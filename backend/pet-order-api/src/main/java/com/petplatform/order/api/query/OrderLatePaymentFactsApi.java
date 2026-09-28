package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderLatePaymentFact;

/** ORDER-owned current proof for REFUND; no event payload is authoritative. */
public interface OrderLatePaymentFactsApi {
    /** SYSTEM-only store hint used to acquire the shared store guard. */
    String locateStore(String orderId, QueryContext context);

    /** Requires the caller's writable main-database transaction and current store guard. */
    OrderLatePaymentFact requireLatePayment(String orderId, String paymentId,
            String storeId, QueryContext context);
}
