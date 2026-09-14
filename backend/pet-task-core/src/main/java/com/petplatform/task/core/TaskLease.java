package com.petplatform.task.core;

/** Immutable claim token. Version changes on every claim, including same-owner recovery. */
public record TaskLease(long taskId, String taskKey, String taskType, long bizId,
                        Long expectedVersion, String payloadJson, String owner,
                        long version, long attemptId, int attemptNo,
                        int retryCount, int maxRetryCount, String retryPolicy) {}
