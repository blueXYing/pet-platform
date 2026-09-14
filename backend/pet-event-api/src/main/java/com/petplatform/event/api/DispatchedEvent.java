package com.petplatform.event.api;

import java.time.OffsetDateTime;

/**
 * Event handed to an {@link IntegrationEventConsumer} by the local dispatcher.
 * Payload stays the raw stored JSON: each consumer decodes its own payload type
 * and routes by eventType + eventVersion (Event Catalog §16).
 */
public record DispatchedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String aggregateType,
        long aggregateId,
        String traceId,
        String payloadJson
) {
}
