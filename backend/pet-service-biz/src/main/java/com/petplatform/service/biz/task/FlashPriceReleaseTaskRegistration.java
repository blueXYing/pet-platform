package com.petplatform.service.biz.task;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.service.biz.application.FlashPriceReleaseService;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/**
 * Durable-task registration for the flash-price release scanner (77 §6.3, owner=SERVICE). The
 * decode validates the deterministic binding — task type, the per-minute task key, the slot
 * payload and its canonical JSON must line up — before the round runs; a damaged binding is an
 * invalid submission, not a business failure.
 */
public final class FlashPriceReleaseTaskRegistration {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FlashPriceReleaseTaskRegistration() {}

    public record Payload(OffsetDateTime slot) {}

    public static TaskRegistration<Payload> create(FlashPriceReleaseService service) {
        Objects.requireNonNull(service, "service is required");
        return new TaskRegistration<>(
                new TaskHandler<>() {
                    @Override
                    public String taskType() {
                        return FlashPriceReleaseService.TASK_TYPE;
                    }

                    @Override
                    public TaskExecutionResult execute(TaskExecutionContext task, Payload payload) {
                        int released = service.runRound();
                        return new TaskExecutionResult.Success(
                                released > 0 ? "RELEASED_" + released : "IDLE");
                    }
                },
                FlashPriceReleaseTaskRegistration::decode,
                lease -> "TASK:" + lease.taskKey());
    }

    private static Payload decode(TaskLease lease) {
        try {
            if (!FlashPriceReleaseService.TASK_TYPE.equals(lease.taskType())) {
                throw new IllegalArgumentException("task type mismatch");
            }
            JsonNode root = JSON.readTree(lease.payloadJson());
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.get("slot").isTextual()) {
                throw new IllegalArgumentException("payload shape mismatch");
            }
            OffsetDateTime slot = OffsetDateTime.parse(root.get("slot").asText())
                    .withOffsetSameInstant(ZoneOffset.UTC);
            if (slot.getNano() != 0 || slot.getSecond() != 0) {
                throw new IllegalArgumentException("slot must be a whole minute");
            }
            if (!FlashPriceReleaseService.taskKey(slot).equals(lease.taskKey())) {
                throw new IllegalArgumentException("task key does not match the slot");
            }
            if (!FlashPriceReleaseService.payloadJson(slot).equals(lease.payloadJson())) {
                throw new IllegalArgumentException("payload is not canonical");
            }
            return new Payload(slot);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid flash-price release task binding");
        }
    }
}
