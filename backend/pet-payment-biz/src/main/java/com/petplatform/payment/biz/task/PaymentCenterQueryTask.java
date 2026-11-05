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
 * Contract 75 §5.3 / Scheduler 09 §6 §17 durable {@code PAYCENTER_CHANNEL_QUERY:{paymentId}} task for payment
 * center rows. Registered in the same transaction as MAY_HAVE_SENT; queries only the original paymentNo.
 */
public final class PaymentCenterQueryTask {
    public static final String TASK_TYPE = "PAYCENTER_CHANNEL_QUERY";
    public static final String RETRY_POLICY = "PAYCENTER_CHANNEL_QUERY";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "PAYMENT";
    public static final int MAX_RETRY_COUNT = 60;
    public static final Duration FIRST_DELAY = Duration.ofSeconds(5);
    /** 5s/15s/30s/1m/2m/5m, then 5m until a channel terminal state or expireAt + 5 minutes. */
    public static final List<Duration> DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5));
    public static final String EXHAUSTED = "PAYMENT_QUERY_EXHAUSTED";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private PaymentCenterQueryTask() {}

    public enum Outcome { DONE, PENDING, EXHAUSTED, NOT_CENTER, NOT_SENT, RECONCILIATION_REQUIRED }

    @FunctionalInterface public interface Step { Outcome execute(long paymentId); }

    public record Payload(long paymentId, int retryCount) {}

    public static String taskKey(long paymentId) { return TASK_TYPE + ":" + IDS.toApi(paymentId); }

    public static String payload(long paymentId) {
        return "{\"paymentId\":\"" + IDS.toApi(paymentId) + "\"}";
    }

    public static Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retry count must not be negative");
        return DELAYS.get(Math.min(retryCount, DELAYS.size() - 1));
    }

    /** Pure mapping, unit-tested offline. Pending never becomes a failure; exhaustion is DEAD for operations. */
    public static TaskExecutionResult map(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("PAYMENT_CHANNEL_TERMINAL");
            case PENDING -> new TaskExecutionResult.Retry("PAYMENT_QUERY_PENDING", delay(retryCount));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
            case RECONCILIATION_REQUIRED -> new TaskExecutionResult.Dead("PAYMENT_RECONCILIATION_REQUIRED");
            case NOT_CENTER -> new TaskExecutionResult.Cancelled("NOT_PAYMENT_CENTER");
            case NOT_SENT -> new TaskExecutionResult.Cancelled("PAYMENT_NOT_SENT");
        };
    }

    public static TaskRegistration<Payload> registration(Step step) {
        Objects.requireNonNull(step);
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return TASK_TYPE; }
            @Override public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
                return map(step.execute(payload.paymentId()), payload.retryCount());
            }
        }, PaymentCenterQueryTask::decode, lease -> "TASK:" + lease.taskKey());
    }

    /** Rejects any generation that is not exactly the one this module enqueues. */
    public static Payload decode(TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("paymentId")) || !node.path("paymentId").isTextual())
                throw new IllegalArgumentException();
            long paymentId = IDS.fromApi(node.path("paymentId").textValue());
            if (lease.bizId() != paymentId || lease.expectedVersion() != null
                    || !TASK_TYPE.equals(lease.taskType()) || !RETRY_POLICY.equals(lease.retryPolicy())
                    || !taskKey(paymentId).equals(lease.taskKey()) || lease.retryCount() < 0)
                throw new IllegalArgumentException();
            return new Payload(paymentId, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid payment center query task generation");
        }
    }
}
