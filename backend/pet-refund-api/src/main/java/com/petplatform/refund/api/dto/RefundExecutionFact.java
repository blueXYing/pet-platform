package com.petplatform.refund.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Immutable business authorization; returned only under the current store guard. */
public record RefundExecutionFact(String refundOrderId, String refundNo, String orderId,
        String paymentId, String paymentNo, String storeId, String merchantId, String userId,
        String paymentSuccessEventId, String lateEventId, String channelTradeNo,
        BigDecimal originalPaidAmount, BigDecimal refundAmount, OffsetDateTime paidAt,
        String currency, String status, long bindingVersion, String createdEventId,
        OffsetDateTime createdAt) {}
