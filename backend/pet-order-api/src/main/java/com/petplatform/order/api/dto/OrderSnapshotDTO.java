package com.petplatform.order.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Internal order snapshot per internal contract 07 §7.4 (realized by the C-004 read slice,
 * 2026-10-07; {@code actions} added by the same slice's rework per OpenAPI11 OrderActions).
 * All ids cross module boundaries as canonical decimal strings; money keeps full BigDecimal
 * precision (HTTP adapters render two-decimal strings). {@code displayStatus} is the product
 * display state computed only by the order domain (tech baseline §5); every fact field stays a
 * raw string so callers never re-derive business truth. {@code actions} is the order-domain
 * UI-entry projection (six booleans, OpenAPI11 OrderActions); the write kernels re-check
 * everything under their own guards.
 */
public record OrderSnapshotDTO(
        String orderId,
        String orderNo,
        String userId,
        String merchantId,
        String storeId,
        String serviceId,
        String reservationId,
        String orderStage,
        String paymentStatus,
        String refundApplicationStatus,
        String refundStatus,
        String afterSaleStatus,
        String verificationStatus,
        String displayStatus,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payAmount,
        BigDecimal refundedAmount,
        OffsetDateTime appointmentStart,
        OffsetDateTime appointmentEnd,
        OffsetDateTime paidAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime verifiedAt,
        int rescheduleCount,
        long version,
        Actions actions
) {

    /** OpenAPI11 OrderActions: UI entry points only, never an authorization proof. */
    public record Actions(
            boolean canPay,
            boolean canReschedule,
            boolean canApplyRefund,
            boolean canShowVerificationCode,
            boolean canReview,
            boolean canApplyAfterSale
    ) {}
}
