package com.petplatform.payment.api.dto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** Guarded authoritative PAYMENT facts; event DTOs alone never authorize fulfillment. */
public record PaymentSuccessFact(String paymentId,String paymentNo,String orderId,String storeId,
        String merchantId,String userId,String channelTradeNo,BigDecimal paidAmount,
        OffsetDateTime paidAt,String successEventId,String currency) {}
