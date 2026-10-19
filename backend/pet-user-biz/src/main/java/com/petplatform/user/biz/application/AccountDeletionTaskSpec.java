package com.petplatform.user.biz.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.task.core.TaskSubmissionSnapshot;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable durable-task submission shared by the producer (the deletion request transaction),
 * the inspector, the worker and any repair (contract 66 v0.2 cooling period, SQL13 family
 * precedent ORDER_AUTO_CONFIRM): one ACCOUNT_DELETION_EXEC task per user_deletion_request
 * row, first attempt scheduled at the cooling-period expiry (enqueueAt), retry policy named
 * after the task type. The execution itself is idempotent through the request-row state, so
 * the binding check only pins the exact immutable submission.
 */
public final class AccountDeletionTaskSpec {
    public static final String TYPE = "ACCOUNT_DELETION_EXEC";
    public static final String RETRY_POLICY = TYPE;
    public static final int MAX_RETRIES = 8;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AccountDeletionTaskSpec() {}

    public static String key(long requestRowId) {
        if (requestRowId <= 0) throw new IllegalArgumentException("Invalid request row id");
        return TYPE + ":" + requestRowId;
    }

    public static String payload(long requestRowId, long userId, LocalDateTime effectiveAt) {
        if (requestRowId <= 0 || userId <= 0 || effectiveAt == null) {
            throw new IllegalArgumentException("Invalid deletion task payload fields");
        }
        try {
            return JSON.writeValueAsString(Map.of(
                    "requestRowId", Long.toUnsignedString(requestRowId),
                    "userId", Long.toUnsignedString(userId),
                    "effectiveAt", effectiveAt.atOffset(ZoneOffset.UTC).toString()));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid deletion task payload", invalid);
        }
    }

    /** True exactly when the claimed lease carries the immutable submission facts. */
    public static boolean matches(TaskSubmissionSnapshot task, long requestRowId, long userId,
            LocalDateTime effectiveAt) {
        if (task == null || !key(requestRowId).equals(task.taskKey())
                || !"USER".equals(task.ownerModule()) || !TYPE.equals(task.taskType())
                || !"ACCOUNT_DELETION".equals(task.bizType())
                || !Long.toUnsignedString(requestRowId).equals(task.bizId())
                || task.expectedVersion() != null || task.maxRetryCount() != MAX_RETRIES
                || !RETRY_POLICY.equals(task.retryPolicy())
                || task.submittedExecuteAt() == null
                || !task.submittedExecuteAt().isEqual(effectiveAt.atOffset(ZoneOffset.UTC))) {
            return false;
        }
        try {
            var node = JSON.readTree(task.payloadJson());
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            return node.isObject()
                    && fields.equals(Set.of("requestRowId", "userId", "effectiveAt"))
                    && Long.toUnsignedString(requestRowId).equals(node.path("requestRowId").textValue())
                    && Long.toUnsignedString(userId).equals(node.path("userId").textValue())
                    && node.path("effectiveAt").isTextual()
                    && effectiveAt.atOffset(ZoneOffset.UTC).toString()
                            .equals(node.path("effectiveAt").textValue());
        } catch (Exception invalid) {
            return false;
        }
    }
}
