package com.petplatform.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** ORDER-owned current payment admission fact; IDs stay decimal strings across modules. */
public record OrderPaymentFact(String orderId, String userId, String merchantId,
        String storeId, String reservationId, String orderStage, String paymentStatus,
        String verificationStatus, BigDecimal payAmount, BigDecimal discountAmount,
        OffsetDateTime paymentExpireAt) {}
