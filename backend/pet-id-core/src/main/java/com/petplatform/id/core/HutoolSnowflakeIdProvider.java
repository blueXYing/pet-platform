package com.petplatform.id.core;

import cn.hutool.core.lang.Snowflake;
import com.petplatform.common.SnowflakeIdGenerator;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import static com.petplatform.id.core.SnowflakeProviderSettings.*;

/**
 * Opt-in production logic component. No Spring bean, deployment verifier or automatic migration.
 * Hutool owns the algorithm. A failed instance is terminal, even if its SDK later returns.
 */
public final class HutoolSnowflakeIdProvider implements SnowflakeIdGenerator, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(HutoolSnowflakeIdProvider.class.getName());
    private record State(SnowflakeNodeGrant grant, long lastPublished, long lastObservedUtc, long lastObservedNano) {}
    private record Attempt(Long id) {}
    private final SnowflakeProviderSettings settings;
    private final JdbcSnowflakeNodeStore store;
    private final PreviousJvmExitVerifier verifier;
    private final UUID incarnation = UUID.randomUUID();
    private final Snowflake sdk;
    private final SingleFlightLane<State> lane;
    private final Thread renewals;

    public HutoolSnowflakeIdProvider(JdbcSnowflakeNodeStore store, SnowflakeProviderSettings settings) {
        this(store, settings, PreviousJvmExitVerifier.rejecting());
    }

    public HutoolSnowflakeIdProvider(JdbcSnowflakeNodeStore store, SnowflakeProviderSettings settings,
                                    PreviousJvmExitVerifier verifier) {
        this.store = Objects.requireNonNull(store);
        this.settings = Objects.requireNonNull(settings);
        this.verifier = Objects.requireNonNull(verifier);
        sdk = new Snowflake(new Date(EPOCH_MILLIS), settings.workerId(), settings.dataCenterId(), false, 0, 0);
        lane = new SingleFlightLane<>(new State(null, 0, 0, 0));
        renewals = Thread.ofPlatform().daemon().name("snowflake-permission-controller").unstarted(this::maintainPermission);
        renewals.start();
    }

    @Override public long nextId() {
        try {
            Attempt attempt = lane.invoke(context -> {
                State previous = context.state();
                context.checkActive();
                SnowflakeNodeGrant grant = permission(previous.grant);
                context.checkActive();
                grant.requireLocallyValid(System.nanoTime());
                long beforeNano = System.nanoTime();
                long before = System.currentTimeMillis();
                checkAgainstPrevious(previous, before, beforeNano);
                long relative = relativeMillis(before);
                if (relative < grant.startMillis()) {
                    // The grant has a known commit ACK, but SDK has not been called. Preserve this
                    // single waiting grant; fail this request without re-reserving/burning future time.
                    context.checkActive();
                    return new SingleFlightLane.Proposal<>(
                            new State(grant, previous.lastPublished, before, beforeNano), new Attempt(null));
                }
                if (relative > grant.throughMillis()) throw new IllegalStateException("Confirmed range exhausted");
                context.checkActive();
                long candidate = sdk.nextId(); // The sole generation algorithm; never called by a controller thread.
                long after = System.currentTimeMillis();
                long afterNano = System.nanoTime();
                validateCandidate(candidate, grant, previous.lastPublished, before, after, beforeNano, afterNano);
                context.checkActive();
                return new SingleFlightLane.Proposal<>(new State(grant, candidate, after, afterNano), new Attempt(candidate));
            });
            if (attempt.id == null) throw new IllegalStateException("OS UTC has not reached the confirmed grant; WARMING");
            return attempt.id;
        } finally {
            if (lane.isClosed()) LockSupport.unpark(renewals);
        }
    }

    private SnowflakeNodeGrant permission(SnowflakeNodeGrant grant) {
        if (grant == null) return store.acquire(settings, incarnation, verifier);
        long nowNano = System.nanoTime();
        grant.requireLocallyValid(nowNano);
        long now = relativeMillis(System.currentTimeMillis());
        if (grant.throughMillis() - now <= REFILL_MILLIS
                || nowNano - grant.requestStartedNanos() >= RENEW_MILLIS * 1_000_000L) {
            return store.renew(settings, grant);
        }
        return grant;
    }

    private void maintainPermission() {
        while (!lane.isClosed()) {
            LockSupport.parkNanos(RENEW_MILLIS * 1_000_000L);
            if (!lane.isClosed()) renewIfActive();
        }
        if (!"CLOSED".equals(lane.failureReason())) {
            // Asynchronous diagnostic only; callers never wait for logging or an alert transport.
            LOG.log(System.Logger.Level.WARNING, "Snowflake provider failed closed: {0}", lane.failureReason());
        }
    }

    private void renewIfActive() {
        if (lane.isClosed()) return;
        if (lane.state().grant == null) return; // Do not acquire any node before an explicit first request.
        try {
            lane.state().grant.requireLocallyValid(System.nanoTime());
            lane.invoke(context -> {
                State previous = context.state();
                context.checkActive();
                SnowflakeNodeGrant grant = store.renew(settings, previous.grant);
                context.checkActive();
                long nowNano = System.nanoTime();
                long now = System.currentTimeMillis();
                checkAgainstPrevious(previous, now, nowNano);
                grant.requireLocallyValid(nowNano);
                return new SingleFlightLane.Proposal<>(new State(grant, previous.lastPublished, now, nowNano), null);
            });
        } catch (RuntimeException failure) {
            // Slot contention before admission is not a failed DB/SDK operation. The lane closes itself
            // for every admitted failure. An expired local grant must still stop an idle/blocked provider.
            SnowflakeNodeGrant grant = lane.state().grant;
            if (grant != null) {
                try { grant.requireLocallyValid(System.nanoTime()); }
                catch (RuntimeException expired) { lane.close("LOCAL_PERMISSION_EXPIRED"); }
            }
        }
    }

    private void validateCandidate(long id, SnowflakeNodeGrant grant, long lastPublished,
                                   long before, long after, long beforeNano, long afterNano) {
        relativeMillis(before);
        relativeMillis(after);
        long elapsedNano = afterNano - beforeNano;
        if (elapsedNano < 0 || after < before || Math.abs((after - before) - elapsedNano / 1_000_000L) > SKEW_MILLIS) {
            throw new IllegalStateException("Unsafe OS UTC sample");
        }
        if (id <= 0 || id <= lastPublished || sdk.getWorkerId(id) != settings.workerId()
                || sdk.getDataCenterId(id) != settings.dataCenterId()) {
            throw new IllegalStateException("Invalid or non-increasing SDK candidate");
        }
        long encoded = sdk.getGenerateDateTime(id);
        long relative = relativeMillis(encoded);
        if (encoded < before || encoded > after || relative < grant.startMillis() || relative > grant.throughMillis()) {
            throw new IllegalStateException("SDK candidate outside confirmed time/OS sample");
        }
        grant.requireLocallyValid(afterNano);
    }

    private static void checkAgainstPrevious(State previous, long utc, long nano) {
        relativeMillis(utc);
        if (previous.lastObservedUtc != 0) {
            long elapsed = nano - previous.lastObservedNano;
            if (elapsed < 0 || utc < previous.lastObservedUtc
                    || Math.abs((utc - previous.lastObservedUtc) - elapsed / 1_000_000L) > SKEW_MILLIS) {
                throw new IllegalStateException("Observed OS clock rollback/jump");
            }
        }
    }

    public boolean isClosed() { return lane.isClosed(); }
    public String failureReason() { return lane.failureReason(); }
    public long lastPublishedId() { return lane.state().lastPublished; }
    public Optional<SnowflakeNodeGrant> currentGrant() { return Optional.ofNullable(lane.state().grant); }
    public UUID incarnation() { return incarnation; }

    @Override public void close() {
        lane.close();
        LockSupport.unpark(renewals); // No interrupt/cancel claim about a blocked Hutool thread.
    }
}
