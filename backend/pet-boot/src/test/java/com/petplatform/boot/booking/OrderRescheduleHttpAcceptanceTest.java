package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.c.COrderRescheduleController;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Reschedule C HTTP face acceptance (contract 10 §3.8 + 46号 R1/R2/R3, this slice): rescheduleOrder
 * over real loopback HTTP, real MINIAPP sessions, isolated MySQL and Redis with the kernel
 * admission, swap, task and fence unchanged. Covers the 46号 first receipt, the protected replay
 * and idempotency conflict, the single-chance/CAS/unchanged-interval/capacity/verified/refunded
 * rejections, the real credential-fence invalidation (the #114/#116 precondition this route
 * waited for), the §3.7 read-side version amendment that feeds expectedOrderVersion, and the
 * default-off assembly. No worker runs and no money moves.
 */
class OrderRescheduleHttpAcceptanceTest {
    private static final String NEW_WINDOW = "710593";

    private static String PATH(String order) { return "/c/orders/" + order + "/reschedule"; }

    private static Map<String, Object> overrides() {
        return Map.of("pet.order.reschedule.enabled", true, "pet.order.reschedule.http.enabled", true,
                // The §4.7 credential routes ride their own http switch; the base fixture leaves it off.
                "pet.verification.credential.http.enabled", true);
    }

    /** A paid and merchant-confirmed PENDING_SERVICE order (original appointment 11:30Z, future). */
    private static String paidOrder(AfterSaleHttpFixture f, long windowId) throws Exception {
        var fx = f.ordinary.t.r.f.f;
        fx.db.jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                + " VALUES(?,710301,710302,710401,'2030-01-01 11:00:00','2030-01-01 13:30:00',1,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", windowId);
        String order = fx.book(new CreateOrderCommand(new CommandContext(UUID.randomUUID().toString(), "reschedule-qa-http",
                OperatorType.USER, BUYER, "MINIAPP"), STORE, "710401", "710200", "IN_STORE",
                OffsetDateTime.parse("2030-01-01T11:30:00Z"), OffsetDateTime.parse("2030-01-01T13:00:00Z"),
                null, null, Long.toString(windowId), null, null, null, null, null)).orderId();
        var prepared = fx.prepare(order, UUID.randomUUID().toString());
        // Channel trade_no is bounded at 32 chars (Lakala protocol); keep the QA tag short.
        var notice = fx.notice(prepared, "SUCCESS", "QA_HTTP_RESCH_" + windowId, prepared.amount(), prepared.amount());
        fx.notification.receive(notice.headers(), notice.body());
        fx.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(fx, prepared.paymentId()));
        f.ordinary.t.confirm(order, 0);
        return order;
    }

    /** The §3.7 read-side amendment this slice registers: version + coordinates via the detail GET. */
    private static String versionOf(AfterSaleHttpFixture f, String order) throws Exception {
        var detail = f.send("GET", "/c/orders/" + order, null, f.buyerToken);
        assertEquals(200, detail.status(), detail.toString());
        assertEquals(true, ((Map<?, ?>) detail.data().get("actions")).get("canReschedule"), detail.toString());
        assertNotNull(detail.value("orderVersion"), detail.toString());
        assertNotNull(detail.value("serviceId"), detail.toString());
        assertNotNull(detail.value("storeId"), detail.toString());
        return detail.value("orderVersion");
    }

    private static Map<String, Object> body(String version, String start, String end, String window) {
        return Map.of("expectedOrderVersion", version, "appointmentStart", start,
                "appointmentEnd", end, "selectedGeneralWindowId", window);
    }

    /** 46号 happy path: swap + fence + tasks + event in one transaction, then the replay. */
    @Test void firstRescheduleSwapsTheReservationInvalidatesTheRealCodeAndReplays() throws Exception {
        try (var f = new AfterSaleHttpFixture(overrides())) {
            fxWindow(f);
            String order = paidOrder(f, 710591L);
            String path = PATH(order);
            String version = versionOf(f, order);

            // Session boundary first: anonymous 401, a foreign buyer and an unknown order share the
            // locate anti-enumeration 403; nothing is written for any of them.
            assertEquals(401, f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), (String) null).status());
            assertError(f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.otherToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("POST", PATH("900199999999999"), body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 403, "COMMON_FORBIDDEN");
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_reschedule_record"));

            // Transport shape: strict JSON, no query, terminal request id.
            assertError(f.sendRaw("POST", path, "{\"expectedOrderVersion\":\"" + version + "\",\"appointmentStart\":\"2030-01-01T14:30:00Z\",\"appointmentEnd\":\"2030-01-01T16:00:00Z\",\"selectedGeneralWindowId\":\"710593\",\"extra\":1}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", path, "{\"expectedOrderVersion\":\"" + version + "\",\"pickupStart\":\"2030-01-01T14:30:00Z\"}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", path, "{\"expectedOrderVersion\":null,\"appointmentStart\":\"2030-01-01T14:30:00Z\",\"appointmentEnd\":\"2030-01-01T16:00:00Z\",\"selectedGeneralWindowId\":\"710593\"}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path + "?x=1", body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), Map.of("Authorization", "Bearer " + f.buyerToken, "X-Request-Id", "not-a-uuid")), 400, "COMMON_INVALID_ARGUMENT");

            // A live real credential exists before the swap (the fence has real work to do).
            var issued = f.send("POST", "/c/orders/" + order + "/verification-code",
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), f.buyerToken);
            assertEquals(200, issued.status(), issued.toString());
            assertEquals("ACTIVE", f.send("GET", "/c/orders/" + order + "/verification-code", null, f.buyerToken).value("status"));

            // First success: the fixed 46号 receipt, one swap, one fence, round-1 task, one event.
            String key = UUID.randomUUID().toString();
            var applied = f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), headers(f.buyerToken, key));
            assertEquals(200, applied.status(), applied.toString());
            assertEquals(order, applied.value("orderId"));
            assertEquals(f.text("SELECT CAST(reservation_id AS CHAR) FROM pet_order WHERE id=" + order), applied.value("reservationId"));
            assertEquals(1, ((Number) applied.data().get("confirmRound")).intValue());
            assertEquals(Long.toString(Long.parseLong(version) + 1), applied.value("orderVersion"));
            assertEquals("PENDING_CONFIRM", applied.value("orderStageAtCommit"));
            assertEquals("2030-01-01T14:30:00.000Z", applied.value("appointmentStart"));
            assertEquals("2030-01-01T16:00:00.000Z", applied.value("appointmentEnd"));
            assertNull(applied.data().get("pickupStart"));
            assertNull(applied.data().get("returnStart"));
            assertEquals("no-store, private", applied.headers().firstValue("Cache-Control").orElseThrow());
            // Deadline = the DB swap time + 30 minutes (46号 second round).
            assertEquals(30L, f.count("SELECT TIMESTAMPDIFF(MINUTE,rescheduled_at,new_confirm_deadline) FROM order_reschedule_record"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM schedule_reservation"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM schedule_reservation_change"));
            assertEquals(1, f.count("SELECT reschedule_count FROM pet_order"));
            assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals("CANCELED", f.text("SELECT status FROM async_task WHERE task_key LIKE 'ORDER_AUTO_CONFIRM:%:0'"));
            assertEquals("READY", f.text("SELECT status FROM async_task WHERE task_key LIKE 'ORDER_AUTO_CONFIRM:%:1'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderRescheduledEvent.v1'"));

            // The real fence: the pre-swap live code is INVALIDATED after the swap (R2 core).
            // The credential read needs the round-1 merchant confirmation first (the swap reset
            // the order to PENDING_CONFIRM, exactly the 46号 second round).
            f.ordinary.t.confirm(order, 1);
            assertEquals("INVALIDATED", f.send("GET", "/c/orders/" + order + "/verification-code", null, f.buyerToken).value("status"));
            // The read side re-gates the entry: one spent chance flips canReschedule off.
            assertEquals(false, ((Map<?, ?>) f.send("GET", "/c/orders/" + order, null, f.buyerToken).data().get("actions")).get("canReschedule"));

            // Protected replay: same key + same params answers the first receipt again.
            var replay = f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), headers(f.buyerToken, key));
            assertEquals(200, replay.status());
            assertEquals(applied.data(), replay.data());
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_reschedule_record"));
            // Same key, different params stays a 409 idempotency conflict without a second swap.
            assertError(f.send("POST", path, body(version, "2030-01-01T14:40:00Z", "2030-01-01T16:10:00Z", NEW_WINDOW), headers(f.buyerToken, key)), 409, "IDEMPOTENCY_KEY_CONFLICT");
            // A fresh key on the same order hits the 46号 single-chance admission.
            assertError(f.send("POST", path, body(applied.value("orderVersion"), "2030-01-01T14:40:00Z", "2030-01-01T16:10:00Z", NEW_WINDOW), f.buyerToken), 409, "ORDER_RESCHEDULE_LIMIT_REACHED");
            assertEquals(1, f.count("SELECT COUNT(*) FROM schedule_reservation_change"));
        }
    }

    /** Wrong version and unchanged intervals never consume the chance; refund_order blocks everything. */
    @Test void casAndUnchangedAndRefundedAdmissionsFailClosedWithoutSideEffects() throws Exception {
        try (var f = new AfterSaleHttpFixture(overrides())) {
            fxWindow(f);
            String order = paidOrder(f, 710591L);
            String path = PATH(order);
            String version = versionOf(f, order);
            // CAS: the version does not match the order row.
            assertError(f.send("POST", path, body(Long.toString(Long.parseLong(version) + 5), "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 409, "COMMON_CONFLICT");
            // No substantive interval change: 409 without consuming the only chance (46号).
            assertError(f.send("POST", path, body(version, "2030-01-01T11:30:00Z", "2030-01-01T13:00:00Z", "710591"), f.buyerToken), 409, "COMMON_CONFLICT");
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_reschedule_record"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM pet_order WHERE reschedule_count=1"));
            // Any refund_order blocks rescheduling (46号 / SSOT hard rule).
            f.sql("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,initiator_type,created_at,updated_at)"
                    + " VALUES(99,99," + order + ",'FULL','PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            assertError(f.send("POST", path, body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 409, "ORDER_REFUND_ALREADY_CREATED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_reschedule_record"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM pet_order WHERE reschedule_count=1"));
        }
    }

    /** A verified order is rejected by the kernel admission (own fixture: one order per database). */
    @Test void verifiedOrderIsRejectedByKernelAdmission() throws Exception {
        try (var f = new AfterSaleHttpFixture(overrides())) {
            fxWindow(f);
            String verified = f.verifiedOrder();
            String verifiedVersion = f.text("SELECT CAST(version AS CHAR) FROM pet_order WHERE id=" + verified);
            assertError(f.send("POST", PATH(verified), body(verifiedVersion, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 409, "ORDER_STATE_NOT_ALLOWED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_reschedule_record"));
        }
    }

    /** Capacity proof: a fully occupied new window keeps the original reservation untouched. */
    @Test void occupiedNewWindowFailsClosedAndKeepsTheOriginalClaim() throws Exception {
        try (var f = new AfterSaleHttpFixture(overrides())) {
            fxWindow(f);
            String order = paidOrder(f, 710591L);
            String version = versionOf(f, order);
            // Occupy the single capacity of the new window with another TEMP_LOCKED booking.
            var fx = f.ordinary.t.r.f.f;
            fx.book(new CreateOrderCommand(new CommandContext(UUID.randomUUID().toString(), "reschedule-qa-http",
                    OperatorType.USER, BUYER, "MINIAPP"), STORE, "710401", "710200", "IN_STORE",
                    OffsetDateTime.parse("2030-01-01T14:30:00Z"), OffsetDateTime.parse("2030-01-01T16:00:00Z"),
                    null, null, NEW_WINDOW, null, null, null, null, null));
            assertError(f.send("POST", PATH(order), body(version, "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken), 409, "SCHEDULE_CAPACITY_EXCEEDED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM schedule_reservation_change"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM pet_order WHERE reschedule_count=1"));
            assertEquals("2030-01-01 11:30:00.000", f.text("SELECT CAST(appointment_start_at AS CHAR) FROM pet_order WHERE id=" + order));
        }
    }

    /** Default off: without the http flag neither the controller nor the route is registered. */
    @Test void defaultOffRegistersNoControllerOrRoute() throws Exception {
        try (var f = new AfterSaleHttpFixture()) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(COrderRescheduleController.class).isEmpty());
            assertEquals(403, f.send("POST", "/c/orders/1/reschedule",
                    body("0", "2030-01-01T14:30:00Z", "2030-01-01T16:00:00Z", NEW_WINDOW), f.buyerToken).status());
        }
    }

    /** The one non-original window every success case reschedules into (14:00–16:30, capacity 1);
     * the seeded staff window ends 14:00, so it is stretched to cover the new interval too. */
    private static void fxWindow(AfterSaleHttpFixture f) {
        f.sql("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                + " VALUES(" + NEW_WINDOW + ",710301,710302,710401,'2030-01-01 14:00:00','2030-01-01 16:30:00',1,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        f.sql("UPDATE staff_availability_window SET end_at='2030-01-01 18:00:00'");
    }

    private static Map<String, String> headers(String token, String requestId) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply + " message=" + reply.envelope().get("message"));
        assertEquals(code, reply.envelope().get("code"), reply + " message=" + reply.envelope().get("message"));
        assertNull(reply.envelope().get("data"));
    }
}
