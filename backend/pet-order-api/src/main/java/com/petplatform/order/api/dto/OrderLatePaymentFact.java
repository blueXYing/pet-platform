package com.petplatform.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Persisted ORDER proof of a paid payment after a proven timeout closure. */
public record OrderLatePaymentFact(String orderId, String storeId, String merchantId,
        String userId, String reservationId, String paymentId, String paymentSuccessEventId,
        String channelTradeNo, BigDecimal channelPaidAmount, OffsetDateTime channelPaidAt) {}
