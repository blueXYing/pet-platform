package com.petplatform.task.core;

import java.time.Duration;

public sealed interface TaskExecutionResult
        permits TaskExecutionResult.Success,
                TaskExecutionResult.Cancelled,
                TaskExecutionResult.Retry,
                TaskExecutionResult.Dead {

    record Success(String resultCode) implements TaskExecutionResult {}
    record Cancelled(String reasonCode) implements TaskExecutionResult {}
    record Retry(String errorCode, Duration nextDelay) implements TaskExecutionResult {}
    record Dead(String errorCode) implements TaskExecutionResult {}
}
