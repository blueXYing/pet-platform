package com.petplatform.event.core;

/** Immutable outbox claim token handed to the dispatcher for one event. */
public record OutboxLease(
        long id,
        String eventId,
        String eventType,
        int eventVersion,
        java.time.OffsetDateTime occurredAt,
        String aggregateType,
        long aggregateId,
        String traceId,
        String payloadJson,
        String owner,
        int retryCount
) {
}
