package com.petplatform.user.biz.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.task.core.TaskDatabaseClock;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskSubmissionInspector;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Durable worker binding for the contract-66 v0.2 cooling-period expiry (SQL13 task family,
 * ORDER_AUTO_CONFIRM shape): the handler validates both the immutable submission and the
 * claimed lease before delegating to the deletion kernel's row-locked
 * {@link AccountDeletionService#executeDueDeletion}. Due-task verdicts map to the task
 * family's result words: EXECUTED succeeds; the auto-cancel and already-resolved outcomes
 * cancel the task (no retry — the request row is the authoritative state); unexpected
 * technical failures retry on the configured policy.
 */
public final class AccountDeletionTaskRegistration {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AccountDeletionTaskRegistration() {}

    public record Payload(long requestRowId, long userId, LocalDateTime effectiveAt) {}

    public static TaskRegistration<Payload> create(DataSource source,
            AccountDeletionService service) {
        var inspector = new TaskSubmissionInspector(source);
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override
            public String taskType() {
                return AccountDeletionTaskSpec.TYPE;
            }

            @Override
            public TaskExecutionResult execute(TaskExecutionContext task, Payload payload) {
                try {
                    AccountDeletionService.DueExecution result =
                            service.executeDueDeletion(payload.requestRowId());
                    return switch (result) {
                        case EXECUTED -> new TaskExecutionResult.Success(result.name());
                        case ALREADY_RESOLVED, AUTO_CANCELED_BLOCKED, AUTO_CANCELED_FROZEN ->
                                new TaskExecutionResult.Cancelled(result.name());
                    };
                } catch (ApiException retryable) {
                    return new TaskExecutionResult.Retry(retryable.code(), Duration.ofSeconds(30));
                } catch (RuntimeException unavailable) {
                    return new TaskExecutionResult.Retry("DELETION_EXECUTION_UNAVAILABLE",
                            Duration.ofSeconds(30));
                }
            }
        }, lease -> {
            try {
                var snapshot = inspector.find(lease.taskKey());
                JsonNode node = JSON.readTree(lease.payloadJson());
                Payload payload = decode(node);
                if (!AccountDeletionTaskSpec.matches(snapshot, payload.requestRowId(),
                        payload.userId(), payload.effectiveAt())
                        || !Long.toString(lease.taskId()).equals(snapshot.taskId())
                        || !AccountDeletionTaskSpec.TYPE.equals(lease.taskType())
                        || !AccountDeletionTaskSpec.key(payload.requestRowId()).equals(lease.taskKey())
                        || !Objects.equals(lease.expectedVersion(), snapshot.expectedVersion())
                        || lease.maxRetryCount() != snapshot.maxRetryCount()
                        || !Objects.equals(lease.retryPolicy(), snapshot.retryPolicy())
                        || !node.equals(JSON.readTree(snapshot.payloadJson()))) {
                    throw new IllegalArgumentException();
                }
                return payload;
            } catch (Exception invalid) {
                throw new IllegalArgumentException("Invalid account-deletion task binding");
            }
        }, lease -> "TASK:" + lease.taskKey());
    }

    private static Payload decode(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Payload shape");
        long rowId = Long.parseUnsignedLong(node.path("requestRowId").textValue());
        long userId = Long.parseUnsignedLong(node.path("userId").textValue());
        LocalDateTime effectiveAt = OffsetDateTime.parse(node.path("effectiveAt").textValue())
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new Payload(rowId, userId, effectiveAt);
    }
}
