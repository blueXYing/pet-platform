package com.petplatform.event.api;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** Event Catalog 08 existing v1 payload; no credentials or provider raw envelope. */
public record PaymentSucceededPayload(String paymentOrderId,String orderId,String channelTradeNo,BigDecimal paidAmount,OffsetDateTime paidAt) {}
