package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.c.CRefundApplicationController;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.refund.biz.application.RefundApplicationService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Refund application C HTTP face acceptance (contract 10 §3.9 + 49号, this slice): applyRefund
 * over real loopback HTTP, real MINIAPP sessions, isolated MySQL and Redis with the kernel
 * admission unchanged. Covers both §3.9 windows (pre-service auto full receipt, post-service
 * merchant 24h receipt), the five-tuple replay, the in-flight/eligibility/switch rejections,
 * the REJECTED-may-retry round, the refund_order→verification linkage, and default-off
 * assembly. Money never moves: the worker stays off and the channel is forbidden.
 */
class RefundApplyHttpAcceptanceTest {
    private static final String PATH(String order) { return "/c/orders/" + order + "/refund-applications"; }

    /** Extra capacity window + one paid+confirmed order with a future appointment start (11:30Z),
     * because the seeded 710500 window books 09:00Z — already at/past the fixture's parked clock. */
    private static String readyFutureOrder(AfterSaleHttpFixture f, long windowId) throws Exception {
        var fx = f.ordinary.t.r.f.f;
        fx.db.jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                + " VALUES(?,710301,710302,710401,'2030-01-01 11:00:00','2030-01-01 13:30:00',1,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", windowId);
        String order = fx.book(new CreateOrderCommand(new CommandContext(UUID.randomUUID().toString(), "refund-qa-http",
                OperatorType.USER, BUYER, "MINIAPP"), STORE, "710401", "710200", "IN_STORE",
                OffsetDateTime.parse("2030-01-01T11:30:00Z"), OffsetDateTime.parse("2030-01-01T13:00:00Z"),
                null, null, Long.toString(windowId), null, null, null, null, null)).orderId();
        var prepared = fx.prepare(order, UUID.randomUUID().toString());
        var notice = fx.notice(prepared, "SUCCESS", "MERCHANT_QA_HTTP_REFUND_" + windowId, prepared.amount(), prepared.amount());
        fx.notification.receive(notice.headers(), notice.body());
        fx.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(fx, prepared.paymentId()));
        f.ordinary.t.confirm(order, 0);
        return order;
    }

    /** Pre-service window (REF-001 switch on): the buyer's apply is the auto-full trigger. */
    @Test void preServiceAutoFullReceiptReplayConflictsAndRefundOrderLinkage() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.refund.application.http.enabled", true,
                "pet.refund.pre-service-auto-refund.enabled", true,
                // The §4.7 credential read rides its own http switch; the base fixture leaves it off.
                "pet.verification.credential.http.enabled", true))) {
            String order = readyFutureOrder(f, 710591L);
            String path = PATH(order);

            // Session boundary first: anonymous is filtered 401, another buyer and an unknown
            // order are the 49号 locate anti-enumeration 403; none leaves an application row.
            assertEquals(401, f.send("POST", path, body(), (String) null).status());
            assertError(f.send("POST", path, body(), f.otherToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("POST", PATH("900199999999999"), body(), f.buyerToken), 403, "COMMON_FORBIDDEN");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application"));

            // Transport shape: strict JSON, no query, terminal request id.
            assertError(f.sendRaw("POST", path, "{\"reasonCode\":\"QA_REASON\",\"extra\":1}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", path, "{\"reasonCode\":null}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", path, "{\"reasonCode\":\"QA_REASON\"}{\"reasonCode\":\"QA_REASON\"}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path + "?reasonCode=QA_REASON", body(), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path, Map.of("reasonCode", 7, "reasonText", "x"), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path, body(), Map.of("Authorization", "Bearer " + f.buyerToken, "X-Request-Id", "not-a-uuid")), 400, "COMMON_INVALID_ARGUMENT");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application"));

            // Pre-service success: 201, fixed first receipt projected to the §3.9 surface.
            String key = UUID.randomUUID().toString();
            var applied = f.send("POST", path, body(), headers(f.buyerToken, key));
            assertEquals(201, applied.status(), applied.toString());
            assertEquals("AUTO_APPROVED", applied.value("applicationStatus"));
            assertEquals("AUTO_FULL_BEFORE_SERVICE", applied.value("route"));
            assertEquals("REFUNDING", applied.value("displayStatus"));
            assertNull(applied.data().get("refundOrderId")); // durable REFUND_APPLICATION_CREATE task owns creation
            String applicationId = applied.value("applicationId");
            assertNotNull(applicationId);
            assertEquals("no-store, private", applied.headers().firstValue("Cache-Control").orElseThrow());

            // Kernel facts: SYSTEM decision in the same transaction, no merchant timeout task.
            assertEquals("AUTO_APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_MERCHANT_TIMEOUT'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));
            assertEquals(24L, f.count("SELECT TIMESTAMPDIFF(HOUR,created_at,merchant_deadline) FROM refund_application"));
            assertEquals("AUTO_APPROVED", f.text("SELECT refund_application_status FROM pet_order"));
            assertEquals(0, f.channelCalls.get());

            // Protected replay: same key + same params re-proves the session and answers 200/first receipt.
            var replay = f.send("POST", path, body(), headers(f.buyerToken, key));
            assertEquals(200, replay.status());
            assertEquals(applied.data(), replay.data());
            // Same key, different params stays a 409 idempotency conflict without a second row.
            assertError(f.send("POST", path, Map.of("reasonCode", "QA_REASON", "reasonText", "changed"),
                    headers(f.buyerToken, key)), 409, "IDEMPOTENCY_KEY_CONFLICT");
            // A fresh key on the same order hits the one-live-application admission.
            assertError(f.send("POST", path, body(), f.buyerToken), 409, "REFUND_APPLICATION_ALREADY_PROCESSED");
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application"));

            // refund_order linkage: once the durable task creates it, verification is blocked and
            // new applications are rejected (49号: any-source refund_order excludes everything).
            // The wire receipt carries only the fixed public fields, so the decision id is read
            // back from the kernel's own table — never guessed or invented.
            var apps = f.context.getBean(RefundApplicationService.class);
            String decisionId = f.text("SELECT CAST(decision_id AS CHAR) FROM refund_application");
            String refundId = apps.createApproved(f.ordinary.create(new com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt(
                    order, applicationId, "AUTO_APPROVED", "1", null, null, decisionId)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(0, f.channelCalls.get());
            assertError(f.send("GET", "/c/orders/" + order + "/verification-code", null, f.buyerToken), 409, "VERIFICATION_BLOCKED_BY_REFUND");
            assertError(f.send("POST", path, body(), f.buyerToken), 409, "REFUND_ORDER_ALREADY_EXISTS");
        }
    }

    /** Post-service window: merchant confirmation with a 24h deadline; REJECTED may re-apply. */
    @Test void postServiceMerchantWindowReceiptAndRejectedRetry() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.application.http.enabled", true))) {
            String order = f.verifiedOrder();
            String path = PATH(order);

            var applied = f.send("POST", path, body(), f.buyerToken);
            assertEquals(201, applied.status(), applied.toString());
            assertEquals("PENDING_MERCHANT", applied.value("applicationStatus"));
            assertEquals("MERCHANT_CONFIRM_AFTER_SERVICE", applied.value("route"));
            assertEquals("REFUND_PENDING_CONFIRM", applied.value("displayStatus"));
            assertNull(applied.data().get("refundOrderId"));
            // The wire deadline is the stored merchant_deadline at millisecond precision (UTC).
            assertEquals(f.jdbc.queryForObject("SELECT merchant_deadline FROM refund_application", java.sql.Timestamp.class).toInstant(),
                    java.time.Instant.parse(applied.value("merchantDeadline")));
            assertEquals(24L, f.count("SELECT TIMESTAMPDIFF(HOUR,created_at,merchant_deadline) FROM refund_application"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_MERCHANT_TIMEOUT'"));
            String first = applied.value("applicationId");

            // In-flight admission: a second live application is rejected before any new row.
            assertError(f.send("POST", path, body(), f.buyerToken), 409, "REFUND_APPLICATION_ALREADY_PROCESSED");
            // Merchant rejection frees the retry with a new row, new id and a fresh 24h deadline.
            // The HTTP receipt exposes only the public projection, so the decide command reuses
            // the application id from the wire plus the kernel's own stored version zero.
            var apps = f.context.getBean(RefundApplicationService.class);
            f.as(f.ownerToken);
            var decided = apps.decide(f.ordinary.decision(new com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt(
                    order, first, "PENDING_MERCHANT", "0", null, null, null), "REJECT"));
            assertEquals("REJECTED", decided.applicationStatus());
            var retried = f.send("POST", path, body(), f.buyerToken);
            assertEquals(201, retried.status(), retried.toString());
            assertEquals("PENDING_MERCHANT", retried.value("applicationStatus"));
            assertNotEquals(first, retried.value("applicationId"));
            assertEquals(2, f.count("SELECT COUNT(*) FROM refund_application"));
            assertEquals("PENDING_MERCHANT", f.text("SELECT refund_application_status FROM pet_order"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }

    /** Fail-closed admission: outside both windows is 409; the unflagged pre-service route is 503. */
    @Test void outOfWindowAdmissionAndSwitchOffPreServiceStayFailClosed() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.application.http.enabled", true))) {
            // Paid but not yet merchant-confirmed: neither §3.9 window pair — 49号 REFUND_NOT_ELIGIBLE.
            String unconfirmed = f.ordinary.t.r.f.paid();
            assertError(f.send("POST", PATH(unconfirmed), body(), f.buyerToken), 409, "REFUND_NOT_ELIGIBLE");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application"));

            // A confirmed pre-service order on the default (switch off) deployment keeps the
            // historical stub refusal instead of inventing the auto-full flow.
            String order = readyFutureOrder(f, 710592L);
            assertError(f.send("POST", PATH(order), body(), f.buyerToken), 503, "REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application"));
        }
    }

    /** Default off: without the http flag neither the controller nor the route is registered. */
    @Test void defaultOffRegistersNoControllerOrRoute() throws Exception {
        try (var f = new AfterSaleHttpFixture()) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(CRefundApplicationController.class).isEmpty());
            assertEquals(403, f.send("POST", "/c/orders/1/refund-applications", body(), f.buyerToken).status());
        }
    }

    private static Map<String, Object> body() { return Map.of("reasonCode", "QA_REASON", "reasonText", "临时无法到店"); }

    private static Map<String, String> headers(String token, String requestId) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply + " message=" + reply.envelope().get("message"));
        assertEquals(code, reply.envelope().get("code"), reply + " message=" + reply.envelope().get("message"));
        assertNull(reply.envelope().get("data"));
    }
}
