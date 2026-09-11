package com.petplatform.event.core;

/**
 * Marker for durable Outbox / local event-dispatch infrastructure.
 * Business modules publish only through pet-event-api.
 */
public final class EventCoreModuleMarker {
    private EventCoreModuleMarker() {}
}
