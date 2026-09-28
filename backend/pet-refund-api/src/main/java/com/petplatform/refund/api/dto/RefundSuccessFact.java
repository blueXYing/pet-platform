package com.petplatform.refund.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RefundSuccessFact(String refundOrderId, String refundNo, String orderId,
        String paymentId, String storeId, BigDecimal refundAmount, BigDecimal originalPaidAmount,
        String channelRefundNo, OffsetDateTime succeededAt, String successEventId,
        String refundSource) {}
