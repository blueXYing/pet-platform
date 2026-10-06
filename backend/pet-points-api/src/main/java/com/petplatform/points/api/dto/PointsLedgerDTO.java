package com.petplatform.points.api.dto;

import java.time.OffsetDateTime;

/**
 * CCR-C006 P1 ledger line. bizType is one of SIGN_IN/INVITE/TASK/ORDER_REWARD/REFUND_CLAWBACK;
 * delta is a signed non-zero integer string (positive accrual, negative clawback) and
 * balanceAfter is the integer balance right after that entry was written.
 */
public record PointsLedgerDTO(
        String ledgerId,
        String bizType,
        String delta,
        String balanceAfter,
        OffsetDateTime createdAt) {}
