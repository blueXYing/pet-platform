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
 * Contract 78 §6.1 durable {@code SETTLEMENT_DUE:{settlementOrderId}:{round}} task.
 * Enqueued in the same transaction as verification T+d snapshotting (or resume/retry).
 */
public final class SettlementDueTask {
    public static final String TASK_TYPE = "SETTLEMENT_DUE";
    public static final String RETRY_POLICY = "SETTLEMENT_DUE";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "SETTLEMENT";
    public static final int MAX_RETRY_COUNT = 20;
    public static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1));

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private SettlementDueTask() {}

    public sealed interface Outcome permits Outcome.Done, Outcome.Held, Outcome.Canceled,
            Outcome.Reschedule, Outcome.Retry, Outcome.Dead {
        record Done() implements Outcome {}
        record Held(String holdReason) implements Outcome {}
        record Canceled(String reason) implements Outcome {}
        record Reschedule(Duration delay) implements Outcome {}
        record Retry(String reason) implements Outcome {}
        record Dead(String reason) implements Outcome {}
    }

    @FunctionalInterface
    public interface Step {
        Outcome execute(long settlementOrderId, int round);
    }

    public record Payload(long settlementOrderId, int round, int retryCount) {}

    public static String taskKey(long settlementOrderId, int round) {
        if (round <= 0) throw new IllegalArgumentException("round must be positive");
        return TASK_TYPE + ":" + IDS.toApi(settlementOrderId) + ":" + round;
    }

    public static String payload(long settlementOrderId, int round) {
        if (round <= 0) throw new IllegalArgumentException("round must be positive");
        return "{\"settlementOrderId\":\"" + IDS.toApi(settlementOrderId) + "\",\"round\":" + round + "}";
    }

    public static Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retry count must not be negative");
        return DELAYS.get(Math.min(retryCount, DELAYS.size() - 1));
    }

    public static TaskExecutionResult map(Outcome outcome, int retryCount) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case Outcome.Done d -> new TaskExecutionResult.Success("SETTLEMENT_DUE_PROCESSED");
            case Outcome.Held h -> new TaskExecutionResult.Success("SETTLEMENT_HELD:" + h.holdReason());
            case Outcome.Canceled c -> new TaskExecutionResult.Cancelled(c.reason());
            case Outcome.Reschedule r -> new TaskExecutionResult.Retry("SETTLEMENT_NOT_YET_DUE", r.delay());
            case Outcome.Retry r -> retryCount >= MAX_RETRY_COUNT - 1
                    ? new TaskExecutionResult.Dead("SETTLEMENT_DUE_EXHAUSTED")
                    : new TaskExecutionResult.Retry(r.reason(), delay(retryCount));
            case Outcome.Dead d -> new TaskExecutionResult.Dead(d.reason());
        };
    }

    public static TaskRegistration<Payload> registration(Step step) {
        Objects.requireNonNull(step, "step");
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return TASK_TYPE; }
            @Override public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
                return map(step.execute(payload.settlementOrderId(), payload.round()), payload.retryCount());
            }
        }, SettlementDueTask::decode, lease -> "TASK:" + lease.taskKey());
    }

    public static Payload decode(TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("settlementOrderId", "round"))
                    || !node.path("settlementOrderId").isTextual()
                    || !node.path("round").isIntegralNumber()
                    || !node.path("round").canConvertToInt()) {
                throw new IllegalArgumentException();
            }
            long id = IDS.fromApi(node.path("settlementOrderId").textValue());
            int round = node.path("round").intValue();
            if (round <= 0 || lease.bizId() != id
                    || !Objects.equals(lease.expectedVersion(), (long) round)
                    || !TASK_TYPE.equals(lease.taskType())
                    || !RETRY_POLICY.equals(lease.retryPolicy())
                    || !taskKey(id, round).equals(lease.taskKey())
                    || lease.retryCount() < 0) {
                throw new IllegalArgumentException();
            }
            return new Payload(id, round, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid settlement due task generation");
        }
    }
}
