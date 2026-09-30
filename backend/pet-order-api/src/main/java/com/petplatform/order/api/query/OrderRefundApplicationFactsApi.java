package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderRefundOriginFact;

/** Persisted ordinary-refund origin, available only after the real CREATE_REFUND commit proof. */
public interface OrderRefundApplicationFactsApi {
    OrderRefundOriginFact requireApprovedRefund(String orderId, String paymentId, String storeId, QueryContext context);
}
