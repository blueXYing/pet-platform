package com.petplatform.event.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * PaymentCenterSucceededEvent.v1 payload (Contract 75 §6). No openid, merchant number, log_no/log_date,
 * raw provider envelope or signature. Consumers filter by bizType and must re-read PaymentCenterFactsApi.
 */
public record PaymentCenterSucceededPayload(String paymentOrderId, String paymentNo, String bizType,
        String bizNo, String channelTradeNo, BigDecimal paidAmount, OffsetDateTime paidAt) {}
