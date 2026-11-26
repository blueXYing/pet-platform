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
 * Durable {@code RECEIVER_BIND_QUERY:{merchantId}:{applyId}} task polling Lakala 1872
 * ({@code /api/v2/mms/openApi/ledger/openWeb/listRelationApply}) after 1290 async bind submission.
 * Backoff schedule: 1m / 5m / 15m / 1h / 6h.
 */
public final class ReceiverBindQueryTask {
    public static final String TASK_TYPE = "RECEIVER_BIND_QUERY";
    public static final String RETRY_POLICY = "RECEIVER_BIND_QUERY";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "SETTLEMENT_RECEIVER";
    public static final int MAX_RETRY_COUNT = 20;
    public static final Duration FIRST_DELAY = Duration.ofMinutes(1);
    public static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));
    public static final String EXHAUSTED = "RECEIVER_BIND_QUERY_EXHAUSTED";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private ReceiverBindQueryTask() {}

    public enum Outcome { DONE, PENDING, EXHAUSTED, NOT_FOUND }

    @FunctionalInterface
    public interface Step {
        Outcome execute(long merchantId, String applyId);
    }

    public record Payload(long merchantId, String applyId, int retryCount) {}

    public static String taskKey(long merchantId, String applyId) {
        if (merchantId <= 0 || applyId == null || applyId.isBlank() || applyId.length() > 64) {
            throw new IllegalArgumentException("invalid receiver bind query task key inputs");
        }
        return TASK_TYPE + ":" + IDS.toApi(merchantId) + ":" + applyId;
    }

    public static String payload(long merchantId, String applyId) {
        if (merchantId <= 0 || applyId == null || applyId.isBlank() || applyId.length() > 64
                || applyId.indexOf('"') >= 0 || applyId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid receiver bind query payload inputs");
        }
        return "{\"merchantId\":\"" + IDS.toApi(merchantId) + "\",\"applyId\":\"" + applyId + "\"}";
    }

    public static Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retry count must not be negative");
        return DELAYS.get(Math.min(retryCount, DELAYS.size() - 1));
    }

    public static TaskExecutionResult map(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("RECEIVER_BIND_TERMINAL");
            case PENDING -> retryCount >= MAX_RETRY_COUNT - 1
                    ? new TaskExecutionResult.Dead(EXHAUSTED)
                    : new TaskExecutionResult.Retry("RECEIVER_BIND_PENDING", delay(retryCount));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
            case NOT_FOUND -> new TaskExecutionResult.Cancelled("RECEIVER_BIND_NOT_FOUND");
        };
    }

    public static TaskRegistration<Payload> registration(Step step) {
        Objects.requireNonNull(step, "step");
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override
            public String taskType() {
                return TASK_TYPE;
            }

            @Override
            public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
                return map(step.execute(payload.merchantId(), payload.applyId()), payload.retryCount());
            }
        }, ReceiverBindQueryTask::decode, lease -> "TASK:" + lease.taskKey());
    }

    public static Payload decode(TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("merchantId", "applyId"))
                    || !node.path("merchantId").isTextual()
                    || !node.path("applyId").isTextual()) {
                throw new IllegalArgumentException();
            }
            long merchantId = IDS.fromApi(node.path("merchantId").textValue());
            String applyId = node.path("applyId").textValue();
            if (applyId.isBlank() || applyId.length() > 64
                    || lease.bizId() != merchantId
                    || lease.expectedVersion() != null
                    || !TASK_TYPE.equals(lease.taskType())
                    || !RETRY_POLICY.equals(lease.retryPolicy())
                    || !taskKey(merchantId, applyId).equals(lease.taskKey())
                    || lease.retryCount() < 0) {
                throw new IllegalArgumentException();
            }
            return new Payload(merchantId, applyId, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid receiver bind query task payload");
        }
    }
}
