package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderRefundOriginFact;

/**
 * ORDER-owned immutable proof that one order was closed by the contract-63 v0.2 operator
 * abnormal-close action (source ADMIN_ABNORMAL_CLOSE). Mirrors
 * {@link OrderMerchantRejectFactsApi}: the refund kernel reads this inside its guarded
 * transaction before creating or completing the linked refund — never infers from events.
 */
public interface OrderAdminCloseFactsApi {

    String locateStore(String orderId, QueryContext context);

    OrderRefundOriginFact requireClosed(String orderId, String paymentId, String storeId, QueryContext context);
}
