package com.petplatform.event.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * WithdrawalSucceededEvent.v1 payload (Contract 80 / CCR-PAY-CENTER-001 stage 5).
 * Published atomically with withdrawal_order=SUCCEEDED via Transactional Outbox.
 */
public record WithdrawalSucceededPayload(
        String withdrawalId,
        String merOrderNo,
        String merchantId,
        String receiverNo,
        BigDecimal amount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String drawJnl,
        OffsetDateTime succeededAt) {}
