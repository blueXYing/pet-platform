package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
/** ORDER owns the interpretation of cancellation state; callers receive no display derivation rules. */
public interface OrderExpiryFactsApi {
    void assertExpiryCommitted(String orderId,String reservationId,String storeId,QueryContext context);
}
