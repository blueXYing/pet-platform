package com.petplatform.order.biz.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import com.petplatform.order.api.dto.OrderSnapshotDTO;

/**
 * Order-domain computation of the six {@code OrderActions} booleans (OpenAPI11 OrderActions;
 * HTTP contract 10 §3.7 rework 2026-10-07). Each rule cites its authority and nothing is
 * invented: pay (SSOT order lifecycle + contract 40 payment window), reschedule (contract 46
 * admission), refund application (contract 49 ORDER admission), verification code entry
 * (contracts 47 §4 / 48 kernel eligibility; an unfinished aftersale never blocks it per SSOT —
 * only a created refund_order does), review (contract 07 §7.7), aftersale entry (contract 50
 * policy incl. the REF-001 pre-service refusal). The booleans are read-side projections of the
 * same order-owned facts the write kernels re-check under their own guards; they gate UI entry
 * points, never the commands themselves.
 */
public final class OrderActionAvailability {
    private OrderActionAvailability() {}

    /** Baseline §5 step 5: active aftersale projections. */
    private static final Set<String> ACTIVE_AFTERSALE = Set.of("PENDING", "PROCESSING", "WAITING_SUPPLEMENT");

    /** Raw projection columns; DATETIME(3) reads as UTC wall time, {@code now} in the same frame. */
    public record Facts(String orderStage, String paymentStatus, String verificationStatus,
            Long refundOrderId, BigDecimal refundedAmount, BigDecimal payAmount,
            String refundApplicationStatus, String aftersaleStatus, Integer rescheduleCount,
            LocalDateTime paymentExpireAt, LocalDateTime canceledAt, String cancelReason,
            LocalDateTime confirmedAt, LocalDateTime appointmentStartAt, LocalDateTime verifiedAt) {}

    public static OrderSnapshotDTO.Actions evaluate(Facts f, OffsetDateTime now) {
        return new OrderSnapshotDTO.Actions(
                canPay(f, now),
                canReschedule(f, now),
                canApplyRefund(f),
                canShowVerificationCode(f),
                canReview(f, now),
                canApplyAfterSale(f, now));
    }

    /**
     * SSOT 待支付生命周期 + contract 40: the payment window (payment_expire_at) is the only
     * paying chance; a late payment keeps the order closed with an automatic full refund, so an
     * expired window is no longer payable. Transient channel states INIT/PAYING only.
     */
    private static boolean canPay(Facts f, OffsetDateTime now) {
        return "PENDING_PAYMENT".equals(f.orderStage())
                && ("INIT".equals(f.paymentStatus()) || "PAYING".equals(f.paymentStatus()))
                && f.paymentExpireAt() != null
                && local(now).isBefore(f.paymentExpireAt())
                && f.refundOrderId() == null;
    }

    /**
     * Contract 46 admission, verbatim: PENDING_CONFIRM/PENDING_SERVICE, PAID, UNVERIFIED,
     * reschedule_count=0, strictly before the original appointment start, and any-source
     * refund_order blocks the reschedule.
     */
    private static boolean canReschedule(Facts f, OffsetDateTime now) {
        return ("PENDING_CONFIRM".equals(f.orderStage()) || "PENDING_SERVICE".equals(f.orderStage()))
                && "PAID".equals(f.paymentStatus())
                && "UNVERIFIED".equals(f.verificationStatus())
                && f.rescheduleCount() != null && f.rescheduleCount() == 0
                && f.appointmentStartAt() != null && local(now).isBefore(f.appointmentStartAt())
                && f.refundOrderId() == null
                && zero(f.refundedAmount());
    }

    /**
     * Contract 49 ORDER admission (normal()/bind): the refund application covers both §3.9
     * windows (auto full before service, merchant 24h after service), requires the
     * verified/completed or unverified/pending-service pair, PAID with an uncancelled order,
     * no refunded amount, no refund_order, and no live application (a rejected one may retry).
     */
    private static boolean canApplyRefund(Facts f) {
        return serviceablePair(f)
                && "PAID".equals(f.paymentStatus())
                && f.canceledAt() == null && f.cancelReason() == null
                && f.payAmount() != null && f.payAmount().signum() > 0
                && zero(f.refundedAmount())
                && f.refundOrderId() == null
                && f.confirmedAt() != null
                && (f.refundApplicationStatus() == null || "REJECTED".equals(f.refundApplicationStatus()));
    }

    /**
     * Contract 47 §4 entry view + contract 48 kernel eligibility projection: fulfillable stage,
     * paid, unverified, no refund_order, nothing refunded, not canceled, merchant/auto confirm
     * already happened. SSOT: an unfinished aftersale (no refund_order yet) never blocks the
     * code, and neither does a pending refund application, so aftersale_status and
     * refund_application_status stay out of this rule.
     */
    private static boolean canShowVerificationCode(Facts f) {
        return "PENDING_SERVICE".equals(f.orderStage())
                && "PAID".equals(f.paymentStatus())
                && "UNVERIFIED".equals(f.verificationStatus())
                && f.refundOrderId() == null
                && zero(f.refundedAmount())
                && f.canceledAt() == null
                && f.confirmedAt() != null;
    }

    /**
     * Contract 07 §7.7: must be verified, within verifiedAt + 30 days; a later PARTIAL refund
     * keeps the review eligible (scoreIncluded is that endpoint's concern). A full refund
     * (REFUNDED) or an in-flight refund order is conservatively false pending adjudication —
     * §7.7 only guarantees the partial-success case.
     */
    private static boolean canReview(Facts f, OffsetDateTime now) {
        if (!"VERIFIED".equals(f.verificationStatus()) || f.verifiedAt() == null) return false;
        if (local(now).isAfter(f.verifiedAt().plusDays(30))) return false;
        if (f.refundOrderId() == null) return true;
        BigDecimal refunded = f.refundedAmount();
        return refunded != null && refunded.signum() > 0
                && f.payAmount() != null && refunded.compareTo(f.payAmount()) < 0;
    }

    /**
     * Contract 50 policy (AfterSaleEligibilityPolicy) over the order projection: same
     * serviceable pair and paid/uncancelled/no-refund facts as the refund application, plus no
     * live application and no active case; the seven-day window anchors at verifiedAt
     * (verified/completed) or appointmentStart (unverified, strictly post-start per REF-001,
     * and only after a rejected refund application — the pre-service entry is the refund
     * application, not aftersale).
     */
    private static boolean canApplyAfterSale(Facts f, OffsetDateTime now) {
        if (!serviceablePair(f)
                || !"PAID".equals(f.paymentStatus())
                || f.canceledAt() != null || f.cancelReason() != null
                || !zero(f.refundedAmount())
                || f.refundOrderId() != null
                || f.confirmedAt() == null
                || (f.refundApplicationStatus() != null && !"REJECTED".equals(f.refundApplicationStatus()))
                || (f.aftersaleStatus() != null && ACTIVE_AFTERSALE.contains(f.aftersaleStatus()))) {
            return false;
        }
        boolean verified = "VERIFIED".equals(f.verificationStatus());
        LocalDateTime anchor = verified ? f.verifiedAt() : f.appointmentStartAt();
        if (anchor == null) return false;
        // The unverified route additionally requires the prior rejected application (policy).
        if (!verified && !"REJECTED".equals(f.refundApplicationStatus())) return false;
        LocalDateTime at = local(now);
        return !at.isBefore(anchor) && !at.isAfter(anchor.plusDays(7));
    }

    /** Contract 49/50 normal(): COMPLETED+VERIFIED or PENDING_SERVICE+UNVERIFIED only. */
    private static boolean serviceablePair(Facts f) {
        return ("COMPLETED".equals(f.orderStage()) && "VERIFIED".equals(f.verificationStatus()))
                || ("PENDING_SERVICE".equals(f.orderStage()) && "UNVERIFIED".equals(f.verificationStatus()));
    }

    private static boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private static LocalDateTime local(OffsetDateTime now) {
        return now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
