package com.petplatform.event.api;

import java.util.Set;

/**
 * In-process consumer of integration events during the local monolith stage.
 * Implementations own their transaction: the consume_log claim and the business
 * change must commit or roll back together (Event Catalog §15/§21).
 */
public interface IntegrationEventConsumer {

    /** Stable identity persisted in integration_event_consume_log (1..128 chars). */
    String consumerName();

    /** Event types this consumer is registered as a necessary consumer for. */
    Set<String> eventTypes();

    /**
     * Handle one event. Must be idempotent: a duplicate delivery after a crash or
     * lease takeover is normal. Throwing marks the event FAILED for retry; already
     * succeeded consumers are not re-executed on the next dispatch.
     */
    void consume(DispatchedEvent event);
}
