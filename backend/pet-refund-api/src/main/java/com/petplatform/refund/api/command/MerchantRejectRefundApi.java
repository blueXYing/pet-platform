package com.petplatform.refund.api.command;
import com.petplatform.common.QueryContext;
/** Joins the caller's store-guarded transaction. No caller-supplied amount. */
public interface MerchantRejectRefundApi {
    void create(String orderId, String paymentId, String storeId, String refundId, QueryContext context);
}
