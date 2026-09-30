package com.petplatform.aftersale.biz.apiimpl;

import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Emits the AFS-owned invalidation event without deriving or exposing order display status. */
final class AfterSaleVerificationEvents {
    private AfterSaleVerificationEvents() {}

    static void invalidated(IntegrationEventPublisher publisher, String eventId, String caseId,
            String orderId, OffsetDateTime at, String traceId) {
        publisher.publish(new IntegrationEvent<>(eventId, "AfterSaleInvalidatedEvent", 1, at,
                "AFTERSALE", caseId, traceId, Map.of("afterSaleId", caseId, "orderId", orderId,
                "reasonCode", "VERIFICATION_WON_RACE", "invalidatedAt",
                at.withOffsetSameInstant(ZoneOffset.UTC).toString())));
    }
}
