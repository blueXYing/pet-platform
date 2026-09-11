package com.petplatform.common;

public record QueryContext(
        String traceId,
        OperatorType operatorType,
        String operatorId
) {
}
