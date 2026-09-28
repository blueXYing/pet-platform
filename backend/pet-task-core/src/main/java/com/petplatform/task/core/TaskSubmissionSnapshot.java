package com.petplatform.task.core;

import java.time.OffsetDateTime;

/** Read-only diagnostic view. Neither this snapshot nor task status authorizes business effects. */
public record TaskSubmissionSnapshot(String taskId, String taskKey, String ownerModule,
        String taskType, String bizType, String bizId, Long expectedVersion, String payloadJson,
        OffsetDateTime submittedExecuteAt, OffsetDateTime executeAt, String status,
        int retryCount, int maxRetryCount, String retryPolicy) {}
