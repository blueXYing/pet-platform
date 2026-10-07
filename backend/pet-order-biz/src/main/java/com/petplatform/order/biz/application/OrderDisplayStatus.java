package com.petplatform.order.biz.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>{@link #sqlPredicate(String)} exposes, for each display value, the fixed SQL predicate
 * equivalent of the same truth table so the list filter and this derivation cannot disagree
 * silently; the MySQL acceptance test cross-checks them on seeded rows. Predicates are a fixed
 * whitelist here — never built from request input.</p>
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

    /** Fixed SQL predicates mirroring {@link #compute}; the whitelist order matches VALUES. */
    private static final Map<String, String> PREDICATES = buildPredicates();

    private static Map<String, String> buildPredicates() {
        String noRefund = "refund_order_id IS NULL";
        String applicationCleared = "(refund_application_status IS NULL OR refund_application_status <> 'PENDING_MERCHANT')";
        String aftersaleCleared = "(aftersale_status IS NULL OR aftersale_status NOT IN ('PENDING','PROCESSING','WAITING_SUPPLEMENT'))";
        Map<String, String> map = new LinkedHashMap<>();
        for (String stage : List.of(PENDING_PAYMENT, PENDING_CONFIRM, PENDING_SERVICE, COMPLETED, CANCELED)) {
            map.put(stage, noRefund + " AND " + applicationCleared + " AND " + aftersaleCleared
                    + " AND order_stage = '" + stage + "'");
        }
        map.put(REFUND_PENDING_CONFIRM, noRefund + " AND refund_application_status = 'PENDING_MERCHANT'");
        map.put(REFUNDING, "refund_order_id IS NOT NULL AND refunded_amount = 0");
        map.put(REFUNDED, "refund_order_id IS NOT NULL AND refunded_amount > 0 AND pay_amount > 0"
                + " AND refunded_amount >= pay_amount");
        map.put(PARTIAL_REFUND, "refund_order_id IS NOT NULL AND refunded_amount > 0 AND pay_amount > 0"
                + " AND refunded_amount < pay_amount");
        map.put(AFTERSALE, noRefund + " AND " + applicationCleared
                + " AND aftersale_status IN ('PENDING','PROCESSING','WAITING_SUPPLEMENT')");
        return Map.copyOf(map);
    }

    /**
     * The SQL predicate selecting exactly the rows {@link #compute} labels with the given
     * display value, or null when the value is not one of the ten (callers reject input first).
     * Fixed strings from the whitelist above — request values never reach SQL text.
     */
    public static String sqlPredicate(String displayStatus) {
        return displayStatus == null ? null : PREDICATES.get(displayStatus);
    }
}
