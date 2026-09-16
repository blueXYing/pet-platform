package com.petplatform.id.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real pool/session regression; no production credentials or permissive host adapter. */
@Timeout(30)
class PooledSnowflakeMySqlTest {
    private static final SnowflakeProviderSettings SETTINGS = new SnowflakeProviderSettings(17);

    @ParameterizedTest
    @CsvSource({"UTC,UTC,+08:00", "Asia/Shanghai,UTC,+08:00",
            "Asia/Shanghai,Asia/Shanghai,+08:00", "UTC,Asia/Shanghai,-05:30"})
    void acquisitionAndRenewalUseUtcOnEachPooledTransaction(String jvmZone, String driverZone,
                                                            String sessionZone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
        try (var db = new MySqlIdTestDatabase(); var pool = pool(db, driverZone)) {
            db.seedVirgin(17);
            var source = new ResetSessionDataSource(pool, sessionZone);
            var store = new JdbcSnowflakeNodeStore(source);
            List<Connection> held = new ArrayList<>();
            SnowflakeNodeGrant grant;
            long before = System.currentTimeMillis();
            try {
                grant = store.acquire(SETTINGS, UUID.randomUUID(), previous -> {
                    db.virginVerifier(17).verify(previous);
                    // Pin the observation's returned connection so the locking transaction MUST
                    // borrow another physical connection, initially in the non-UTC session zone.
                    try { held.add(pool.getConnection()); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                });
            } finally {
                for (Connection connection : held) connection.close();
            }
            assertEquals(2, source.borrows.size(), "one checkout per REQUIRES_NEW transaction");
            assertNotEquals(source.borrows.get(0), source.borrows.get(1), "two physical MySQL connections exercised");
            assertTrue(grant.dbSampleMillis() >= before - SnowflakeProviderSettings.SKEW_MILLIS);
            assertTrue(grant.dbSampleMillis() <= System.currentTimeMillis() + SnowflakeProviderSettings.SKEW_MILLIS);
            assertEquals(SnowflakeProviderSettings.relativeMillis(grant.dbSampleMillis()), grant.startMillis());
            for (int i = 0; i < 12; i++) grant = store.renew(SETTINGS, grant);
            assertEquals(14, source.borrows.size(), "renew uses one transaction-bound connection too");
            assertEquals(grant.throughMillis(), db.highWater(17));
            assertEquals(1, grant.fence());
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT lease_until FROM snowflake_worker_state WHERE node_id=17")) {
                assertTrue(rs.next());
                assertEquals(grant.dbLeaseUntilMillis(), rs.getObject(1, LocalDateTime.class)
                        .toInstant(ZoneOffset.UTC).toEpochMilli());
            }
            System.out.println("POOL UTC: JVM=" + jvmZone + " driver=" + driverZone + " resetSession="
                    + sessionZone + " physicalIds=" + source.borrows + " renewals=12");
            // A separate, specifically audited virgin node exercises the real SDK and automatic
            // renewal controller, rather than inferring readiness from the store alone.
            db.seedVirgin(18);
            try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source),
                    new SnowflakeProviderSettings(18), db.virginVerifier(18))) {
                long first = nextWhenReady(provider);
                var initial = provider.currentGrant().orElseThrow();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (provider.currentGrant().orElseThrow().dbLeaseUntilMillis() == initial.dbLeaseUntilMillis()
                        && !provider.isClosed() && System.nanoTime() < until) Thread.sleep(10);
                assertFalse(provider.isClosed(), provider.failureReason());
                assertTrue(provider.currentGrant().orElseThrow().dbLeaseUntilMillis() > initial.dbLeaseUntilMillis());
                assertTrue(nextWhenReady(provider) > first);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void delayedRenewalCommitAcknowledgementFailsClosedAndCannotReviveProvider() throws Exception {
        try (var db = new MySqlIdTestDatabase(); var pool = pool(db, "UTC")) {
            db.seedVirgin(17);
            var delayed = new DelayedCommitDataSource(pool);
            try (var provider = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(delayed),
                    SETTINGS, db.virginVerifier(17))) {
                long id = nextWhenReady(provider);
                var original = provider.currentGrant().orElseThrow();
                delayed.armed.set(true);
                try {
                    assertTrue(delayed.committed.await(5, TimeUnit.SECONDS), "actual automatic renewal committed");
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!provider.isClosed() && System.nanoTime() < until) Thread.sleep(5);
                    assertTrue(provider.isClosed());
                    assertEquals("OPERATION_TIMEOUT", provider.failureReason());
                    assertEquals(original, provider.currentGrant().orElseThrow());
                    assertEquals(id, provider.lastPublishedId());
                    long persistedLease = db.jdbc().queryForObject(
                            "SELECT lease_until FROM snowflake_worker_state WHERE node_id=17",
                            (rs, n) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli());
                    assertTrue(persistedLease > original.dbLeaseUntilMillis(), "commit succeeded before ACK delay");
                    assertTrue(db.highWater(17) >= original.throughMillis());
                    assertThrows(IllegalStateException.class, provider::nextId);
                } finally {
                    delayed.release.countDown();
                }
                assertTrue(delayed.returned.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, provider::nextId);
                assertEquals(original, provider.currentGrant().orElseThrow(), "late ACK must not publish a grant");
                assertEquals(id, provider.lastPublishedId());
                System.out.println("RENEWAL: real commit + held ACK -> OPERATION_TIMEOUT; late ACK did not revive provider");
            } finally {
                delayed.release.countDown();
            }
        }
    }

    private static long nextWhenReady(HutoolSnowflakeIdProvider provider) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (;;) {
            try { return provider.nextId(); }
            catch (IllegalStateException failure) {
                if (!failure.getMessage().contains("WARMING") || System.nanoTime() >= until) throw failure;
                Thread.sleep(5);
            }
        }
    }

    /** Test-only ACK delay after a REAL commit; does not claim a spontaneous Docker stall. */
    private static final class DelayedCommitDataSource extends DelegatingDataSource {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);

        DelayedCommitDataSource(HikariDataSource pool) { super(pool); }

        @Override public Connection getConnection() throws java.sql.SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("commit") && armed.compareAndSet(true, false)) {
                                committed.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("ACK test timed out");
                                } finally { returned.countDown(); }
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    static HikariDataSource pool(MySqlIdTestDatabase db, String driverZone) {
        var config = new HikariConfig();
        config.setJdbcUrl(db.databaseUrl() + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone="
                + driverZone + "&forceConnectionTimeZoneToSession=false&connectTimeout=1000&socketTimeout=5000");
        config.setUsername(System.getenv().getOrDefault("PLAT002_ID_MYSQL_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("PLAT002_ID_MYSQL_PASSWORD", ""));
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(3);
        return new HikariDataSource(config);
    }

    /** Simulates pool reuse after another client left a non-UTC session; records real server IDs. */
    static final class ResetSessionDataSource extends DelegatingDataSource {
        final List<Long> borrows = Collections.synchronizedList(new ArrayList<>());
        private final String zone;

        ResetSessionDataSource(HikariDataSource pool, String zone) {
            super(pool);
            this.zone = zone;
        }

        @Override public Connection getConnection() throws java.sql.SQLException {
            Connection connection = super.getConnection();
            try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION time_zone = '" + zone + "'");
                try (var rs = statement.executeQuery("SELECT CONNECTION_ID(), @@session.time_zone")) {
                    assertTrue(rs.next());
                    assertEquals(zone, rs.getString(2));
                    borrows.add(rs.getLong(1));
                }
                return connection;
            } catch (Throwable failure) {
                connection.close();
                throw failure;
            }
        }
    }
}
