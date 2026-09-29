package com.petplatform.order.api.query;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
public interface OrderMerchantRejectFactsApi {
    String locateStore(String orderId, QueryContext context);
    OrderRefundOriginFact requireRejected(String orderId, String paymentId, String storeId, QueryContext context);
}
