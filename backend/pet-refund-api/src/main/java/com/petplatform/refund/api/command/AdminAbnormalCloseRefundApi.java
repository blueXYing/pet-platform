package com.petplatform.refund.api.command;

import com.petplatform.common.QueryContext;

/**
 * Joins the caller's (order kernel) store-guarded transaction to create the full business refund
 * for one ADMIN abnormal close (contract-63 v0.2, HTTP10 §5.5 draft: 异常关闭 + 全额退款).
 * The refund kernel only inserts after the ORDER-owned immutable closed proof reads back; no
 * caller-supplied amount (the channel-paid amount is the only truth). Mirrors
 * {@link MerchantRejectRefundApi}.
 */
public interface AdminAbnormalCloseRefundApi {
    void createForAbnormalClose(String orderId, String paymentId, String storeId, String refundId, QueryContext context);
}
