package com.petplatform.payment.biz.settlement;

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
 * Durable tasks of merchant acquiring onboarding (CCR-PAY-ONBOARD-001 §6 / contract 81 §6):
 * <ul>
 *   <li>{@code MERCHANT_ONBOARDING_ADVANCE} runs the next channel submission outside any request or
 *       callback thread; 10s / 30s / 2m / 10m, at most 6 attempts.</li>
 *   <li>{@code MERCHANT_ONBOARDING_POLL} queries the channel for the current waiting step when the
 *       callback does not arrive; 1m / 5m / 15m / 1h / 6h, at most 20 attempts (about five days).</li>
 * </ul>
 * Keys carry the attempt token so every new waiting period gets its own task row.
 */
public final class MerchantOnboardingTasks {
    public static final String ADVANCE_TYPE = "MERCHANT_ONBOARDING_ADVANCE";
    public static final String ADVANCE_POLICY = "MERCHANT_ONBOARDING_ADVANCE";
    public static final String POLL_TYPE = "MERCHANT_ONBOARDING_POLL";
    public static final String POLL_POLICY = "MERCHANT_ONBOARDING_POLL";
    public static final String OWNER_MODULE = "PAYMENT";
    public static final String BIZ_TYPE = "PAYMENT_MERCHANT_ONBOARDING";
    public static final int ADVANCE_MAX_RETRY = 6;
    public static final int POLL_MAX_RETRY = 20;
    public static final List<Duration> ADVANCE_DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    public static final List<Duration> POLL_DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));
    public static final String EXHAUSTED = "MERCHANT_ONBOARDING_EXHAUSTED";
    public static final String STALE = "MERCHANT_ONBOARDING_STALE";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private MerchantOnboardingTasks() {}

    public enum Outcome { DONE, PENDING, EXHAUSTED }

    /** One execution for one merchant; implementations must not throw for business outcomes. */
    @FunctionalInterface
    public interface Step {
        Outcome execute(long merchantId);
    }

    public record Payload(long merchantId, int retryCount) {}

    public static String advanceKey(long merchantId, String token) {
        return key(ADVANCE_TYPE, merchantId, token);
    }

    public static String pollKey(long merchantId, String token) {
        return key(POLL_TYPE, merchantId, token);
    }

    public static String payload(long merchantId) {
        if (merchantId <= 0) throw new IllegalArgumentException("invalid onboarding task payload inputs");
        return "{\"merchantId\":\"" + IDS.toApi(merchantId) + "\"}";
    }

    public static TaskExecutionResult advanceResult(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("MERCHANT_ONBOARDING_ADVANCED");
            case PENDING -> retryCount >= ADVANCE_MAX_RETRY - 1
                    ? new TaskExecutionResult.Dead(EXHAUSTED)
                    : new TaskExecutionResult.Retry("MERCHANT_ONBOARDING_RETRY",
                            ADVANCE_DELAYS.get(Math.min(retryCount, ADVANCE_DELAYS.size() - 1)));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
        };
    }

    public static TaskExecutionResult pollResult(Outcome outcome, int retryCount) {
        return switch (Objects.requireNonNull(outcome)) {
            case DONE -> new TaskExecutionResult.Success("MERCHANT_ONBOARDING_SETTLED");
            case PENDING -> retryCount >= POLL_MAX_RETRY - 1
                    ? new TaskExecutionResult.Dead(EXHAUSTED)
                    : new TaskExecutionResult.Retry("MERCHANT_ONBOARDING_PENDING",
                            POLL_DELAYS.get(Math.min(retryCount, POLL_DELAYS.size() - 1)));
            case EXHAUSTED -> new TaskExecutionResult.Dead(EXHAUSTED);
        };
    }

    public static TaskRegistration<Payload> advanceRegistration(Step step) {
        return registration(ADVANCE_TYPE, ADVANCE_POLICY, step, MerchantOnboardingTasks::advanceResult);
    }

    public static TaskRegistration<Payload> pollRegistration(Step step) {
        return registration(POLL_TYPE, POLL_POLICY, step, MerchantOnboardingTasks::pollResult);
    }

    private static TaskRegistration<Payload> registration(String type, String policy, Step step,
            java.util.function.BiFunction<Outcome, Integer, TaskExecutionResult> mapping) {
        Objects.requireNonNull(step, "step");
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override
            public String taskType() {
                return type;
            }

            @Override
            public TaskExecutionResult execute(TaskExecutionContext context, Payload payload) {
                return mapping.apply(step.execute(payload.merchantId()), payload.retryCount());
            }
        }, lease -> decode(lease, type, policy), lease -> "TASK:" + lease.taskKey());
    }

    static Payload decode(TaskLease lease, String type, String policy) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("merchantId")) || !node.path("merchantId").isTextual()) {
                throw new IllegalArgumentException();
            }
            long merchantId = IDS.fromApi(node.path("merchantId").textValue());
            if (lease.bizId() != merchantId
                    || lease.expectedVersion() != null
                    || !type.equals(lease.taskType())
                    || !policy.equals(lease.retryPolicy())
                    || lease.retryCount() < 0) {
                throw new IllegalArgumentException();
            }
            return new Payload(merchantId, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid merchant onboarding task payload");
        }
    }

    private static String key(String type, long merchantId, String token) {
        if (merchantId <= 0 || token == null || token.isBlank() || token.length() > 48
                || token.indexOf('"') >= 0 || token.indexOf('\\') >= 0 || token.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid onboarding task key inputs");
        }
        return type + ":" + IDS.toApi(merchantId) + ":" + token;
    }
}
