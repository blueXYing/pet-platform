package com.petplatform.common;

public record CommandContext(
        String requestId,
        String traceId,
        OperatorType operatorType,
        String operatorId,
        String source
) {
}
