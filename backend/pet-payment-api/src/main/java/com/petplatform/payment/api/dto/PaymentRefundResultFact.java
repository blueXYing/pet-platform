package com.petplatform.payment.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Signed channel result persisted by PAYMENT, read under the current store guard. */
public record PaymentRefundResultFact(String refundOrderId, String refundNo, String paymentId,
        String orderId, String storeId, String originalChannelTradeNo, BigDecimal refundAmount,
        String currency, String channelRefundNo, String channelStatus, OffsetDateTime resultAt,
        String receiptSource, String receiptSha256) {}
