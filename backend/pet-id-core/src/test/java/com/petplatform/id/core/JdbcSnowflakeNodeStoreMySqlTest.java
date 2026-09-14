package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcSnowflakeNodeStoreMySqlTest {
    private static final SnowflakeProviderSettings SETTINGS = new SnowflakeProviderSettings(17);

    @Test void missingAndUnverifiedInitializationNeverCreatesOrClaimsNode() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            assertThrows(IllegalStateException.class, () -> store.acquire(SETTINGS, UUID.randomUUID(), PreviousJvmExitVerifier.rejecting()));
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM snowflake_worker_state", Integer.class));
            db.seedVirgin(17);
            assertThrows(IllegalStateException.class, () -> store.acquire(SETTINGS, UUID.randomUUID(), PreviousJvmExitVerifier.rejecting()));
            assertEquals(-1, db.highWater(17));
        }
    }

    @Test void committedGrantRenewalAndDisabledNodePreserveAtomicState() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            UUID incarnation = UUID.randomUUID();
            var first = store.acquire(SETTINGS, incarnation, db.virginVerifier(17));
            assertEquals(1, first.fence());
            assertEquals(incarnation, first.incarnation());
            assertEquals(SnowflakeProviderSettings.relativeMillis(first.dbSampleMillis()), first.startMillis());
            assertEquals(4999, first.throughMillis() - first.startMillis());
            assertEquals(first.throughMillis(), db.highWater(17));
            var renewed = store.renew(SETTINGS, first);
            assertEquals(first.startMillis(), renewed.startMillis());
            assertEquals(first.fence(), renewed.fence());
            assertTrue(renewed.throughMillis() >= first.throughMillis());
            assertEquals(renewed.throughMillis(), db.highWater(17));
            var forged = new SnowflakeNodeGrant(17, incarnation, renewed.fence() + 1, renewed.startMillis(),
                    renewed.throughMillis(), renewed.dbLeaseUntilMillis(), renewed.requestStartedNanos(), renewed.dbSampleMillis());
            assertThrows(IllegalStateException.class, () -> store.renew(SETTINGS, forged));
            db.jdbc().update("UPDATE snowflake_worker_state SET enabled=FALSE WHERE node_id=17");
            assertThrows(IllegalStateException.class, () -> store.renew(SETTINGS, renewed));
            assertEquals(renewed.throughMillis(), db.highWater(17));
        }
    }

    @Test void twoVerifiedVirginObservationsCannotBothAcquireSameNode() throws Exception {
        try (var db = new MySqlIdTestDatabase(); var pool = Executors.newFixedThreadPool(2)) {
            db.seedVirgin(17);
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            var bothObserved = new CountDownLatch(2);
            PreviousJvmExitVerifier barrier = previous -> {
                db.virginVerifier(17).verify(previous);
                bothObserved.countDown();
                try { if (!bothObserved.await(700, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Both observations not reached"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            };
            var futures = new ArrayList<java.util.concurrent.Future<SnowflakeNodeGrant>>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> store.acquire(SETTINGS, UUID.randomUUID(), barrier)));
            var winners = new ArrayList<SnowflakeNodeGrant>();
            int rejected = 0;
            for (var future : futures) {
                try { winners.add(future.get(3, TimeUnit.SECONDS)); }
                catch (ExecutionException failure) {
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
                    rejected++;
                }
            }
            assertEquals(1, winners.size());
            assertEquals(1, rejected);
            assertEquals(1, db.jdbc().queryForObject("SELECT fence FROM snowflake_worker_state WHERE node_id=17", Long.class));
            assertEquals(winners.getFirst().throughMillis(), db.highWater(17));
        }
    }

    @Test void hostVerificationRunsWithoutRowLockAndChangedObservationCannotCommit() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            PreviousJvmExitVerifier changed = previous -> {
                db.virginVerifier(17).verify(previous);
                db.jdbc().update("UPDATE snowflake_worker_state SET enabled=FALSE WHERE node_id=17");
            };
            assertThrows(IllegalStateException.class, () -> store.acquire(SETTINGS, UUID.randomUUID(), changed));
            assertEquals(-1, db.highWater(17));
        }
    }

    @Test void realCommitWithLostAckKeepsReservedRangeAndDoesNotReturnGrant() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            // acquire first commits a read-only observation, then its range mutation.
            var lostAck = new CommitAckLossDataSource(db.dataSource(), 2);
            var store = new JdbcSnowflakeNodeStore(lostAck);
            assertThrows(RuntimeException.class, () -> store.acquire(SETTINGS, UUID.randomUUID(), db.virginVerifier(17)));
            assertTrue(lostAck.committedBeforeLoss());
            assertTrue(db.highWater(17) > 0, "actual committed H survives missing acknowledgement");
            long committedHigh = db.highWater(17);
            assertThrows(IllegalStateException.class, () -> new JdbcSnowflakeNodeStore(db.dataSource())
                    .acquire(SETTINGS, UUID.randomUUID(), db.virginVerifier(17)));
            assertEquals(committedHigh, db.highWater(17));
        }
    }

    @Test void callerRollbackCannotReclaimCommittedNodeRange() throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            var outer = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            var grant = outer.execute(status -> {
                var committed = store.acquire(SETTINGS, UUID.randomUUID(), db.virginVerifier(17));
                status.setRollbackOnly();
                return committed;
            });
            assertNotNull(grant);
            assertEquals(grant.throughMillis(), db.highWater(17));
        }
    }

    @Test void leaseIsRecheckedWithFreshDbTimeAfterActualLockWait() throws Exception {
        try (var db = new MySqlIdTestDatabase(); var pool = Executors.newSingleThreadExecutor()) {
            db.seedVirgin(17);
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            var first = store.acquire(SETTINGS, UUID.randomUUID(), db.virginVerifier(17));
            long expires = System.currentTimeMillis() + 350;
            db.jdbc().update("UPDATE snowflake_worker_state SET lease_until=? WHERE node_id=17",
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(expires), ZoneOffset.UTC));
            var shortened = new SnowflakeNodeGrant(17, first.incarnation(), first.fence(), first.startMillis(),
                    first.throughMillis(), expires, first.requestStartedNanos(), first.dbSampleMillis());
            try (Connection lock = db.dataSource().getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.createStatement()) {
                    statement.executeQuery("SELECT * FROM snowflake_worker_state WHERE node_id=17 FOR UPDATE").close();
                }
                var pending = pool.submit(() -> store.renew(SETTINGS, shortened));
                long observeUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                boolean observed = false;
                do {
                    int waits = db.jdbc().queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l
                              ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='snowflake_worker_state'
                            """, Integer.class);
                    if (waits > 0) { observed = true; break; }
                    Thread.sleep(5);
                } while (System.nanoTime() < observeUntil);
                assertTrue(observed, "must observe real MySQL lock waiting, not merely schedule a future");
                long remaining = expires + 40 - System.currentTimeMillis();
                if (remaining > 0) Thread.sleep(remaining);
                lock.commit();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("Expired"), failure.getCause().toString());
            }
            assertEquals(first.throughMillis(), db.highWater(17));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"41_BIT_END", "MAX_AHEAD"})
    void auditedExistingHighWaterRejectsUnsafeNewWindowWithoutMutatingRow(String boundary) throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            long high = boundary.equals("41_BIT_END")
                    ? SnowflakeProviderSettings.MAX_RELATIVE_MILLIS - 2500
                    : SnowflakeProviderSettings.relativeMillis(System.currentTimeMillis()) + SnowflakeProviderSettings.MAX_AHEAD_MILLIS;
            PreviousJvmExitVerifier audited = db.seedAuditedBoundary(17, high);
            var original = db.jdbc().queryForMap("SELECT * FROM snowflake_worker_state WHERE node_id=17");
            String reference = (String) original.get("initialization_ref");
            assertThrows(IllegalStateException.class, () -> audited.verify(
                    new PreviousJvmExitVerifier.PreviousJvm(17, null, 0, reference, high - 1)));
            assertThrows(IllegalStateException.class, () -> audited.verify(
                    new PreviousJvmExitVerifier.PreviousJvm(17, null, 0, "wrong-audit", high)));
            IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                    new JdbcSnowflakeNodeStore(db.dataSource()).acquire(SETTINGS, UUID.randomUUID(), audited));
            assertTrue(rejected.getMessage().contains("Reservation outside"), rejected.toString());
            assertEquals(original, db.jdbc().queryForMap("SELECT * FROM snowflake_worker_state WHERE node_id=17"),
                    "rejection must leave H/fence/owner/boundaries and audit timestamps unchanged");
            assertEquals(high, db.highWater(17));
            assertEquals(0L, original.get("fence"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualExpansionCommitsHighWaterAndGrantTogetherEvenWhenAcknowledgementIsLost(boolean loseAck) throws Exception {
        try (var db = new MySqlIdTestDatabase()) {
            db.seedVirgin(17);
            var store = new JdbcSnowflakeNodeStore(db.dataSource());
            var original = store.acquire(SETTINGS, UUID.randomUUID(), db.virginVerifier(17));
            // Explicit TEST-ONLY boundary injection: no IDs were generated; make the existing
            // reserved range short enough to trigger expansion without modifying production settings.
            long shortHigh = Math.max(original.startMillis(),
                    SnowflakeProviderSettings.relativeMillis(System.currentTimeMillis())) + 500;
            assertTrue(shortHigh < original.throughMillis());
            db.jdbc().update("""
                    UPDATE snowflake_worker_state SET grant_through=?,reserved_through=?
                    WHERE node_id=17 AND fence=? AND reserved_through=?
                    """, shortHigh, shortHigh, original.fence(), original.throughMillis());
            var nearEnd = new SnowflakeNodeGrant(17, original.incarnation(), original.fence(), original.startMillis(),
                    shortHigh, original.dbLeaseUntilMillis(), original.requestStartedNanos(), original.dbSampleMillis());
            var lostAck = new CommitAckLossDataSource(db.dataSource(), 1);
            var expanding = loseAck ? new JdbcSnowflakeNodeStore(lostAck) : store;
            var receipt = new java.util.concurrent.atomic.AtomicReference<SnowflakeNodeGrant>();
            if (loseAck) {
                assertThrows(RuntimeException.class, () -> receipt.set(expanding.renew(SETTINGS, nearEnd)));
                assertTrue(lostAck.committedBeforeLoss());
                assertNull(receipt.get(), "no local grant receipt is delivered without commit acknowledgement");
            } else {
                receipt.set(expanding.renew(SETTINGS, nearEnd));
            }
            // One real post-commit row snapshot validates paired H/U and unchanged ownership.
            var snapshot = db.jdbc().queryForObject("SELECT *,BIN_TO_UUID(owner_incarnation) AS owner_text FROM snowflake_worker_state WHERE node_id=17",
                    (rs, index) -> new ExpansionSnapshot(rs.getLong("grant_start"), rs.getLong("grant_through"),
                            rs.getLong("reserved_through"), rs.getLong("fence"), UUID.fromString(rs.getString("owner_text")),
                            rs.getObject("lease_until", LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli()));
            assertNotNull(snapshot);
            assertEquals(original.startMillis(), snapshot.start());
            assertEquals(original.fence(), snapshot.fence());
            assertEquals(original.incarnation(), snapshot.owner());
            assertTrue(snapshot.high() > shortHigh, "must actually expand, not merely extend the lease");
            assertEquals(snapshot.high(), snapshot.through());
            long sampledDbTime = snapshot.leaseUntil() - SnowflakeProviderSettings.LEASE_MILLIS;
            assertEquals(Math.max(shortHigh + 1, SnowflakeProviderSettings.relativeMillis(sampledDbTime)
                    + SnowflakeProviderSettings.WINDOW_MILLIS - 1), snapshot.high());
            if (!loseAck) {
                assertEquals(snapshot.high(), receipt.get().throughMillis());
                assertEquals(snapshot.start(), receipt.get().startMillis());
                assertEquals(snapshot.fence(), receipt.get().fence());
            }
            assertThrows(IllegalStateException.class, () -> store.renew(SETTINGS, nearEnd),
                    "an old receipt cannot overwrite the newly committed expanded window");
            assertEquals(snapshot.high(), db.highWater(17));
        }
    }

    private record ExpansionSnapshot(long start, long through, long high, long fence, UUID owner, long leaseUntil) {}
}
