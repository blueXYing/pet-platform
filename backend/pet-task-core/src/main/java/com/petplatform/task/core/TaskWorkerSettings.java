package com.petplatform.task.core;

import java.time.Duration;

/** Technical settings, never business deadlines. */
public record TaskWorkerSettings(Duration lease, Duration heartbeat, Duration pollInterval, int batchSize) {
    public TaskWorkerSettings {
        long leaseMillis = JdbcAsyncTaskRepository.positiveMillis(lease);
        long heartbeatMillis = JdbcAsyncTaskRepository.positiveMillis(heartbeat);
        JdbcAsyncTaskRepository.positiveMillis(pollInterval);
        if (heartbeatMillis >= leaseMillis || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("heartbeat must be shorter than lease; batch size must be 1..1000");
        }
    }

    public static TaskWorkerSettings defaults() {
        return new TaskWorkerSettings(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(1), 100);
    }
}
