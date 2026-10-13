package com.petplatform.id.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cn.hutool.core.lang.Snowflake;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only child JVM. Host verification is an explicit stdin handshake bound to the prior process.
 * The handshake runs BEFORE the provider is constructed: the store invokes the verifier seam inside
 * the single-flight lane's fixed one-second budget (a production constant with no test seam), and
 * that budget must never contain a cross-process round trip — under parallel load the child of
 * PR#139 run 37922615049 died with "Provider unavailable: OPERATION_TIMEOUT" before the host had
 * even seen VERIFY. The in-acquire verifier still re-binds the pre-received host confirmation to
 * the row the store itself observes, so acquisition remains impossible without it.
 */
public final class IdProviderProcessProbe {
    /**
     * Distance the recover child lifts the stored reserved boundary past its own wall clock, so
     * the fresh grant provably starts in the future and the WARMING proof cannot be skipped by a
     * slow child boot. Natural timing leaves the boundary barely ahead of the clock.
     */
    private static final long WARMING_LEAD_MILLIS = 3_000L;

    /** Generous overridable waits (seconds); the real exchanges need well under one second each. */
    static long lineTimeoutSeconds() { return Long.getLong("plat002.idtest.lineTimeoutSeconds", 120L); }
    static long warmingBudgetSeconds() { return Long.getLong("plat002.idtest.warmingBudgetSeconds", 60L); }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String url = args[1];
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/plat002_id_test_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Only the parent-created test database may be used");
        }
        int node = Integer.parseInt(args[2]);
        String initialization = args[3];
        BufferedReader host = new BufferedReader(new InputStreamReader(System.in));
        var source = MySqlIdTestDatabase.source(url);
        var jdbc = new JdbcTemplate(source);
        // Force the pool to establish its connections BEFORE any budgeted store transaction:
        // the single-flight lane's 1s production budget covers the acquire's transactions, and
        // waiting for a cold physical connect inside that budget is exactly the runner-noise
        // failure this probe must be immune to. Holding every checkout at once pins all three.
        try (var a = source.getConnection(); var b = source.getConnection(); var c = source.getConnection()) {
            if (!a.isValid(5) || !b.isValid(5) || !c.isValid(5)) {
                throw new IllegalStateException("Warm MySQL connections are not usable");
            }
        }
        final String confirmed;
        if (!mode.equals("hold")) {
            // Deterministic WARMING: lift the stored boundary a fixed distance past this JVM's now
            // (never backwards — GREATEST keeps a naturally later boundary). Fixture surgery on
            // this test's own row, in the same spirit as the parent's lease-expiry injection, and
            // strictly after the parent's oldHigh assertions have run. This read also pre-warms
            // the pooled connection the store's budgeted transactions will borrow.
            long boundary = SnowflakeProviderSettings.relativeMillis(System.currentTimeMillis() + WARMING_LEAD_MILLIS);
            jdbc.update("UPDATE snowflake_worker_state SET grant_through=GREATEST(grant_through,?),"
                    + " reserved_through=GREATEST(reserved_through,?), updated_at=UTC_TIMESTAMP(3) WHERE node_id=?",
                    boundary, boundary, node);
            var row = jdbc.queryForMap(
                    "SELECT owner_incarnation,fence,reserved_through FROM snowflake_worker_state WHERE node_id=?", node);
            System.out.println("VERIFY " + uuid((byte[]) row.get("owner_incarnation")) + " " + row.get("fence")
                    + " " + row.get("reserved_through"));
            System.out.flush();
            confirmed = readLineBounded(host, lineTimeoutSeconds());
            System.out.println("HOST_CONFIRMED");
            System.out.flush();
        } else {
            confirmed = null;
        }
        PreviousJvmExitVerifier verifier = previous -> {
            if (previous.nodeId() != node || !initialization.equals(previous.initializationRef())) {
                throw new IllegalStateException("Wrong fixture node/init evidence");
            }
            if (mode.equals("hold")) {
                if (previous.incarnation() != null || previous.fence() != 0 || previous.reservedThrough() != -1) {
                    throw new IllegalStateException("First child requires exactly its audited virgin fixture");
                }
            } else {
                if (!UUID.fromString(args[4]).equals(previous.incarnation()) || Long.parseLong(args[5]) != previous.fence()) {
                    throw new IllegalStateException("Unexpected prior incarnation/fence");
                }
                String expected = "EXIT_VERIFIED:" + args[4] + ":" + args[6] + ":" + args[7];
                if (!expected.equals(confirmed)) throw new IllegalStateException("No bound host exit confirmation");
            }
        };
        try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source), new SnowflakeProviderSettings(node), verifier)) {
            Field sdkField = HutoolSnowflakeIdProvider.class.getDeclaredField("sdk");
            sdkField.setAccessible(true);
            Snowflake sdk = (Snowflake) sdkField.get(provider);
            Field last = Snowflake.class.getDeclaredField("lastTimestamp");
            last.setAccessible(true);
            Long warmingHigh = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmingBudgetSeconds());
            long id;
            for (;;) {
                try { id = provider.nextId(); break; }
                catch (IllegalStateException warming) {
                    if (provider.isClosed() || System.nanoTime() >= until) throw warming;
                    var pending = provider.currentGrant().orElseThrow();
                    long high = jdbc.queryForObject("SELECT reserved_through FROM snowflake_worker_state WHERE node_id=?", Long.class, node);
                    if (warmingHigh == null) {
                        warmingHigh = high;
                        System.out.println("WARMING " + pending.incarnation() + " " + pending.startMillis() + " " + high);
                        System.out.flush();
                    }
                    if (high != warmingHigh || high != pending.throughMillis() || sdkField.get(provider) != sdk
                            || last.getLong(sdk) != -1 || provider.lastPublishedId() != 0) {
                        throw new IllegalStateException("WARMING consumed SDK state, lost grant or advanced H");
                    }
                    Thread.sleep(25);
                }
            }
            var grant = provider.currentGrant().orElseThrow();
            String start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
            System.out.println("READY " + mode + " " + ProcessHandle.current().pid() + " " + start + " "
                    + grant.incarnation() + " " + grant.fence() + " " + grant.startMillis() + " " + grant.throughMillis() + " " + id);
            System.out.flush();
            if (mode.equals("hold")) {
                if (!"HALT".equals(host.readLine())) throw new IllegalStateException("Expected test host halt command");
                Runtime.getRuntime().halt(23); // Real process exit, bypassing provider.close and shutdown hooks.
            }
        }
    }

    /** Bounded stdin read: a generous explicit deadline keeps a lost host from hanging the child
     *  forever; it must dwarf the real exchange (milliseconds) so it can never mask the behavior
     *  under test. The reading thread is a daemon so a timeout still lets the JVM exit. */
    private static String readLineBounded(BufferedReader host, long timeoutSeconds) throws Exception {
        var single = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            return single.submit(host::readLine).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("Host line not received within " + timeoutSeconds + "s");
        } finally {
            single.shutdownNow();
        }
    }

    private static UUID uuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) throw new IllegalStateException("Invalid owner incarnation");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
