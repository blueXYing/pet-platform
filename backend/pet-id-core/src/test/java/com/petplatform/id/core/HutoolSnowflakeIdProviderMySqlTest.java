package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.core.lang.Snowflake;
import com.petplatform.common.DecimalPublicIdCodec;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class HutoolSnowflakeIdProviderMySqlTest {
    private static final SnowflakeProviderSettings SETTINGS = new SnowflakeProviderSettings(17);

    @Test void defaultVerifierFailsBeforeSdkAndDoesNotInventVirginPermission() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(db.dataSource()), SETTINGS)) {
                assertThrows(IllegalStateException.class, provider::nextId);
                assertTrue(provider.isClosed());
                assertEquals(-1, db.highWater(17));
                assertEquals(-1, field(Snowflake.class, "lastTimestamp").getLong(sdk(provider)));
                assertEquals(0, provider.lastPublishedId());
            }
        }
    }

    @Test void unknownGrantCommitFailsWholeProviderWhileKeepingActualMysqlHighWater() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            var lostAck = new CommitAckLossDataSource(db.dataSource(), 2);
            try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(lostAck), SETTINGS, db.virginVerifier(17))) {
                assertThrows(IllegalStateException.class, provider::nextId);
                assertTrue(lostAck.committedBeforeLoss());
                assertTrue(db.highWater(17) > 0);
                long committedHigh = db.highWater(17);
                assertTrue(provider.isClosed());
                assertTrue(provider.currentGrant().isEmpty(), "unacknowledged permission cannot be installed");
                assertEquals(0, provider.lastPublishedId());
                assertEquals(-1, field(Snowflake.class, "lastTimestamp").getLong(sdk(provider)));
                assertThrows(IllegalStateException.class, provider::nextId);
                assertEquals(committedHigh, db.highWater(17));
            }
        }
    }

    @Test void fixedSdkParametersAndConcurrentRealCandidatesRetainStringIdentity() throws Exception {
        try (var db = new MySqlIdTestDatabase(); var callers = Executors.newFixedThreadPool(4)) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                Snowflake sdk = sdk(provider);
                assertEquals(SnowflakeProviderSettings.EPOCH_MILLIS, field(Snowflake.class, "twepoch").getLong(sdk));
                assertEquals(17, field(Snowflake.class, "workerId").getLong(sdk));
                assertEquals(0, field(Snowflake.class, "dataCenterId").getLong(sdk));
                assertFalse(field(Snowflake.class, "useSystemClock").getBoolean(sdk));
                assertEquals(0, field(Snowflake.class, "timeOffset").getLong(sdk));
                assertEquals(0, field(Snowflake.class, "randomSequenceLimit").getLong(sdk));
                assertTrue(Snowflake.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("hutool-core-5.8.47.jar"));
                var futures = new ArrayList<java.util.concurrent.Future<List<Long>>>();
                for (int i = 0; i < 4; i++) futures.add(callers.submit(() -> {
                    var ids = new ArrayList<Long>();
                    for (int n = 0; n < 250; n++) ids.add(provider.nextId());
                    return ids;
                }));
                var ids = new HashSet<Long>();
                var codec = new DecimalPublicIdCodec();
                for (var future : futures) for (long id : future.get(5, TimeUnit.SECONDS)) {
                    assertTrue(ids.add(id), "duplicate returned ID");
                    assertTrue(id > 0);
                    assertEquals(id, codec.fromApi(codec.toApi(id)));
                    assertEquals(17, sdk.getWorkerId(id));
                    assertEquals(0, sdk.getDataCenterId(id));
                }
                assertEquals(1000, ids.size());
                assertEquals(ids.stream().mapToLong(Long::longValue).max().orElseThrow(), provider.lastPublishedId());
                assertFalse(provider.isClosed());
                assertEquals(provider.currentGrant().orElseThrow().throughMillis(), db.highWater(17));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZERO", "NEGATIVE", "REPLAY", "LOWER", "WRONG_NODE", "FUTURE_WINDOW"})
    void candidateGateRejectsInvalidOutputsWithoutRepairOrPublication(String kind) throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                long published = provider.nextId();
                long high = db.highWater(17);
                Snowflake wrongNode = new Snowflake(new Date(SnowflakeProviderSettings.EPOCH_MILLIS), 18, 0, false, 0, 0);
                LongSupplier candidate = switch (kind) {
                    case "ZERO" -> () -> 0;
                    case "NEGATIVE" -> () -> -1;
                    case "REPLAY" -> () -> published;
                    case "LOWER" -> () -> published - 1;
                    case "WRONG_NODE" -> wrongNode::nextId;
                    case "FUTURE_WINDOW" -> () -> ((high + 1) << 22) | (17L << 12);
                    default -> throw new AssertionError(kind);
                };
                // Test-only candidate injection exercises the gate, not Hutool's generation algorithm.
                field(HutoolSnowflakeIdProvider.class, "sdk").set(provider, new CandidateSdk(candidate));
                assertThrows(IllegalStateException.class, provider::nextId, kind);
                assertTrue(provider.isClosed(), kind);
                assertEquals(published, provider.lastPublishedId());
                assertEquals(high, db.highWater(17));
                assertThrows(IllegalStateException.class, provider::nextId);
            }
        }
    }

    @Test void actualSdkRollbackBranchFailsClosedWithoutChangingOsClock() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                long published = provider.nextId();
                // Precisely a private-state SDK branch probe, not a real operating-system rollback.
                field(Snowflake.class, "lastTimestamp").setLong(sdk(provider), System.currentTimeMillis() + 60_000);
                IllegalStateException failure = assertThrows(IllegalStateException.class, provider::nextId);
                assertTrue(causeContains(failure, "Clock moved backwards"), failure.toString());
                assertTrue(provider.isClosed());
                assertEquals(published, provider.lastPublishedId());
            }
        }
    }

    @Test void osDomainIsCheckedIndependentlyOfMaskedCandidateTimestamp() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                long id = provider.nextId();
                var grant = provider.currentGrant().orElseThrow();
                var gate = HutoolSnowflakeIdProvider.class.getDeclaredMethod("validateCandidate", long.class,
                        SnowflakeNodeGrant.class, long.class, long.class, long.class, long.class, long.class);
                gate.setAccessible(true);
                long wrappedOs = SnowflakeProviderSettings.EPOCH_MILLIS + (1L << 42);
                long nano = System.nanoTime();
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> gate.invoke(provider, id, grant, 0L, wrappedOs, wrappedOs, nano, nano));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("epoch range"));
                // Synthetic method inputs test the independent guard; the actual OS was never changed.
            }
        }
    }

    @Test void sdkMonitorBlockTimesOutAndLateActualSdkReturnCannotPublishOrRenew() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var db = new MySqlIdTestDatabase(); var callers = Executors.newFixedThreadPool(2)) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                long published = provider.nextId();
                long high = db.highWater(17);
                var grant = provider.currentGrant().orElseThrow();
                var holder = callers.submit(() -> {
                    try {
                        synchronized (sdk(provider)) {
                            entered.countDown();
                            release.await();
                        }
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var pending = callers.submit(provider::nextId);
                assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
                assertTrue(provider.isClosed());
                assertEquals(published, provider.lastPublishedId());
                release.countDown();
                holder.get(1, TimeUnit.SECONDS);
                Thread.sleep(2200); // Cross the scheduled renewal boundary after failure.
                assertEquals(published, provider.lastPublishedId());
                assertEquals(high, db.highWater(17));
                assertEquals(grant.dbLeaseUntilMillis(), db.jdbc().queryForObject(
                        "SELECT lease_until FROM snowflake_worker_state WHERE node_id=17", java.time.LocalDateTime.class)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
                assertThrows(IllegalStateException.class, provider::nextId);
                // A real SDK monitor was blocked. This is not its private tilNextMillis freeze path.
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void sustainedUseExtendsConfirmedWindowWithoutChangingIncarnationOrFence() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            try (var provider = provider(db)) {
                long previous = provider.nextId();
                var initial = provider.currentGrant().orElseThrow();
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5500);
                while (System.nanoTime() < until) {
                    Thread.sleep(100);
                    long id = provider.nextId();
                    assertTrue(id > previous);
                    previous = id;
                }
                var renewed = provider.currentGrant().orElseThrow();
                assertEquals(initial.incarnation(), renewed.incarnation());
                assertEquals(initial.fence(), renewed.fence());
                assertTrue(renewed.throughMillis() > initial.throughMillis());
                assertEquals(renewed.throughMillis(), db.highWater(17));
                assertFalse(provider.isClosed());
            }
        }
    }

    private static HutoolSnowflakeIdProvider provider(MySqlIdTestDatabase db) {
        return new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(db.dataSource()), SETTINGS, db.virginVerifier(17));
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Snowflake sdk(HutoolSnowflakeIdProvider provider) throws Exception {
        return (Snowflake) field(HutoolSnowflakeIdProvider.class, "sdk").get(provider);
    }
    private static boolean causeContains(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) return true;
        }
        return false;
    }
    private static final class CandidateSdk extends Snowflake {
        private final LongSupplier candidate;
        CandidateSdk(LongSupplier candidate) {
            super(new Date(SnowflakeProviderSettings.EPOCH_MILLIS), 17, 0, false, 0, 0);
            this.candidate = candidate;
        }
        @Override public synchronized long nextId() { return candidate.getAsLong(); }
    }
}
