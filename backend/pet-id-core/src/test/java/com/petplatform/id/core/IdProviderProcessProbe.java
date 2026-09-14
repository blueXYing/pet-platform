package com.petplatform.id.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import cn.hutool.core.lang.Snowflake;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only child JVM. Host verification is an explicit stdin handshake bound to the prior process. */
public final class IdProviderProcessProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String url = args[1];
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/plat002_id_test_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Only the parent-created test database may be used");
        }
        int node = Integer.parseInt(args[2]);
        String initialization = args[3];
        BufferedReader host = new BufferedReader(new InputStreamReader(System.in));
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
                System.out.println("VERIFY " + previous.incarnation() + " " + previous.fence() + " " + previous.reservedThrough());
                System.out.flush();
                try {
                    String expected = "EXIT_VERIFIED:" + args[4] + ":" + args[6] + ":" + args[7];
                    if (!expected.equals(host.readLine())) throw new IllegalStateException("No bound host exit confirmation");
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
        };
        var source = MySqlIdTestDatabase.source(url);
        var jdbc = new JdbcTemplate(source);
        try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source), new SnowflakeProviderSettings(node), verifier)) {
            Field sdkField = HutoolSnowflakeIdProvider.class.getDeclaredField("sdk");
            sdkField.setAccessible(true);
            Snowflake sdk = (Snowflake) sdkField.get(provider);
            Field last = Snowflake.class.getDeclaredField("lastTimestamp");
            last.setAccessible(true);
            Long warmingHigh = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
}
