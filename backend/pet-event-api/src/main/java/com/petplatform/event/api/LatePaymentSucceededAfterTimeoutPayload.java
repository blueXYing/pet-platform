package com.petplatform.event.api;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** Event Catalog 08 existing v1 payload; no credentials or provider raw envelope. */
public record LatePaymentSucceededAfterTimeoutPayload(String orderId,String paymentOrderId,String paymentNo,BigDecimal channelPaidAmount,OffsetDateTime channelPaidAt,OffsetDateTime detectedAt) {}
