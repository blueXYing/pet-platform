package com.petplatform.task.core;

import java.time.OffsetDateTime;

public record TaskExecutionContext(
        String taskId,
        String requestId,
        String traceId,
        OffsetDateTime now
) {
}
