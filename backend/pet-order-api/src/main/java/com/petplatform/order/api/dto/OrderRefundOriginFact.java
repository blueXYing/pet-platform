package com.petplatform.order.api.dto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** ORDER-owned immutable source proof; never inferred from an event payload. */
public record OrderRefundOriginFact(String orderId, String storeId, String merchantId,
        String userId, String reservationId, String paymentId, String paymentSuccessEventId,
        String channelTradeNo, BigDecimal channelPaidAmount, OffsetDateTime channelPaidAt,
        String sourceType, String sourceEventId, String refundOrderId) {
    public static OrderRefundOriginFact late(OrderLatePaymentFact f, String eventId, String refundId) {
        return new OrderRefundOriginFact(f.orderId(),f.storeId(),f.merchantId(),f.userId(),
            f.reservationId(),f.paymentId(),f.paymentSuccessEventId(),f.channelTradeNo(),
            f.channelPaidAmount(),f.channelPaidAt(),"LATE_PAYMENT_TIMEOUT",eventId,refundId);
    }
}
