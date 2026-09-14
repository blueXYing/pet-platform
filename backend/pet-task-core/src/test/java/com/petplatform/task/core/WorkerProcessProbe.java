package com.petplatform.task.core;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Forked JVM fixture. IDs are explicit test sequences, never a production provider. */
public final class WorkerProcessProbe {
    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(args[1] + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                System.getenv().getOrDefault("PLAT004_MYSQL_USER", "root"),
                System.getenv().getOrDefault("PLAT004_MYSQL_PASSWORD", ""));
        var ids = new AtomicLong(Long.parseLong(args[2]));
        var repository = new JdbcAsyncTaskRepository(source, ids::incrementAndGet);
        if ("crash".equals(args[0])) {
            TaskLease claim = repository.claim("process-restart", Duration.ofMillis(250)).orElseThrow();
            System.out.println("CLAIM_COMMITTED " + claim.taskId() + " " + claim.version());
            System.out.flush();
            Runtime.getRuntime().halt(17); // Deliberately bypass finally/close/shutdown hooks.
        }
        TaskHandler<String> handler = new TaskHandler<>() {
            public String taskType() { return "TEST"; }
            public TaskExecutionResult execute(TaskExecutionContext context, String payload) {
                System.out.println("REQUEST " + context.requestId());
                return new TaskExecutionResult.Success("OK");
            }
        };
        try (var worker = new AsyncTaskWorker(repository, "process-restart", Clock.systemUTC(),
                new TaskWorkerSettings(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofMillis(20), 1),
                new TaskRetryDelays(Map.of("FAST_INTERNAL", List.of(Duration.ofMillis(50)))),
                List.of(new TaskRegistration<>(handler, TaskLease::payloadJson, lease -> "TASK:TEST:" + lease.bizId() + ":0")))) {
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < end) {
                if (worker.runOne() == AsyncTaskWorker.Outcome.COMPLETED) {
                    System.out.println("RECOVERED");
                    return;
                }
                Thread.sleep(20);
            }
            throw new IllegalStateException("No recovery before deadline");
        }
    }
}
