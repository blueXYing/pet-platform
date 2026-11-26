package com.petplatform.payment.biz.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Synchronous pre-refund settlement gate (Ruling 40 §6, CCR-PAY-CENTER-001 §8, Contract 78 §8.3).
 * Invoked inside PAYMENT strictly outside any DB transaction before dispatching a channel refund.
 */
public interface SettlementRefundGate {

    record RefundGateRequest(
            long paymentId,
            long refundOrderId,
            BigDecimal refundAmount,
            BigDecimal originalPaidAmount,
            String sourceType) {}

    sealed interface GateDecision permits GateDecision.Proceed, GateDecision.Wait, GateDecision.Paused {
        record Proceed() implements GateDecision {}
        record Wait(OffsetDateTime nextCheckAt) implements GateDecision {}
        record Paused(String reasonCode, String detail) implements GateDecision {}
    }

    GateDecision ensureReadyForRefund(RefundGateRequest request);
}
