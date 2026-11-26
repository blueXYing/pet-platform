package com.petplatform.payment.biz.task;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Contract 78 §6.1 / CCR §6.1 durable {@code SETTLEMENT_CHANNEL_QUERY:{instructionId}} task.
 * Enqueued in the same transaction as {@code settlement_instruction(SENT)} with 15s initial delay
 * (per Lakala 1293 recommendation) and backoff 15s / 1m / 5m / 15m / 1h / 6h.
 */
public final class SettlementQueryTask {
    public static final String TASK_TYPE = "SETTLEMENT_CHANNEL_QUERY";
    public static final String RETRY_POLICY = "SETTLEMENT_CHANNEL_QUERY";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "SETTLEMENT";
    public static final int MAX_RETRY_COUNT = 20;
    public static final Duration FIRST_DELAY = Duration.ofSeconds(15);
    /** 15s / 1m / 5m / 15m / 1h / 6h (CCR-PAY-CENTER-001 §6.1 & §13.1). */
    public static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(15), Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));
    public static final String EXHAUSTED = "SETTLEMENT_QUERY_EXHAUSTED";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private SettlementQueryTask() {}

    public enum Outcome { DONE, PENDING, EXHAUSTED, NOT_FOUND }

    @FunctionalInterface
    public interface Step {
        Outcome execute(long instructionId);
    }

    public record Payload(long instructionId, int retryCount) {}

    public static String taskKey(long instructionId) {
        return TASK_TYPE + ":" + IDS.toApi(instructionId);
    }

    public static String payload(long instructionId) {
        return "{\"instructionId\":\"" + IDS.toApi(instructionId) + "\"}";
    }

    public static Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retry count must not be negative");
        return DELAYS.get(Math.min(retryCount, DELAYS.size() - 1));
    }

    public static TaskExecutionResult map(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("SETTLEMENT_CHANNEL_TERMINAL");
            case PENDING -> retryCount >= MAX_RETRY_COUNT - 1
                    ? new TaskExecutionResult.Dead(EXHAUSTED)
                    : new TaskExecutionResult.Retry("SETTLEMENT_QUERY_PENDING", delay(retryCount));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
            case NOT_FOUND -> new TaskExecutionResult.Cancelled("SETTLEMENT_INSTRUCTION_NOT_FOUND");
        };
    }

    public static TaskRegistration<Payload> registration(Step step) {
        Objects.requireNonNull(step, "step");
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return TASK_TYPE; }
            @Override public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
                return map(step.execute(payload.instructionId()), payload.retryCount());
            }
        }, SettlementQueryTask::decode, lease -> "TASK:" + lease.taskKey());
    }

    public static Payload decode(TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("instructionId")) || !node.path("instructionId").isTextual()) {
                throw new IllegalArgumentException();
            }
            long id = IDS.fromApi(node.path("instructionId").textValue());
            if (lease.bizId() != id || lease.expectedVersion() != null
                    || !TASK_TYPE.equals(lease.taskType())
                    || !RETRY_POLICY.equals(lease.retryPolicy())
                    || !taskKey(id).equals(lease.taskKey())
                    || lease.retryCount() < 0) {
                throw new IllegalArgumentException();
            }
            return new Payload(id, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid settlement query task generation");
        }
    }
}
