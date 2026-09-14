package com.petplatform.event.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Explicit backoff schedule for dispatch failures; the last entry caps the tail. */
public final class OutboxRetryDelays {
    private final List<Duration> delays;

    public OutboxRetryDelays(List<Duration> delays) {
        Objects.requireNonNull(delays);
        this.delays = List.copyOf(delays);
        if (this.delays.isEmpty()) throw new IllegalArgumentException("Empty retry schedule");
        this.delays.forEach(JdbcOutboxRepository::positiveMillis);
    }

    public Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retryCount must be >= 0");
        return delays.get(Math.min(retryCount, delays.size() - 1));
    }
}
