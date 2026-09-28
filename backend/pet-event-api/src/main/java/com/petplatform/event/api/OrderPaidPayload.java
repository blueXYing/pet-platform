package com.petplatform.event.api;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** Event Catalog 08 existing v1 payload; no credentials or provider raw envelope. */
public record OrderPaidPayload(String orderId,String reservationId,String couponInstanceId,OffsetDateTime paidAt,OffsetDateTime confirmDeadline) {}
