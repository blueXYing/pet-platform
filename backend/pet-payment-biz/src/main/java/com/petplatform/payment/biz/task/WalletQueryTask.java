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
 * Durable {@code WALLET_WITHDRAW_QUERY:{withdrawalId}} task polling Lakala 1271
 * ({@code /api/v2/laep/industry/ewalletWithdrawQuery}) after 1319 D1 withdrawal submission.
 * Backoff schedule: 1m / 5m / 15m / 1h / 6h.
 */
public final class WalletQueryTask {
    public static final String TASK_TYPE = "WALLET_WITHDRAW_QUERY";
    public static final String RETRY_POLICY = "WALLET_WITHDRAW_QUERY";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "WITHDRAWAL";
    public static final int MAX_RETRY_COUNT = 20;
    public static final Duration FIRST_DELAY = Duration.ofMinutes(1);
    public static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));
    public static final String EXHAUSTED = "WALLET_WITHDRAW_QUERY_EXHAUSTED";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private WalletQueryTask() {}

    public enum Outcome { DONE, PENDING, EXHAUSTED, NOT_FOUND }

    @FunctionalInterface
    public interface Step {
        Outcome execute(long withdrawalId);
    }

    public record Payload(long withdrawalId, int retryCount) {}

    public static String taskKey(long withdrawalId) {
        if (withdrawalId <= 0) throw new IllegalArgumentException("withdrawalId must be positive");
        return TASK_TYPE + ":" + IDS.toApi(withdrawalId);
    }

    public static String payload(long withdrawalId) {
        if (withdrawalId <= 0) throw new IllegalArgumentException("withdrawalId must be positive");
        return "{\"withdrawalId\":\"" + IDS.toApi(withdrawalId) + "\"}";
    }

    public static Duration delay(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("retry count must not be negative");
        return DELAYS.get(Math.min(retryCount, DELAYS.size() - 1));
    }

    public static TaskExecutionResult map(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("WITHDRAWAL_TERMINAL");
            case PENDING -> retryCount >= MAX_RETRY_COUNT - 1
                    ? new TaskExecutionResult.Dead(EXHAUSTED)
                    : new TaskExecutionResult.Retry("WITHDRAWAL_PENDING", delay(retryCount));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
            case NOT_FOUND -> new TaskExecutionResult.Cancelled("WITHDRAWAL_NOT_FOUND");
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
                return map(step.execute(payload.withdrawalId()), payload.retryCount());
            }
        }, WalletQueryTask::decode, lease -> "TASK:" + lease.taskKey());
    }

    public static Payload decode(TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("withdrawalId")) || !node.path("withdrawalId").isTextual()) {
                throw new IllegalArgumentException();
            }
            long withdrawalId = IDS.fromApi(node.path("withdrawalId").textValue());
            if (lease.bizId() != withdrawalId
                    || lease.expectedVersion() != null
                    || !TASK_TYPE.equals(lease.taskType())
                    || !RETRY_POLICY.equals(lease.retryPolicy())
                    || !taskKey(withdrawalId).equals(lease.taskKey())
                    || lease.retryCount() < 0) {
                throw new IllegalArgumentException();
            }
            return new Payload(withdrawalId, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid wallet withdrawal query task payload");
        }
    }
}
