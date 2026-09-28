package com.petplatform.order.biz.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Mutable database rows; stores convert these into their existing immutable projections. */
public final class OrderMapperRows {
    private OrderMapperRows() {}

    public static final class ProtectionOrder {
        public Long id, reservationId, userId, merchantId, storeId, serviceId, serviceStaffId, version;
        public String fulfillmentType, orderStage, verificationStatus;
    }

    public static final class Assignment {
        public Long id, orderId, staffId, version;
        public Integer isCurrent;
    }

    public static final class ExpiryOrder {
        public Long id, reservationId, storeId, version;
        public String orderStage, paymentStatus, verificationStatus, cancelReason;
        public BigDecimal payAmount, discountAmount;
        public LocalDateTime paymentExpireAt;
    }

    public static final class Locator {
        public Long storeId, userId;
    }

    public static final class PaymentOrder {
        public Long id, userId, merchantId, storeId, reservationId, version;
        public String orderStage, paymentStatus, verificationStatus, cancelReason;
        public BigDecimal payAmount, discountAmount;
        public LocalDateTime paymentExpireAt, paidAt;
    }

    public static final class PaymentResult {
        public Long paymentId, sourceEventId;
        public String channelTradeNo, resultType;
        public BigDecimal channelPaidAmount;
        public LocalDateTime channelPaidAt;
    }

    public static final class LateRefundOrder {
        public Long refundOrderId;
        public BigDecimal refundedAmount;
        public String orderStage, paymentStatus, verificationStatus, cancelReason;
    }

    public static final class LateRefundResult {
        public Long refundOrderId, paymentId, createdEventId, successEventId;
        public String refundType, refundSource, refundStatus;
        public BigDecimal refundAmount;
        public LocalDateTime succeededAt;
    }

}
