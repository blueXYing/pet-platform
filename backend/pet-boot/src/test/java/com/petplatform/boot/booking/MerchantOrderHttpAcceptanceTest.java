package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.merchant.MerchantOrderController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Merchant manual confirm/reject HTTP face acceptance (contract 45 via contract 10 §4.2/§4.3,
 * switch pet.order.merchant.http.enabled default OFF): the two OWNER routes over real loopback
 * HTTP, real MINIAPP sessions, isolated MySQL and Redis. The kernel transactions, the 30-minute
 * deadline race against the automatic task and the refund projection are covered by
 * MerchantOrderAcceptanceTest; here the wire proves the security-chain opening, the envelope,
 * the X-Request-Id replay and the fail-closed boundaries (non-owner 403, unknown order 403,
 * state/deadline 409, malformed bodies 400, switch-off denied).
 */
class MerchantOrderHttpAcceptanceTest {

    /** The aftersale HTTP composition plus the merchant order http switch (validation forces
     * both refund/auto-confirm workers on; the auto-confirm tasks of these fixtures stay in
     * the future so the workers never race the manual decisions asserted here). */
    static final class Http implements AutoCloseable {
        final AfterSaleHttpFixture f;

        Http() throws Exception {
            f = new AfterSaleHttpFixture(Map.of(
                    "pet.order.merchant.http.enabled", true,
                    "pet.order.merchant.worker.enabled", true,
                    "pet.order.auto-confirm.worker.enabled", true));
        }

        /** A paid PENDING_CONFIRM round-0 order of the fixture owner (deadline now+30min).
         *  The fixture's DataSource pins every DB session to its fixed clock (2030), while the
         *  kernel payment chain signs the receipt with the real wall clock — so after booking
         *  the clock is pulled back to just before the real deadline: the manual decision's
         *  lock-internal DB time then sits inside paidAt+30min, and the assembled auto-confirm
         *  worker (real clock, task scheduled at the future deadline) never races it. */
        String pendingOrder() throws Exception {
            String order = f.ordinary.t.r.f.paid();
            f.at(java.time.Instant.now().minusSeconds(60));
            return order;
        }

        public void close() {
            f.close();
        }
    }

    private static Map<String, String> owner(String requestId) {
        return Map.of("X-Request-Id", requestId);
    }

    private static Map<String, String> owner(String requestId, String token) {
        return Map.of("X-Request-Id", requestId, "Authorization", "Bearer " + token);
    }

    @Test
    void ownerConfirmReturnsEnvelopeReceiptAndReplaysTheFirstDecision() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.pendingOrder();
            String key = UUID.randomUUID().toString();

            assertEquals(401, f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0), (String) null).status());

            var first = f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0, "internalNote", "店内备注 QA"), owner(key, f.ownerToken));
            assertEquals(200, first.status(), first.toString());
            assertEquals("SUCCESS", first.envelope().get("code"));
            assertEquals(Boolean.TRUE, first.envelope().get("success"));
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(order, first.value("orderId"));
            assertEquals("0", first.value("confirmRound"));
            assertEquals("CONFIRM", first.value("action"));
            assertEquals("PENDING_SERVICE", first.value("orderStageAtCommit"));
            assertNull(first.data().get("refundOrderId"));
            assertNotNull(first.value("decisionId"));
            assertNotNull(first.value("decidedAt"));

            // Same X-Request-Id replays the persisted first receipt, not a second decision.
            var replay = f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0, "internalNote", "店内备注 QA"), owner(key, f.ownerToken));
            assertEquals(200, replay.status(), replay.toString());
            assertEquals(first.value("decisionId"), replay.value("decisionId"));
            assertEquals(first.value("decidedAt"), replay.value("decidedAt"));

            // A different key on the decided order is a plain state conflict.
            var again = f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0), owner(UUID.randomUUID().toString(), f.ownerToken));
            assertError(again, 409, "ORDER_STATE_NOT_ALLOWED");

            assertEquals("PENDING_SERVICE", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals("MERCHANT", f.text("SELECT confirm_mode FROM pet_order WHERE id=" + order));
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_merchant_decision WHERE order_id=" + order));
        }
    }

    @Test
    void ownerRejectCreatesFullRefundAtomicallyAndBindsReasonRules() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.pendingOrder();

            var reply = f.send("POST", "/merchant/orders/" + order + "/reject",
                    Map.of("expectedConfirmRound", 0, "reasonCode", "STAFF_UNAVAILABLE",
                            "reasonText", "当日人员不足无法履约"),
                    owner(UUID.randomUUID().toString(), f.ownerToken));
            assertEquals(200, reply.status(), reply.toString());
            assertEquals(order, reply.value("orderId"));
            assertEquals("REJECT", reply.value("action"));
            assertEquals("CANCELED", reply.value("orderStageAtCommit"));
            String refund = reply.value("refundOrderId");
            assertNotNull(refund);
            assertEquals("no-store", reply.headers().firstValue("Cache-Control").orElseThrow());

            assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals("MERCHANT_REJECT_ORDER", f.text("SELECT cancel_reason FROM pet_order WHERE id=" + order));
            assertEquals("MERCHANT_REJECT_ORDER", f.text(
                    "SELECT source_type FROM refund_order WHERE id=" + refund));
            assertEquals("CONFIRMED", f.text(
                    "SELECT status FROM schedule_reservation WHERE order_id=" + order));
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_merchant_decision WHERE order_id=" + order));

            // Reason surface: five frozen codes, 5..200 code points, no unknown/duplicate fields.
            // These are static validations (controller strict-JSON and the service's validate())
            // that run before any order lookup, so a synthetic id exercises them without
            // booking another order — the seeded window holds a single reservation.
            String synthetic = "9007199254740991";
            for (var body : List.of(
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER"),
                    Map.of("expectedConfirmRound", 0, "reasonCode", "NEW_REASON", "reasonText", "valid reason text"),
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "1234"),
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "   "),
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "a".repeat(201)),
                    Map.of("expectedConfirmRound", 2, "reasonCode", "OTHER", "reasonText", "valid reason text"),
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "valid reason text",
                            "internalNote", "never mixed"))) {
                assertError(f.send("POST", "/merchant/orders/" + synthetic + "/reject", body,
                        owner(UUID.randomUUID().toString(), f.ownerToken)), 400, "COMMON_INVALID_ARGUMENT");
            }
            // The malformed bodies never reached durable admission: the fixture holds only
            // the one command row of the successful reject above (bindings carry no order id).
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_merchant_command"));
            // All five approved codes stay kernel-covered end to end
            // (MerchantOrderAcceptanceTest.allApprovedReasonsCreateOneFullRefund...);
            // this wire proof uses STAFF_UNAVAILABLE above.
        }
    }

    @Test
    void nonOwnerAndUnknownOrdersFailClosedWithoutEnumeration() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.pendingOrder();
            Map<String, Object> body = Map.of("expectedConfirmRound", 0);

            // Another MINIAPP session never reaches the command.
            assertError(f.send("POST", "/merchant/orders/" + order + "/confirm", body,
                    owner(UUID.randomUUID().toString(), f.otherToken)), 403, "COMMON_FORBIDDEN");
            // Unknown and foreign orders read exactly like this — no 404 oracle.
            for (String unknown : List.of("9007199254740993", "123")) {
                assertError(f.send("POST", "/merchant/orders/" + unknown + "/confirm", body,
                        owner(UUID.randomUUID().toString(), f.ownerToken)), 403, "COMMON_FORBIDDEN");
                assertError(f.send("POST", "/merchant/orders/" + unknown + "/reject",
                        Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "unknown order"),
                        owner(UUID.randomUUID().toString(), f.ownerToken)), 403, "COMMON_FORBIDDEN");
            }
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_merchant_command"));
            assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
        }
    }

    @Test
    void pastDeadlineIsRejectedOverHttpWhileTheScheduledTaskStaysEligible() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            // A normally paid order (real deadline now+30min keeps the assembled auto-confirm
            // worker ineligible), examined with the fixture clock pinned just past the cutoff.
            // OrderConfirmEpoch pins confirmDeadline=paidAt+30min as stored values, so the
            // wire deadline face is proven by moving the clock, never the stored epoch.
            String order = f.ordinary.t.r.f.paid();
            f.at(java.time.Instant.now().plusSeconds(35 * 60));
            Map<String, Object> body = Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER",
                    "reasonText", "排队越界拒绝 QA");
            assertError(f.send("POST", "/merchant/orders/" + order + "/reject", body,
                    owner(UUID.randomUUID().toString(), f.ownerToken)), 409, "ORDER_CONFIRM_DEADLINE_PASSED");
            assertError(f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0), owner(UUID.randomUUID().toString(), f.ownerToken)),
                    409, "ORDER_CONFIRM_DEADLINE_PASSED");
            // Nothing was decided by either rejected command.
            assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_merchant_decision WHERE order_id=" + order));
        }
    }

    @Test
    void overdueOrderIsAutoConfirmedByTheWorkerAndManualDecisionsThenConflict() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            // Overdue signing (paidAt one hour back): the deadline is genuinely past for the
            // real-clock auto-confirm worker assembled with this slice, so waiting for its
            // decision is deterministic — no race with the manual route under test.
            String order = f.ordinary.t.r.f.paid(true);
            assertTrue(awaitAutoConfirmed(f, order), "the assembled auto-confirm worker must confirm the overdue order");
            assertEquals("AUTO", f.text("SELECT confirm_mode FROM pet_order WHERE id=" + order));
            assertEquals("PENDING_SERVICE", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order WHERE order_id=" + order));
            assertError(f.send("POST", "/merchant/orders/" + order + "/confirm",
                    Map.of("expectedConfirmRound", 0), owner(UUID.randomUUID().toString(), f.ownerToken)),
                    409, "ORDER_STATE_NOT_ALLOWED");
            assertError(f.send("POST", "/merchant/orders/" + order + "/reject",
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "自动接单后拒单 QA"),
                    owner(UUID.randomUUID().toString(), f.ownerToken)), 409, "ORDER_STATE_NOT_ALLOWED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order WHERE order_id=" + order));
        }
    }

    /** The worker polls every second; the overdue task is immediately eligible. */
    private static boolean awaitAutoConfirmed(AfterSaleHttpFixture f, String order) throws Exception {
        for (int i = 0; i < 100; i++) {
            if ("AUTO".equals(f.text("SELECT COALESCE(MAX(confirm_mode),'') FROM pet_order WHERE id=" + order))) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    @Test
    void disabledHttpSwitchKeepsRoutesUnassembledAndDenied() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantOrderController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantOrderController.class).isEmpty());
                });
        // Kernel stays on, only the http switch off: no controller, no route, requests denied.
        try (var f = new AfterSaleHttpFixture()) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(MerchantOrderController.class).isEmpty());
            assertTrue(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                    .getHandlerMethods().keySet().stream()
                    .map(Object::toString)
                    .noneMatch(route -> route.endsWith("/confirm}") || route.endsWith("/reject}")));
            assertEquals(403, f.send("POST", "/merchant/orders/1/confirm",
                    Map.of("expectedConfirmRound", 0), f.ownerToken).status());
            assertEquals(403, f.send("POST", "/merchant/orders/1/reject",
                    Map.of("expectedConfirmRound", 0, "reasonCode", "OTHER", "reasonText", "switch off QA"),
                    f.ownerToken).status());
        }
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.toString());
        assertEquals(code, reply.envelope().get("code"));
        assertNull(reply.envelope().get("data"));
    }
}
