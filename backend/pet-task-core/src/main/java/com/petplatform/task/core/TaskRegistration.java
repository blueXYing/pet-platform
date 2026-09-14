package com.petplatform.task.core;

import com.petplatform.common.PublicContractChecks;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Business owners provide payload decoding and the Scheduler §37 generation mapping.
 * The request ID resolver must be deterministic across attempts, workers and restarts.
 * There is deliberately no default generation, business handler, or payload convention.
 */
public record TaskRegistration<T>(TaskHandler<T> handler, Function<TaskLease, T> decode,
                                  Function<TaskLease, String> requestId) {
    public TaskRegistration {
        Objects.requireNonNull(handler);
        Objects.requireNonNull(decode);
        Objects.requireNonNull(requestId);
        if (handler.taskType() == null || handler.taskType().isBlank()) {
            throw new IllegalArgumentException("A task type is required");
        }
    }

    Supplier<TaskExecutionResult> prepare(TaskLease lease, Clock clock) {
        String stableRequestId = requestId.apply(lease);
        PublicContractChecks.requireRequestId(stableRequestId);
        var context = new TaskExecutionContext(Long.toString(lease.taskId()), stableRequestId,
                "TASK:" + lease.taskId() + ":" + lease.attemptNo(),
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS));
        T payload = decode.apply(lease);
        return () -> Objects.requireNonNull(handler.execute(context, payload), "Handler returned null");
    }
}
