package com.petplatform.event.core;

import java.time.Duration;

/** Technical settings, never business deadlines. */
public record OutboxDispatchSettings(Duration lease, Duration heartbeat, Duration pollInterval, int batchSize) {
    public OutboxDispatchSettings {
        long leaseMillis = JdbcOutboxRepository.positiveMillis(lease);
        long heartbeatMillis = JdbcOutboxRepository.positiveMillis(heartbeat);
        JdbcOutboxRepository.positiveMillis(pollInterval);
        if (heartbeatMillis >= leaseMillis || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("heartbeat must be shorter than lease; batch size must be 1..1000");
        }
    }

    public static OutboxDispatchSettings defaults() {
        return new OutboxDispatchSettings(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(1), 100);
    }
}
