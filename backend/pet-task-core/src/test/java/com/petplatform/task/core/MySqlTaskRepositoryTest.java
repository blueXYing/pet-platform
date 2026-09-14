package com.petplatform.task.core;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** SQL13 invariants on MySQL itself; unavailable database is a failure, never a skip. */
class MySqlTaskRepositoryTest {
    private MySqlTestDatabase db;
    private JdbcAsyncTaskRepository repository;
    private final AtomicLong ids = new AtomicLong(100_000);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeEach
    void open() throws Exception {
        db = new MySqlTestDatabase();
        repository = db.repository(ids::incrementAndGet);
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void multipleWorkersClaimEveryTaskOnceAndTaskKeyIsUnique() throws Exception {
        for (int i = 1; i <= 12; i++) db.seed(i, "concurrent-" + i, "TEST", 2);
        assertThrows(DuplicateKeyException.class, () -> db.seed(99, "concurrent-1", "TEST", 2));
        CountDownLatch start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<Optional<TaskLease>>>();
        for (int i = 0; i < 24; i++) {
            String owner = "worker-" + i;
            calls.add(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                // SKIP LOCKED may transiently find no row during another claim's scan.
                // Real workers poll; an empty claim does not prove the queue is drained.
                for (int poll = 0; poll < 50; poll++) {
                    Optional<TaskLease> lease = repository.claim(owner, LEASE);
                    if (lease.isPresent()) return lease;
                    Thread.sleep(10);
                }
                return Optional.empty();
            });
        }
        var claimed = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(24)) {
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                Optional<TaskLease> lease = future.get(15, TimeUnit.SECONDS);
                if (lease.isPresent()) assertTrue(claimed.add(lease.get().taskId()), "duplicate claim");
            }
        }
        assertEquals(12, claimed.size());
        assertEquals(12, count("async_task_attempt"));
        assertEquals(12, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task WHERE status='RUNNING'", Integer.class));
    }

    @Test
    void skipLockedDoesNotWaitForAnExternallyHeldRowLock() throws Exception {
        db.seed(1, "locked", "TEST", 1);
        db.seed(2, "available", "TEST", 1);
        try (Connection connection = db.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeQuery("SELECT id FROM async_task WHERE id=1 FOR UPDATE").close();
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var future = executor.submit(() -> repository.claim("skip-worker", LEASE));
                    assertEquals(2, future.get(3, TimeUnit.SECONDS).orElseThrow().taskId());
                }
            } finally {
                connection.rollback();
            }
        }
        assertEquals(1, repository.claim("next-worker", LEASE).orElseThrow().taskId());
    }

    @Test
    void sameOwnerTakeoverUsesNewVersionAndRejectsBothOldWrites() {
        db.seed(1, "recovery", "TEST", 2);
        TaskLease old = repository.claim("reused-owner", LEASE).orElseThrow();
        expire(1);
        TaskLease current = db.repository(ids::incrementAndGet).claim("reused-owner", LEASE).orElseThrow();
        assertEquals(old.version() + 1, current.version());
        assertEquals(2, current.attemptNo());
        assertEquals("RETRY", attempt(old, "result"));
        assertEquals("LEASE_EXPIRED", attempt(old, "error_code"));
        assertFalse(repository.heartbeat(old, LEASE));
        assertFalse(repository.complete(old, new TaskExecutionResult.Success("STALE")));
        assertTrue(repository.complete(current, new TaskExecutionResult.Success("RECOVERED")));
        assertFalse(repository.complete(old, new TaskExecutionResult.Dead("STALE")));
        assertEquals("RECOVERED", task("last_result_code"));
        assertEquals("SUCCESS", attempt(current, "result"));
    }

    @Test
    void expiryRejectsCompletionAndHeartbeatEvenBeforeTakeover() {
        db.seed(1, "expired", "TEST", 1);
        TaskLease lease = repository.claim("worker", LEASE).orElseThrow();
        expire(1);
        assertFalse(repository.complete(lease, new TaskExecutionResult.Success("LATE")));
        assertFalse(repository.heartbeat(lease, LEASE));
        assertEquals("RUNNING", task("status"));
        assertNull(attempt(lease, "result"));
    }

    @Test
    void completionRechecksDatabaseTimeAfterWaitingBeyondLease() throws Exception {
        assertExpiredWhileWaitingForLock(false);
    }

    @Test
    void heartbeatRechecksDatabaseTimeAfterWaitingBeyondLease() throws Exception {
        assertExpiredWhileWaitingForLock(true);
    }

    private void assertExpiredWhileWaitingForLock(boolean heartbeat) throws Exception {
        db.seed(1, "lock-wait-expiry", "TEST", 1);
        TaskLease lease = repository.claim("worker", Duration.ofSeconds(2)).orElseThrow();
        try (var executor = Executors.newSingleThreadExecutor();
             Connection connection = db.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT id FROM async_task WHERE id=1 FOR UPDATE").close();
                }
                var result = executor.submit(() -> heartbeat
                        ? repository.heartbeat(lease, LEASE)
                        : repository.complete(lease, new TaskExecutionResult.Success("TOO_LATE")));
                // Observe the actual InnoDB wait instead of assuming a thread has started.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    int waits = db.jdbc().queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l
                              ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='async_task'
                            """, Integer.class);
                    if (waits > 0) { blocked = true; break; }
                    Thread.sleep(10);
                }
                assertTrue(blocked, "repository statement must actually wait on the task row");
                assertEquals(1, db.jdbc().queryForObject(
                        "SELECT lease_until >= NOW(3) FROM async_task WHERE id=1", Integer.class),
                        "wait must begin before expiry to exercise statement-start NOW semantics");
                while (db.jdbc().queryForObject(
                        "SELECT lease_until >= NOW(3) FROM async_task WHERE id=1", Integer.class) == 1) {
                    assertTrue(System.nanoTime() < deadline, "database lease did not expire");
                    Thread.sleep(10);
                }
                assertFalse(result.isDone(), "external lock must still block the repository");
                connection.rollback();
                assertFalse(result.get(5, TimeUnit.SECONDS));
                assertEquals("RUNNING", task("status"));
                assertNull(attempt(lease, "result"));
                assertEquals(1, db.jdbc().queryForObject(
                        "SELECT lease_until < NOW(3) FROM async_task WHERE id=1", Integer.class));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void successfulHeartbeatExtendsUsingDatabaseTime() {
        db.seed(1, "heartbeat", "TEST", 1);
        TaskLease lease = repository.claim("worker", Duration.ofSeconds(5)).orElseThrow();
        assertTrue(repository.heartbeat(lease, Duration.ofSeconds(30)));
        long remaining = db.jdbc().queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND,NOW(3),lease_until) FROM async_task WHERE id=1", Long.class);
        assertTrue(remaining >= 28 && remaining <= 30);
        assertEquals(lease.version(), db.jdbc().queryForObject("SELECT version FROM async_task WHERE id=1", Long.class));
    }

    @Test
    void missingExactAttemptRollsBackTaskResultAndLeaseCleanup() {
        db.seed(1, "atomic-result", "TEST", 1);
        TaskLease lease = repository.claim("worker", LEASE).orElseThrow();
        db.jdbc().update("DELETE FROM async_task_attempt WHERE id=?", lease.attemptId());
        assertThrows(IllegalStateException.class,
                () -> repository.complete(lease, new TaskExecutionResult.Success("OK")));
        assertEquals("RUNNING", task("status"));
        assertEquals("worker", task("lease_owner"));
        assertNull(task("last_result_code"));
        assertNull(db.jdbc().queryForObject("SELECT finished_at FROM async_task WHERE id=1", Object.class));
    }

    @Test
    void duplicateAttemptIdRollsBackClaimAndAttemptNumberIsUnique() {
        db.seed(1, "first", "TEST", 1);
        db.seed(2, "second", "TEST", 1);
        TaskLease first = repository.claim("worker-1", LEASE).orElseThrow();
        assertThrows(DuplicateKeyException.class,
                () -> db.repository(() -> first.attemptId()).claim("worker-2", LEASE));
        assertEquals("READY", db.jdbc().queryForObject("SELECT status FROM async_task WHERE id=2", String.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT version FROM async_task WHERE id=2", Integer.class));
        assertThrows(DuplicateKeyException.class, () -> db.jdbc().update("""
                INSERT INTO async_task_attempt (id,task_id,attempt_no,instance_id,started_at,created_at)
                VALUES (999999,1,1,'duplicate',NOW(3),NOW(3))
                """));
        assertEquals(1, count("async_task_attempt"));
    }

    @Test
    void retryWaitIsDueOnlyByDatabaseClockAndLimitBecomesDead() {
        db.seed(1, "retry", "TEST", 1);
        TaskLease first = repository.claim("worker", LEASE).orElseThrow();
        assertTrue(repository.complete(first, new TaskExecutionResult.Retry("TEMP", Duration.ofMinutes(5))));
        assertEquals("RETRY_WAIT", task("status"));
        assertEquals("RETRY", attempt(first, "result"));
        assertNull(task("lease_owner"));
        assertTrue(repository.claim("worker", LEASE).isEmpty());
        long delay = db.jdbc().queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND,NOW(3),execute_at) FROM async_task WHERE id=1", Long.class);
        assertTrue(delay >= 298 && delay <= 300);
        db.jdbc().update("UPDATE async_task SET execute_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=1");
        TaskLease retry = db.repository(ids::incrementAndGet).claim("restarted", LEASE).orElseThrow();
        assertEquals(1, retry.retryCount());
        assertEquals(2, retry.attemptNo());
        assertTrue(repository.complete(retry, new TaskExecutionResult.Retry("AGAIN", Duration.ofSeconds(1))));
        assertEquals("DEAD", task("status"));
        assertEquals("DEAD", attempt(retry, "result"));
        assertEquals(2, db.jdbc().queryForObject("SELECT retry_count FROM async_task WHERE id=1", Integer.class));
        assertTrue(repository.claim("worker", LEASE).isEmpty());
    }

    @Test
    void futureReadyTaskIsNotClaimedUntilDatabaseDeadline() {
        db.seed(1, "future", "TEST", 1);
        db.jdbc().update("UPDATE async_task SET execute_at=TIMESTAMPADD(DAY,1,NOW(3)) WHERE id=1");
        assertTrue(repository.claim("worker", LEASE).isEmpty());
        db.jdbc().update("UPDATE async_task SET execute_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=1");
        assertEquals(1, repository.claim("worker", LEASE).orElseThrow().taskId());
    }

    @Test
    void successNoopCancellationAndDeadHavePairedFinalAttempts() {
        TaskExecutionResult[] results = {
            new TaskExecutionResult.Success("OK"), new TaskExecutionResult.Success("NOOP"),
            new TaskExecutionResult.Cancelled("OBSOLETE"), new TaskExecutionResult.Dead("INVALID")
        };
        String[] states = {"SUCCEEDED", "SUCCEEDED", "CANCELED", "DEAD"};
        String[] attempts = {"SUCCESS", "NOOP", "NOOP", "DEAD"};
        for (int i = 0; i < results.length; i++) {
            long id = i + 1;
            db.seed(id, "terminal-" + id, "TEST", 1);
            TaskLease lease = repository.claim("worker", LEASE).orElseThrow();
            assertTrue(repository.complete(lease, results[i]));
            assertEquals(states[i], db.jdbc().queryForObject("SELECT status FROM async_task WHERE id=?", String.class, id));
            assertEquals(attempts[i], attempt(lease, "result"));
            assertNotNull(db.jdbc().queryForObject("SELECT finished_at FROM async_task WHERE id=?", Object.class, id));
            assertNotNull(db.jdbc().queryForObject("SELECT duration_ms FROM async_task_attempt WHERE id=?", Long.class, lease.attemptId()));
            assertNull(db.jdbc().queryForObject("SELECT lease_owner FROM async_task WHERE id=?", String.class, id));
            assertFalse(repository.complete(lease, new TaskExecutionResult.Success("DUPLICATE")));
        }
        assertTrue(repository.claim("worker", LEASE).isEmpty());
    }

    private void expire(long id) {
        db.jdbc().update("UPDATE async_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
    }
    private String task(String column) {
        return db.jdbc().queryForObject("SELECT " + column + " FROM async_task WHERE id=1", String.class);
    }
    private String attempt(TaskLease lease, String column) {
        return db.jdbc().queryForObject("SELECT " + column + " FROM async_task_attempt WHERE id=?", String.class, lease.attemptId());
    }
    private int count(String table) {
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
