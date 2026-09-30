package com.petplatform.refund.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Database rows mapped by MyBatis; domain checks stay in the application service. */
public final class RefundMapperRows {
    public static final class OrderPresence {
        public Long id, orderId;
        public String status;
    }
    private RefundMapperRows() {}

    public static final class Binding {
        public Long businessRefundId, businessRefundNo, businessOrderId;
        public String refundType, sourceType, status, channel, channelRefundNo;
        public BigDecimal businessAmount, refundRatio;
        public LocalDateTime succeededAt, businessCreatedAt;
        public Long bindingRefundId, bindingRefundNo, bindingOrderId, paymentId, paymentNo;
        public Long storeId, merchantId, userId, paymentSuccessEventId, lateEventId;
        public String channelTradeNo, currency, requestId, successReceiptSha256;
        public BigDecimal channelPaidAmount, refundAmount;
        public LocalDateTime channelPaidAt, bindingCreatedAt;
        public Long bindingVersion, createdEventId, successEventId, sourceEventId, sourceBizId, sourceDecisionId;
        public String bindingSourceType;
    }

    public static final class Candidate {
        public Long refundOrderId, storeId;
        public String sourceType;
    }
}
