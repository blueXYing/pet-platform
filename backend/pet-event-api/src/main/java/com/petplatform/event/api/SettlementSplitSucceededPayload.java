package com.petplatform.event.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * SettlementSplitSucceededEvent.v1 payload (Contract 78 §6 / CCR-PAY-CENTER-001 stage 2).
 * Published atomically with SPLIT_SUCCEEDED via Transactional Outbox.
 */
public record SettlementSplitSucceededPayload(
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
        BigDecimal actualMerchantAmount,
        BigDecimal channelSplitFee,
        BigDecimal platformSubsidyDue,
        OffsetDateTime settledAt) {}
