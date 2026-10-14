package com.petplatform.refund.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Read-only platform refund face for the single operator (contract 63, HTTP10 §5.2 query side):
 * one page/list over refund_order joined with its execution fact and the linked refund
 * application projection. The kernel re-proves the current ADMIN_WEB session, the
 * {@code refund.read} action and the resource scope on every call via the injected
 * authorization port. Sensitive material stays sealed: application reason cipher, merchant
 * decision cipher and channel secrets never cross this surface. No write path — channel
 * query/retry (HTTP10 §5.2) stay a separate drafted slice; one order keeps at most one
 * business refund (SSOT), so the list is the deduplicated truth.
 */
public interface AdminRefundQueryApi {

    /** One page of platform refunds, newest first (created_at DESC, id DESC). */
    PageResult<AdminRefundSummary> listRefunds(AdminRefundListQuery query);

    /** One refund detail: refund_order row + linked application projection + execution fact. */
    AdminRefundDetail getRefund(AdminRefundQuery query);

    record AdminRefundSummary(
            String refundOrderId, String refundNo, String orderId, String refundType,
            String sourceType, String status, BigDecimal refundAmount, String initiatorType,
            String channelRefundNo, OffsetDateTime succeededAt, OffsetDateTime createdAt) {}

    record AdminApplicationView(
            String applicationId, String status, BigDecimal requestedAmount,
            OffsetDateTime merchantDeadline, OffsetDateTime decidedAt) {}

    record AdminExecutionView(
            String channelTradeNo, BigDecimal refundAmount, OffsetDateTime channelPaidAt,
            String currency) {}

    record AdminRefundDetail(
            String refundOrderId, String refundNo, String orderId, String refundType,
            String sourceType, String status, BigDecimal refundAmount, String initiatorType,
            String channelRefundNo, OffsetDateTime succeededAt, OffsetDateTime createdAt,
            AdminApplicationView application, AdminExecutionView execution) {}

    /** Trusted final-authorization input re-checked inside the refund kernel per call. */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record AdminRefundListQuery(
            String status, String sourceType, String refundType, String orderId,
            OffsetDateTime createdFrom, OffsetDateTime createdTo, int page, int pageSize,
            QueryContext context, AdminAuthorization authorization) {}

    record AdminRefundQuery(String refundOrderId, QueryContext context, AdminAuthorization authorization) {}
}
