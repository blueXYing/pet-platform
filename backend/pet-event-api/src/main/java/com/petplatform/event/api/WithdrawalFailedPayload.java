package com.petplatform.event.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * WithdrawalFailedEvent.v1 payload (Contract 80 / CCR-PAY-CENTER-001 stage 5).
 * Published atomically with withdrawal_order=FAILED via Transactional Outbox.
 */
public record WithdrawalFailedPayload(
        String withdrawalId,
        String merOrderNo,
        String merchantId,
        String receiverNo,
        BigDecimal amount,
        String drawJnl,
        String failReason,
        OffsetDateTime failedAt) {}
