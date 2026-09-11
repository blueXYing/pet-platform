package com.petplatform.event.api;

import java.time.OffsetDateTime;

public record IntegrationEvent<T>(
        String eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String aggregateType,
        String aggregateId,
        String traceId,
        T payload
) {
}
