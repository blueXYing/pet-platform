package com.petplatform.order.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Truth table for the order-domain OrderActions computation (fixed read moment
 * 2026-10-07T12:00Z). Every case cites the same authority the production rule documents
 * (contracts 40/46/47/48/49/50 and 07 §7.7); the pet-boot MySQL acceptance test cross-checks
 * the same rules end to end over real rows. Rendering: P=canPay R=canReschedule
 * F=canApplyRefund V=canShowVerificationCode W=canReview A=canApplyAfterSale, '-' = false.
 */
class OrderActionAvailabilityTest {

    private static final BigDecimal PAY = new BigDecimal("128.00");
    private static final BigDecimal PART = new BigDecimal("50.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final java.time.OffsetDateTime NOW = java.time.OffsetDateTime.parse("2026-10-07T12:00:00Z");

    /** Defaults model a healthy future appointment: confirmed, first round, nothing applied. */
    private static OrderActionAvailability.Facts facts(String stage, String payment, String verification) {
        return new OrderActionAvailability.Facts(stage, payment, verification,
                null, ZERO, PAY, null, null, 0,
                LocalDateTime.parse("2030-01-01T00:00"), null, null,
                LocalDateTime.parse("2026-10-01T08:00"),
                LocalDateTime.parse("2030-01-01T09:00"), null);
    }

    private static OrderActionAvailability.Facts facts(String stage, String payment, String verification,
            Long refundOrder, BigDecimal refunded, String application, String aftersale,
            LocalDateTime appointmentStart, LocalDateTime verifiedAt) {
        return new OrderActionAvailability.Facts(stage, payment, verification,
                refundOrder, refunded, PAY, application, aftersale, 0,
                LocalDateTime.parse("2030-01-01T00:00"), null, null,
                LocalDateTime.parse("2026-10-01T08:00"), appointmentStart, verifiedAt);
    }

    private static String render(OrderActionAvailability.Facts f) {
        var a = OrderActionAvailability.evaluate(f, NOW);
        return (a.canPay() ? "P" : "-") + (a.canReschedule() ? "R" : "-")
                + (a.canApplyRefund() ? "F" : "-") + (a.canShowVerificationCode() ? "V" : "-")
                + (a.canReview() ? "W" : "-") + (a.canApplyAfterSale() ? "A" : "-");
    }

    @Test
    void pendingPaymentInsideTheWindowCanOnlyPay() {
        assertEquals("P-----", render(facts("PENDING_PAYMENT", "INIT", "UNVERIFIED")));
        assertEquals("P-----", render(facts("PENDING_PAYMENT", "PAYING", "UNVERIFIED")));
        // Contract 40: outside the window the late payment keeps the order closed — nothing left.
        OrderActionAvailability.Facts expired = new OrderActionAvailability.Facts(
                "PENDING_PAYMENT", "INIT", "UNVERIFIED", null, ZERO, PAY, null, null, 0,
                LocalDateTime.parse("2026-10-01T00:00"), null, null,
                LocalDateTime.parse("2026-10-01T08:00"),
                LocalDateTime.parse("2030-01-01T09:00"), null);
        assertEquals("------", render(expired));
    }

    @Test
    void rescheduleFollowsContract46Admission() {
        // Isolated on PENDING_CONFIRM (no refund/code entries share that stage).
        assertEquals("-R----", render(facts("PENDING_CONFIRM", "PAID", "UNVERIFIED",
                null, ZERO, null, null, LocalDateTime.parse("2030-01-01T09:00"), null)));
        // Second reschedule is spent: at most one per order.
        OrderActionAvailability.Facts spent = new OrderActionAvailability.Facts(
                "PENDING_CONFIRM", "PAID", "UNVERIFIED", null, ZERO, PAY, null, null, 1,
                null, null, null, LocalDateTime.parse("2026-10-01T08:00"),
                LocalDateTime.parse("2030-01-01T09:00"), null);
        assertEquals("------", render(spent));
        // Appointment start already passed.
        assertEquals("------", render(facts("PENDING_CONFIRM", "PAID", "UNVERIFIED",
                null, ZERO, null, null, LocalDateTime.parse("2026-10-01T09:00"), null)));
        // Any-source refund_order blocks the reschedule.
        assertEquals("------", render(facts("PENDING_CONFIRM", "PAID", "UNVERIFIED",
                6901L, ZERO, null, null, LocalDateTime.parse("2030-01-01T09:00"), null)));
    }

    @Test
    void refundApplicationFollowsContract49OrderAdmission() {
        // Pre-service unconfirmed stage has no application/code entries yet.
        assertEquals("-R----", render(facts("PENDING_CONFIRM", "PAID", "UNVERIFIED",
                null, ZERO, null, null, LocalDateTime.parse("2030-01-01T09:00"), null)));
        // Verified/completed: refund application, review and aftersale entries all open.
        assertEquals("--F-WA", render(facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-10-04T12:00"))));
        // A live application blocks a new one (the code entry stays open, SSOT).
        assertEquals("---V--", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, "PENDING_MERCHANT", null,
                LocalDateTime.parse("2026-10-01T09:00"), null)));
        // A rejected application may retry; post-start (appointment passed, reschedule gone)
        // it also opens the aftersale entry.
        assertEquals("--FV-A", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, "REJECTED", null,
                LocalDateTime.parse("2026-10-05T09:00"), null)));
    }

    @Test
    void verificationCodeEntryFollowsContract47AndSsot() {
        // SSOT: an unfulfilled aftersale (no refund_order) never blocks the code; contract 46/49
        // admission does not check the aftersale either, so reschedule/refund entries stay open.
        assertEquals("-RFV--", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, null, "PROCESSING", LocalDateTime.parse("2030-01-01T09:00"), null)));
        // Without the merchant/auto confirm there is no fulfillable code yet (contract 48) and
        // the refund application requires the same confirm (contract 49 normal()); contract 46
        // admission lists no confirm requirement, so the reschedule entry stays open.
        assertEquals("-R----", render(new OrderActionAvailability.Facts(
                "PENDING_SERVICE", "PAID", "UNVERIFIED", null, ZERO, PAY, null, null, 0,
                null, null, null, null,
                LocalDateTime.parse("2030-01-01T09:00"), null)));
        // refund_order created: every entry closed even with everything else fine.
        assertEquals("------", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                6901L, ZERO, null, null, LocalDateTime.parse("2030-01-01T09:00"), null)));
    }

    @Test
    void reviewFollowsContract07SevenPointSeven() {
        // Verified 3 days ago: reviewable for 30 days.
        assertEquals("--F-WA", render(facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-10-04T12:00"))));
        // Partial refund after verification keeps the review eligible (07 §7.7); the money
        // movements close the refund/aftersale entries but not the review.
        assertEquals("----W-", render(facts("COMPLETED", "PAID", "VERIFIED",
                6902L, PART, null, "RESOLVED", null, LocalDateTime.parse("2026-10-04T12:00"))));
        // Full refund: conservatively false pending adjudication.
        assertEquals("------", render(facts("COMPLETED", "PAID", "VERIFIED",
                6902L, PAY, "APPROVED", "RESOLVED", null, LocalDateTime.parse("2026-10-04T12:00"))));
        // In-flight refund (final type unknown): conservatively false.
        assertEquals("------", render(facts("COMPLETED", "PAID", "VERIFIED",
                6902L, ZERO, "APPROVED", "RESOLVED", null, LocalDateTime.parse("2026-10-04T12:00"))));
        // 36 days after verification: outside the review window and the aftersale window; the
        // refund application itself carries no time window (49).
        assertEquals("--F---", render(facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-09-01T12:00"))));
    }

    @Test
    void aftersaleEntryFollowsContract50PolicyAndRef001() {
        // Verified anchor, day 3 of 7.
        assertEquals("--F-WA", render(facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-10-04T12:00"))));
        // Verified anchor, day 8: only the aftersale window closed.
        assertEquals("--F-W-", render(facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-09-29T12:00"))));
        // Unverified pre-start (REF-001): the pre-service entry is the refund application.
        assertEquals("-RFV--", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, "REJECTED", null, LocalDateTime.parse("2026-10-10T09:00"), null)));
        // Unverified post-start with a rejected application: the aftersale entry opens
        // (the passed appointment already closed the reschedule entry).
        assertEquals("--FV-A", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, "REJECTED", null, LocalDateTime.parse("2026-10-05T09:00"), null)));
        // An active case closes the entry for a new one.
        assertEquals("--FV--", render(facts("PENDING_SERVICE", "PAID", "UNVERIFIED",
                null, ZERO, "REJECTED", "PROCESSING",
                LocalDateTime.parse("2026-10-05T09:00"), null)));
    }

    @Test
    void latePaymentOrderIsClosedForEveryEntry() {
        // SSOT: a late payment keeps the order closed with an automatic full refund on the way.
        OrderActionAvailability.Facts late = new OrderActionAvailability.Facts(
                "CANCELED", "PAID", "UNVERIFIED", 6904L, ZERO, PAY, null, null, 0,
                LocalDateTime.parse("2026-10-03T00:30"), LocalDateTime.parse("2026-10-03T01:00"),
                "PAYMENT_TIMEOUT", null, LocalDateTime.parse("2026-10-03T00:00"), null);
        assertEquals("------", render(late));
    }

    @Test
    void evaluationReadsOnlyTheGivenInstant() {
        // Same facts evaluated one hour earlier: identical projection — the rule never reads a
        // wall clock, the caller supplies the read moment.
        OrderActionAvailability.Facts verified = facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-10-04T12:00"));
        assertEquals(renderAt(verified, Instant.parse("2026-10-07T11:00:00Z")),
                renderAt(verified, Instant.parse("2026-10-07T12:00:00Z")));
        // Crossing the verified+7d aftersale boundary flips only canApplyAfterSale.
        OrderActionAvailability.Facts boundary = facts("COMPLETED", "PAID", "VERIFIED",
                null, ZERO, null, null, null, LocalDateTime.parse("2026-09-30T13:00:00"));
        assertEquals("--F-WA", renderAt(boundary, Instant.parse("2026-10-07T12:59:59Z")));
        assertEquals("--F-W-", renderAt(boundary, Instant.parse("2026-10-07T13:00:01Z")));
    }

    private static String renderAt(OrderActionAvailability.Facts f, Instant at) {
        var a = OrderActionAvailability.evaluate(f, at.atOffset(ZoneOffset.UTC));
        return (a.canPay() ? "P" : "-") + (a.canReschedule() ? "R" : "-")
                + (a.canApplyRefund() ? "F" : "-") + (a.canShowVerificationCode() ? "V" : "-")
                + (a.canReview() ? "W" : "-") + (a.canApplyAfterSale() ? "A" : "-");
    }
}
