package com.petplatform.order.biz.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * The single authoritative DisplayOrderStatus computation (tech baseline §5; AGENTS hard rule:
 * only the order domain computes it, every surface reads the projection). Inputs are the
 * order-domain's own transactionally maintained {@code pet_order} projections:
 * refund_order_id/refunded_amount (refund facts, success projected only on real channel
 * success), refund_application_status (49) and aftersale_status (48).
 *
 * <p>Priority (baseline §5): refund success/type &gt; refund in flight &gt; pending merchant
 * refund application &gt; active aftersale &gt; order stage. The five stage values share the
 * DisplayOrderStatus names, so stages 6-10 project verbatim.</p>
 *
 * <p>The list filter mirrors this truth table as fixed branches in OrderQueryMapper.xml
 * (bound values only, per the PERSISTENCE-XML gate); the MySQL acceptance test cross-checks
 * every filtered row against this same computation.</p>
 */
public final class OrderDisplayStatus {
    private OrderDisplayStatus() {}

    public static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String PENDING_SERVICE = "PENDING_SERVICE";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELED = "CANCELED";
    public static final String REFUND_PENDING_CONFIRM = "REFUND_PENDING_CONFIRM";
    public static final String REFUNDING = "REFUNDING";
    public static final String REFUNDED = "REFUNDED";
    public static final String PARTIAL_REFUND = "PARTIAL_REFUND";
    public static final String AFTERSALE = "AFTERSALE";

    /** Baseline §5 step 5: only these aftersale projections count as the active AFTERSALE tab. */
    private static final Set<String> ACTIVE_AFTERSALE = Set.of("PENDING", "PROCESSING", "WAITING_SUPPLEMENT");
    private static final Set<String> STAGES = Set.of(PENDING_PAYMENT, PENDING_CONFIRM, PENDING_SERVICE, COMPLETED, CANCELED);
    /** Selection order for display and the only values the filter accepts. */
    public static final List<String> VALUES = List.of(
            PENDING_PAYMENT, PENDING_CONFIRM, PENDING_SERVICE, COMPLETED, CANCELED,
            REFUND_PENDING_CONFIRM, REFUNDING, REFUNDED, PARTIAL_REFUND, AFTERSALE);

    /** Raw projection columns the derivation reads; refund fields may be null/zero. */
    public record Facts(String orderStage, Long refundOrderId, BigDecimal refundedAmount,
            BigDecimal payAmount, String refundApplicationStatus, String aftersaleStatus) {}

    /** Baseline §5; an unparsable fact set is an integrity failure, never a mislabeled tab. */
    public static String compute(Facts facts) {
        if (facts == null || facts.orderStage() == null || !STAGES.contains(facts.orderStage())) {
            throw new IllegalStateException("unreadable order stage projection");
        }
        if (facts.refundOrderId() != null) {
            BigDecimal refunded = facts.refundedAmount();
            if (refunded == null || refunded.signum() <= 0) {
                return REFUNDING; // steps 1-2 fail, step 3: created/processing/unknown in flight
            }
            BigDecimal pay = facts.payAmount();
            if (pay == null || pay.signum() <= 0 || refunded.compareTo(pay) >= 0) {
                return REFUNDED; // step 1: full success returned everything paid
            }
            return PARTIAL_REFUND; // step 2: partial success
        }
        if ("PENDING_MERCHANT".equals(facts.refundApplicationStatus())) {
            return REFUND_PENDING_CONFIRM; // step 4
        }
        if (facts.aftersaleStatus() != null && ACTIVE_AFTERSALE.contains(facts.aftersaleStatus())) {
            return AFTERSALE; // step 5
        }
        return facts.orderStage(); // steps 6-10 share the display names
    }
}
