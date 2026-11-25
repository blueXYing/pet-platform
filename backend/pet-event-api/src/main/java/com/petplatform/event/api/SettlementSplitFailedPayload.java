package com.petplatform.event.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * SettlementSplitFailedEvent.v1 payload (Contract 78 §6 / CCR-PAY-CENTER-001 stage 2).
 * Published atomically with SPLIT_FAILED via Transactional Outbox.
 */
public record SettlementSplitFailedPayload(
        String settlementOrderId,
        String instructionId,
        String paymentId,
        String orderId,
        String bizType,
        String bizNo,
        String merchantId,
        String outSeparateNo,
        String separateNo,
        BigDecimal splitAmount,
        String reasonCode,
        String reasonDetail,
        OffsetDateTime failedAt) {}
