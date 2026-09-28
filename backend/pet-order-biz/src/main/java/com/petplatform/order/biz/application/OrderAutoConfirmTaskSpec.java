package com.petplatform.order.biz.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.task.core.TaskSubmissionSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The approved B submission format. It does not implement an automatic confirmation command. */
public final class OrderAutoConfirmTaskSpec {
    public static final String TYPE = "ORDER_AUTO_CONFIRM";
    public static final int MAX_RETRIES = 8;
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private OrderAutoConfirmTaskSpec() {}

    public static String key(String orderId) {
        IDS.fromApi(orderId);
        return TYPE + ":" + orderId + ":0";
    }

    public static String payload(String orderId, OffsetDateTime deadline) {
        IDS.fromApi(orderId);
        PublicContractChecks.requireMillisecondPrecision(deadline);
        try {
            return JSON.writeValueAsString(Map.of("orderId", orderId, "expectedConfirmRound", 0,
                    "expectedConfirmDeadline", deadline.withOffsetSameInstant(ZoneOffset.UTC).toString()));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid auto-confirm task payload", invalid);
        }
    }

    public static boolean matches(TaskSubmissionSnapshot task, String orderId, OffsetDateTime deadline) {
        if (task == null || !key(orderId).equals(task.taskKey()) || !"ORDER".equals(task.ownerModule())
                || !TYPE.equals(task.taskType()) || !"ORDER".equals(task.bizType())
                || !orderId.equals(task.bizId()) || task.expectedVersion() != null
                || task.maxRetryCount() != MAX_RETRIES || !TYPE.equals(task.retryPolicy())
                || task.submittedExecuteAt() == null || !task.submittedExecuteAt().isEqual(deadline))
            return false;
        try {
            var node = JSON.readTree(task.payloadJson());
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            return node.isObject() && fields.equals(Set.of("orderId", "expectedConfirmRound", "expectedConfirmDeadline"))
                    && node.path("orderId").isTextual() && orderId.equals(node.path("orderId").textValue())
                    && node.path("expectedConfirmRound").isIntegralNumber()
                    && node.path("expectedConfirmRound").canConvertToInt()
                    && node.path("expectedConfirmRound").intValue() == 0
                    && node.path("expectedConfirmDeadline").isTextual()
                    && deadline.withOffsetSameInstant(ZoneOffset.UTC).toString()
                            .equals(node.path("expectedConfirmDeadline").textValue());
        } catch (Exception invalid) { return false; }
    }
}
