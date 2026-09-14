package com.petplatform.task.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AsyncTaskWorkerMySqlTest {
    private final AtomicLong ids = new AtomicLong(100_000);

    private AsyncTaskWorker worker(MySqlTestDatabase db, Clock clock,
            BiFunction<TaskExecutionContext, String, TaskExecutionResult> action) {
        TaskHandler<String> handler = new TaskHandler<>() {
            public String taskType() { return "TEST"; }
            public TaskExecutionResult execute(TaskExecutionContext context, String payload) {
                return action.apply(context, payload);
            }
        };
        return new AsyncTaskWorker(db.repository(ids::incrementAndGet), "worker", clock,
                new TaskWorkerSettings(Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofMillis(20), 4),
                new TaskRetryDelays(Map.of("FAST_INTERNAL", List.of(Duration.ofMillis(60)))),
                List.of(new TaskRegistration<>(handler, TaskLease::payloadJson,
                        lease -> "TASK:TEST:" + lease.bizId() + ":0")));
    }

    @Test void handlerRunsOutsideTransactionAndHeartbeatKeepsLongExecutionOwned() throws Exception {
        try (var db = new MySqlTestDatabase(); var executor = Executors.newSingleThreadExecutor()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var worker = worker(db, Clock.systemUTC(), (context, payload) -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                entered.countDown();
                await(release);
                return new TaskExecutionResult.Success("OK");
            })) {
                var execution = executor.submit(worker::runOne);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    // A different connection can take a NOWAIT lock while Handler is blocked.
                    try (var connection = db.dataSource().getConnection()) {
                        connection.setAutoCommit(false);
                        try (var statement = connection.createStatement()) {
                            statement.executeQuery("SELECT id FROM async_task WHERE id=1 FOR UPDATE NOWAIT").close();
                        }
                        connection.rollback();
                    }
                    Thread.sleep(1400); // More than two original lease lifetimes.
                    assertTrue(db.repository(ids::incrementAndGet).claim("competitor", Duration.ofSeconds(1)).isEmpty());
                } finally { release.countDown(); }
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED, execution.get(5, TimeUnit.SECONDS));
                assertEquals("SUCCEEDED", state(db));
                assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task_attempt", Integer.class));
            }
        }
    }

    @Test void closeDoesNotClaimAgainOrTreatFutureCancellationAsBusinessInterruption() throws Exception {
        try (var db = new MySqlTestDatabase(); var executor = Executors.newSingleThreadExecutor()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var worker = worker(db, Clock.systemUTC(), (context, payload) -> {
                entered.countDown(); await(release); return new TaskExecutionResult.Success("LATE");
            })) {
                var execution = executor.submit(worker::runOne);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    worker.close();
                    assertEquals(AsyncTaskWorker.Outcome.ABANDONED, worker.runOne());
                    assertEquals("RUNNING", state(db));
                    Thread.sleep(750);
                    var replacement = db.repository(ids::incrementAndGet);
                    var lease = replacement.claim("worker", Duration.ofSeconds(2)).orElseThrow();
                    assertEquals(2, lease.attemptNo());
                    assertTrue(replacement.complete(lease, new TaskExecutionResult.Success("RECOVERED")));
                } finally { release.countDown(); }
                assertEquals(AsyncTaskWorker.Outcome.ABANDONED, execution.get(5, TimeUnit.SECONDS));
                assertEquals("RECOVERED", db.jdbc().queryForObject("SELECT last_result_code FROM async_task", String.class));
            }
        }
    }

    @Test void applicationClockCannotMakeTaskDueOrControlRetryDeadlineAndRequestIdStaysStable() throws Exception {
        try (var db = new MySqlTestDatabase()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            db.jdbc().update("UPDATE async_task SET execute_at=TIMESTAMPADD(DAY,1,NOW(3))");
            var clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
            var requests = new java.util.ArrayList<String>();
            var calls = new AtomicInteger();
            try (var worker = worker(db, clock, (context, payload) -> {
                assertEquals(clock.instant(), context.now().toInstant());
                requests.add(context.requestId());
                return calls.incrementAndGet() == 1 ? new TaskExecutionResult.Retry("TEMPORARY", Duration.ofMillis(500))
                        : new TaskExecutionResult.Success("OK");
            })) {
                assertEquals(AsyncTaskWorker.Outcome.EMPTY, worker.runOne());
                db.jdbc().update("UPDATE async_task SET execute_at=TIMESTAMPADD(SECOND,-1,NOW(3))");
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
                assertEquals("RETRY_WAIT", state(db));
                assertTrue(db.jdbc().queryForObject("SELECT execute_at BETWEEN NOW(3) AND TIMESTAMPADD(SECOND,1,NOW(3)) FROM async_task", Boolean.class));
                assertEquals(AsyncTaskWorker.Outcome.EMPTY, worker.runOne());
                Thread.sleep(600);
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
                assertEquals(List.of("TASK:TEST:1:0", "TASK:TEST:1:0"), requests);
            }
        }
    }

    @Test void runtimeFailureRetriesWithoutLeakingMessageAndFatalErrorLeavesRecoverableLease() throws Exception {
        try (var db = new MySqlTestDatabase()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            try (var worker = worker(db, Clock.systemUTC(), (context, payload) -> { throw new IllegalStateException("private payload"); })) {
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
                assertEquals("HANDLER_EXCEPTION", db.jdbc().queryForObject("SELECT last_error_code FROM async_task", String.class));
                assertNull(db.jdbc().queryForObject("SELECT last_error_message FROM async_task", String.class));
            }
            Thread.sleep(100);
            try (var worker = worker(db, Clock.systemUTC(), (context, payload) -> { throw new AssertionError("fatal fixture"); })) {
                assertThrows(AssertionError.class, worker::runOne);
            }
            Thread.sleep(750);
            var recovered = db.repository(ids::incrementAndGet).claim("replacement", Duration.ofSeconds(2)).orElseThrow();
            assertEquals(3, recovered.attemptNo());
        }
    }

    @Test void rejectsAmbientTransactionAndMissingProductionProvider() throws Exception {
        try (var db = new MySqlTestDatabase(); var worker = worker(db, Clock.systemUTC(), (c, p) -> new TaskExecutionResult.Success("OK"))) {
            assertThrows(NullPointerException.class, () -> new JdbcAsyncTaskRepository(db.dataSource(), null));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            transaction.executeWithoutResult(status -> assertThrows(IllegalStateException.class, worker::runOne));
        }
    }

    @Test void closeDuringPreparationPreventsFirstHandlerEntry() throws Exception {
        try (var db = new MySqlTestDatabase(); var executor = Executors.newSingleThreadExecutor()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TaskHandler<String> handler = new TaskHandler<>() {
                public String taskType() { return "TEST"; }
                public TaskExecutionResult execute(TaskExecutionContext c, String p) {
                    calls.incrementAndGet(); return new TaskExecutionResult.Success("OK");
                }
            };
            var registration = new TaskRegistration<>(handler, lease -> {
                decoding.countDown(); await(release); return lease.payloadJson();
            }, lease -> "TASK:TEST:1:0");
            try (var worker = new AsyncTaskWorker(db.repository(ids::incrementAndGet), "worker", Clock.systemUTC(),
                    TaskWorkerSettings.defaults(), new TaskRetryDelays(Map.of("FAST_INTERNAL", List.of(Duration.ofSeconds(5)))),
                    List.of(registration))) {
                var execution = executor.submit(worker::runOne);
                try {
                    assertTrue(decoding.await(5, TimeUnit.SECONDS));
                    worker.close();
                } finally { release.countDown(); }
                assertEquals(AsyncTaskWorker.Outcome.ABANDONED, execution.get(5, TimeUnit.SECONDS));
                assertEquals(0, calls.get());
                assertEquals("RUNNING", state(db));
            }
        }
    }

    @Test void nonfatalErrorDoesNotPermanentlyStopScheduledPolling() throws Exception {
        try (var db = new MySqlTestDatabase()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            db.seed(2, "TEST:2:0", "TEST", 2);
            AtomicInteger calls = new AtomicInteger();
            try (var worker = worker(db, Clock.systemUTC(), (c, p) -> {
                if (calls.incrementAndGet() == 1) throw new AssertionError("nonfatal fixture");
                return new TaskExecutionResult.Success("OK");
            })) {
                worker.start();
                long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task WHERE status='SUCCEEDED'", Integer.class) < 2
                        && System.nanoTime() < end) Thread.sleep(20);
                assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task WHERE status='SUCCEEDED'", Integer.class));
                assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM async_task_attempt", Integer.class));
            }
        }
    }

    @Test void oldHandlerResultIsFencedAfterHeartbeatLosesOwnership() throws Exception {
        try (var db = new MySqlTestDatabase(); var executor = Executors.newSingleThreadExecutor()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var worker = worker(db, Clock.systemUTC(), (c, p) -> {
                entered.countDown(); await(release); return new TaskExecutionResult.Success("OLD");
            })) {
                var execution = executor.submit(worker::runOne);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    db.jdbc().update("UPDATE async_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3))");
                    var next = db.repository(ids::incrementAndGet);
                    var lease = next.claim("worker", Duration.ofSeconds(2)).orElseThrow();
                    assertTrue(next.complete(lease, new TaskExecutionResult.Success("NEW")));
                    Thread.sleep(200); // Let the old heartbeat observe the lost token.
                } finally { release.countDown(); }
                assertEquals(AsyncTaskWorker.Outcome.LEASE_LOST, execution.get(5, TimeUnit.SECONDS));
                assertEquals("NEW", db.jdbc().queryForObject("SELECT last_result_code FROM async_task", String.class));
                assertEquals(List.of("RETRY", "SUCCESS"), db.jdbc().queryForList("SELECT result FROM async_task_attempt ORDER BY attempt_no", String.class));
            }
        }
    }

    @Test void scheduledPollerWakesAndStops() throws Exception {
        try (var db = new MySqlTestDatabase(); var worker = worker(db, Clock.systemUTC(), (c, p) -> new TaskExecutionResult.Success("OK"))) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            worker.start();
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!"SUCCEEDED".equals(state(db)) && System.nanoTime() < end) Thread.sleep(20);
            assertEquals("SUCCEEDED", state(db));
            worker.close();
            assertThrows(IllegalStateException.class, worker::start);
        }
    }

    @Test void actualJvmHaltAfterCommitAndNewProcessRecoversExpiredClaim() throws Exception {
        try (var db = new MySqlTestDatabase()) {
            db.seed(1, "TEST:1:0", "TEST", 2);
            String url = System.getenv().getOrDefault("PLAT004_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/")
                    + db.jdbc().queryForObject("SELECT DATABASE()", String.class);
            var crash = probe("crash", url, "300000");
            assertEquals(17, crash.exitCode());
            assertTrue(crash.output().contains("CLAIM_COMMITTED 1 1"), crash.output());
            assertEquals("RUNNING", state(db));
            var recovery = probe("recover", url, "400000");
            assertEquals(0, recovery.exitCode(), recovery.output());
            assertTrue(recovery.output().contains("RECOVERED"), recovery.output());
            assertTrue(recovery.output().contains("REQUEST TASK:TEST:1:0"), recovery.output());
            assertEquals("SUCCEEDED", state(db));
            assertEquals(List.of("RETRY", "SUCCESS"), db.jdbc().queryForList("SELECT result FROM async_task_attempt ORDER BY attempt_no", String.class));
            System.out.println("W2-TASK-002 actual JVM halt exit=17; restart exit=0; attempt RETRY/SUCCESS");
        }
    }

    private static ProbeResult probe(String mode, String url, String firstId) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, WorkerProcessProbe.class.getName(), mode, url, firstId)
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Forked worker timed out");
            return new ProbeResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private record ProbeResult(int exitCode, String output) {}
    private static String state(MySqlTestDatabase db) { return db.jdbc().queryForObject("SELECT status FROM async_task WHERE id=1", String.class); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
