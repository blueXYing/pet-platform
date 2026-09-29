package com.petplatform.order.biz.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.ApiException;
import com.petplatform.task.core.*;
import java.time.*;
import java.util.Objects;
import javax.sql.DataSource;

/** Validates both the immutable submission and the claimed lease before executing. */
public final class OrderAutoConfirmTaskRegistration {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private OrderAutoConfirmTaskRegistration() {}
    public record Payload(String orderId, OffsetDateTime deadline) {}
    public static TaskRegistration<Payload> create(DataSource source, OrderAutoConfirmService service) {
        var inspector = new TaskSubmissionInspector(source);
        var clock = new TaskDatabaseClock(source);
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return OrderAutoConfirmTaskSpec.TYPE; }
            @Override public TaskExecutionResult execute(TaskExecutionContext task, Payload payload) {
                try {
                    var result = service.autoConfirm(OrderAutoConfirmService.command(
                            task.traceId(), payload.orderId(), payload.deadline()));
                    return switch (result) {
                        case CONFIRMED, ALREADY_CONFIRMED -> new TaskExecutionResult.Success(result.name());
                        case STALE, BLOCKED_BY_REFUND -> new TaskExecutionResult.Cancelled(result.name());
                        case NOT_DUE -> {
                            Duration remaining = Duration.between(clock.now().atOffset(ZoneOffset.UTC), payload.deadline());
                            yield new TaskExecutionResult.Retry("NOT_DUE",
                                    remaining.isNegative() ? Duration.ofSeconds(1) : remaining.plusSeconds(1));
                        }
                    };
                } catch (ApiException retry) {
                    return new TaskExecutionResult.Retry(retry.code(), Duration.ofSeconds(30));
                }
            }
        }, lease -> {
            String orderId = Long.toString(lease.bizId());
            try {
                var snapshot = inspector.find(lease.taskKey());
                var node = JSON.readTree(lease.payloadJson());
                var deadline = OffsetDateTime.parse(node.path("expectedConfirmDeadline").textValue());
                if (!OrderAutoConfirmTaskSpec.matches(snapshot, orderId, deadline)
                        || !Long.toString(lease.taskId()).equals(snapshot.taskId())
                        || !OrderAutoConfirmTaskSpec.TYPE.equals(lease.taskType())
                        || !OrderAutoConfirmTaskSpec.key(orderId).equals(lease.taskKey())
                        || !Objects.equals(lease.expectedVersion(), snapshot.expectedVersion())
                        || lease.maxRetryCount() != snapshot.maxRetryCount()
                        || !Objects.equals(lease.retryPolicy(), snapshot.retryPolicy())
                        || !node.equals(JSON.readTree(snapshot.payloadJson())))
                    throw new IllegalArgumentException();
                return new Payload(orderId, deadline);
            } catch (Exception invalid) {
                service.recordAnomaly(orderId,"AUTO_CONFIRM_DECODE","TASK_BINDING_CONFLICT");
                throw new IllegalArgumentException("Invalid auto-confirm task binding");
            }
        }, lease -> "TASK:" + lease.taskKey());
    }
}
