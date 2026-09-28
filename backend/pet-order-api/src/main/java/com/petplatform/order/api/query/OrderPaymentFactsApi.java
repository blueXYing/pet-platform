package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderPaymentFact;

/** Only ORDER reads its own current payment/booking state. */
public interface OrderPaymentFactsApi {
    String locateStore(String orderId, QueryContext context);
    OrderPaymentFact readForPayment(String orderId, String storeId, QueryContext context);
    /** ORDER owns stage/deadline eligibility for a new payment intent. */
    void requirePayableForPreparation(String orderId,String storeId,QueryContext context);
    /** ORDER alone decides whether this original payment deadline is currently due. */
    default boolean isPaymentExpiryDue(String orderId, String storeId,
            java.time.OffsetDateTime expectedDeadline, QueryContext context) {
        throw new com.petplatform.common.ApiException(
                com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "ORDER expiry eligibility unavailable");
    }
    /** SCH beforeCommit must prove this transaction just marked the bound ORDER paid. */
    void assertPaymentCommitted(String orderId, String reservationId, String storeId,
            QueryContext context);
}
