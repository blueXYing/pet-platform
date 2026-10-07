package com.petplatform.order.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tech baseline §5 truth table for the single order-domain DisplayOrderStatus computation,
 * plus the fixed SQL filter whitelist that mirrors it (cross-checked against real rows by the
 * pet-boot MySQL acceptance test).
 */
class OrderDisplayStatusTest {

    private static final BigDecimal PAY = new BigDecimal("128.00");
    private static final BigDecimal FULL = new BigDecimal("128.00");
    private static final BigDecimal PART = new BigDecimal("50.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static String compute(String stage, String refundOrderId, BigDecimal refunded,
            BigDecimal pay, String application, String aftersale) {
        return OrderDisplayStatus.compute(new OrderDisplayStatus.Facts(
                stage, refundOrderId == null ? null : 1L, refunded, pay, application, aftersale));
    }

    @Test
    void refundFactsWinOverEveryOtherDimension() {
        // Step 1: full success even while an application or aftersale is still "active".
        assertEquals("REFUNDED", compute("COMPLETED", "601", FULL, PAY, "PENDING_MERCHANT", "PROCESSING"));
        // Step 2: partial success.
        assertEquals("PARTIAL_REFUND", compute("COMPLETED", "601", PART, PAY, null, null));
        assertEquals("PARTIAL_REFUND", compute("PENDING_SERVICE", "601", PART, PAY, "APPROVED", null));
        // Step 3: refund order exists, no projected success yet.
        assertEquals("REFUNDING", compute("CANCELED", "601", ZERO, PAY, "AUTO_APPROVED", "RESOLVED"));
        assertEquals("REFUNDING", compute("PENDING_SERVICE", "601", null, PAY, null, null));
    }

    @Test
    void applicationThenAftersaleThenStagePriority() {
        // Step 4 beats step 5 beats the stage.
        assertEquals("REFUND_PENDING_CONFIRM",
                compute("PENDING_PAYMENT", null, ZERO, PAY, "PENDING_MERCHANT", "PROCESSING"));
        // Step 5: only active aftersale projections count.
        assertEquals("AFTERSALE", compute("PENDING_SERVICE", null, ZERO, PAY, "REJECTED", "PROCESSING"));
        assertEquals("AFTERSALE", compute("COMPLETED", null, ZERO, PAY, null, "WAITING_SUPPLEMENT"));
        assertEquals("AFTERSALE", compute("PENDING_SERVICE", null, ZERO, PAY, null, "PENDING"));
        // Closed/withdrawn/resolved aftersale falls through to the stage.
        assertEquals("PENDING_SERVICE", compute("PENDING_SERVICE", null, ZERO, PAY, null, "WITHDRAWN"));
        assertEquals("COMPLETED", compute("COMPLETED", null, ZERO, PAY, null, "RESOLVED"));
        assertEquals("CANCELED", compute("CANCELED", null, ZERO, PAY, null, "INVALIDATED"));
        assertEquals("CANCELED", compute("CANCELED", null, ZERO, PAY, null, "CLOSED"));
    }

    @Test
    void stagesProjectVerbatimWhenNothingElseApplies() {
        for (String stage : List.of("PENDING_PAYMENT", "PENDING_CONFIRM", "PENDING_SERVICE", "COMPLETED", "CANCELED")) {
            assertEquals(stage, compute(stage, null, ZERO, PAY, null, null), stage);
            assertEquals(stage, compute(stage, null, ZERO, PAY, "REJECTED", null), stage);
        }
    }

    @Test
    void unreadableFactsAreIntegrityFailuresNeverAMislabeledTab() {
        assertThrows(IllegalStateException.class,
                () -> compute("WEIRD", null, ZERO, PAY, null, null));
        assertThrows(IllegalStateException.class,
                () -> compute(null, null, ZERO, PAY, null, null));
        assertThrows(IllegalStateException.class,
                () -> OrderDisplayStatus.compute(null));
    }

    @Test
    void vocabularyIsExactlyTheTenDisplayTabs() {
        // The selectable vocabulary; the mapper's XML filter branches mirror the same truth
        // table and the MySQL acceptance test cross-checks both on real rows.
        assertEquals(List.of(
                "PENDING_PAYMENT", "PENDING_CONFIRM", "PENDING_SERVICE", "COMPLETED", "CANCELED",
                "REFUND_PENDING_CONFIRM", "REFUNDING", "REFUNDED", "PARTIAL_REFUND", "AFTERSALE"),
                OrderDisplayStatus.VALUES);
        assertEquals(10, OrderDisplayStatus.VALUES.size());
    }
}
