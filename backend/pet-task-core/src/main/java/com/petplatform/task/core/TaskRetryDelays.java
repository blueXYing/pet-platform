package com.petplatform.task.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit configured policy for unexpected technical handler failures.
 * Handlers retain responsibility for UNKNOWN query/compensation paths.
 */
public final class TaskRetryDelays {
    private final Map<String, List<Duration>> policies;

    public TaskRetryDelays(Map<String, List<Duration>> policies) {
        Objects.requireNonNull(policies);
        this.policies = policies.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> {
                    List<Duration> values = List.copyOf(entry.getValue());
                    if (values.isEmpty()) throw new IllegalArgumentException("Empty retry policy");
                    values.forEach(JdbcAsyncTaskRepository::positiveMillis);
                    return values;
                }));
    }

    public Duration delay(String policy, int retryCount) {
        List<Duration> values = policies.get(policy);
        if (values == null || retryCount < 0) throw new IllegalArgumentException("Unconfigured retry policy");
        return values.get(Math.min(retryCount, values.size() - 1));
    }
}
