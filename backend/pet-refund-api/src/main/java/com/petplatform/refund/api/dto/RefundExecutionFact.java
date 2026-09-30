package com.petplatform.refund.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Immutable business authorization; returned only under the current store guard. */
public record RefundExecutionFact(String refundOrderId, String refundNo, String orderId,
        String paymentId, String paymentNo, String storeId, String merchantId, String userId,
        String paymentSuccessEventId, String lateEventId, String channelTradeNo,
        BigDecimal originalPaidAmount, BigDecimal refundAmount, OffsetDateTime paidAt,
        String currency, String status, long bindingVersion, String createdEventId,
        OffsetDateTime createdAt, String sourceType, String sourceEventId, String sourceBizId, String sourceDecisionId,
        String refundType) {
    public RefundExecutionFact(String refundOrderId,String refundNo,String orderId,String paymentId,String paymentNo,
        String storeId,String merchantId,String userId,String paymentSuccessEventId,String lateEventId,String channelTradeNo,
        BigDecimal originalPaidAmount,BigDecimal refundAmount,OffsetDateTime paidAt,String currency,String status,
        long bindingVersion,String createdEventId,OffsetDateTime createdAt,String sourceType,String sourceEventId,
        String sourceBizId,String sourceDecisionId) {
        this(refundOrderId,refundNo,orderId,paymentId,paymentNo,storeId,merchantId,userId,paymentSuccessEventId,
            lateEventId,channelTradeNo,originalPaidAmount,refundAmount,paidAt,currency,status,bindingVersion,
            createdEventId,createdAt,sourceType,sourceEventId,sourceBizId,sourceDecisionId,"FULL");
    }
    public RefundExecutionFact(String refundOrderId,String refundNo,String orderId,String paymentId,String paymentNo,
        String storeId,String merchantId,String userId,String paymentSuccessEventId,String lateEventId,String channelTradeNo,
        BigDecimal originalPaidAmount,BigDecimal refundAmount,OffsetDateTime paidAt,String currency,String status,
        long bindingVersion,String createdEventId,OffsetDateTime createdAt,String sourceType,String sourceEventId) {
        this(refundOrderId,refundNo,orderId,paymentId,paymentNo,storeId,merchantId,userId,paymentSuccessEventId,
            lateEventId,channelTradeNo,originalPaidAmount,refundAmount,paidAt,currency,status,bindingVersion,
            createdEventId,createdAt,sourceType,sourceEventId,null,null);
    }
    public RefundExecutionFact(String refundOrderId,String refundNo,String orderId,String paymentId,String paymentNo,
        String storeId,String merchantId,String userId,String paymentSuccessEventId,String lateEventId,String channelTradeNo,
        BigDecimal originalPaidAmount,BigDecimal refundAmount,OffsetDateTime paidAt,String currency,String status,
        long bindingVersion,String createdEventId,OffsetDateTime createdAt) {
        this(refundOrderId,refundNo,orderId,paymentId,paymentNo,storeId,merchantId,userId,paymentSuccessEventId,
            lateEventId,channelTradeNo,originalPaidAmount,refundAmount,paidAt,currency,status,bindingVersion,
            createdEventId,createdAt,"LATE_PAYMENT_TIMEOUT",lateEventId);
    }
}
