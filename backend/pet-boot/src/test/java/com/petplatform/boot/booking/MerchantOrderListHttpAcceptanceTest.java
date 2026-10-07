package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.merchant.MerchantOrderController;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Merchant order list read-side acceptance (contract 10 §4.1 supplement, switch
 * pet.order.merchant.http.enabled default OFF): the OWNER list route over real loopback HTTP,
 * real MINIAPP sessions, isolated MySQL and Redis. Proves the merchant-coordinate scope
 * (cross-store isolation, non-OWNER and unknown coordinates share one 403 anti-enumeration
 * answer), the displayStatus filter (same domain truth table as §3.7), the stable
 * created_at DESC/id DESC paging, the minimal summary projection and the switch-off boundary.
 * The list→process integration hands an orderId to the contract-45 confirm route.
 */
class MerchantOrderListHttpAcceptanceTest {

    /** Lakala trade_no is capped at 32 chars; keep the QA trade number short. */
    private static final java.util.concurrent.atomic.AtomicLong TRADE = new java.util.concurrent.atomic.AtomicLong();

    /** Same composition as the contract-45 HTTP slice: kernel + http switch + forced workers. */
    static final class Http implements AutoCloseable {
        final AfterSaleHttpFixture f;
        /** The contract-45 acceptance fixture one level down (paid bookings + payment chain). */
        final MerchantOrderAcceptanceTest.F orders;
        final PaymentFoundationAcceptanceTest.Fixture payments;

        Http() throws Exception {
            f = new AfterSaleHttpFixture(Map.of(
                    "pet.order.merchant.http.enabled", true,
                    "pet.order.merchant.worker.enabled", true,
                    "pet.order.auto-confirm.worker.enabled", true));
            orders = f.ordinary.t.r.f;
            payments = orders.f;
        }

        /** A paid PENDING_CONFIRM round-0 order on its own window (default window holds one). */
        String paidOnWindow(long windowId, String start) throws Exception {
            OffsetDateTime begin = OffsetDateTime.parse(start);
            String order = payments.book(new CreateOrderCommand(new CommandContext(
                    UUID.randomUUID().toString(), "pay-qa-create", OperatorType.USER, "710100", "MINIAPP"),
                    "710302", "710401", "710200", "IN_STORE", begin, begin.plusMinutes(90),
                    null, null, Long.toString(windowId), null, null, null, null, null)).orderId();
            var prepared = payments.prepare(order, UUID.randomUUID().toString());
            var notice = payments.notice(prepared, "SUCCESS", "MERCHANT_QA" + TRADE.incrementAndGet(),
                    prepared.amount(), prepared.amount());
            payments.notification.receive(notice.headers(), notice.body());
            payments.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(payments, prepared.paymentId()));
            return order;
        }

        /** Three PENDING_CONFIRM orders: default window plus two fresh single-capacity windows. */
        List<String> threePendingOrders() throws Exception {
            // Pull the pinned DB clock near the real wall clock BEFORE any booking: each order's
            // auto-confirm task is eligible when DB time passes paidAt+30min, and with the
            // constructor's 2030 pin the assembled worker would confirm them mid-setup.
            f.at(java.time.Instant.now().minusSeconds(300));
            f.sql("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                    + " VALUES(710591,710301,710302,710401,'2030-01-01 11:00:00','2030-01-01 13:30:00',1,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            f.sql("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                    + " VALUES(710592,710301,710302,710401,'2030-01-01 14:00:00','2030-01-01 16:30:00',1,'OPEN','GENERAL',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            // The seeded staff availability ends at 14:00; extend the same staff so the third
            // booking (14:30-16:00) keeps its staff coverage through the capacity proof.
            f.sql("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,created_at,updated_at)"
                    + " VALUES(710595,710302,710303,'2030-01-01 14:00:00','2030-01-01 18:00:00','AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            String first = orders.paid();
            String second = paidOnWindow(710591, "2030-01-01T11:30:00Z");
            String third = paidOnWindow(710592, "2030-01-01T14:30:00Z");
            return List.of(first, second, third);
        }

        public void close() {
            f.close();
        }
    }

    private static String listQuery(String merchant, String store, String display, int page, int size) {
        StringBuilder url = new StringBuilder("/merchant/orders?merchantId=").append(merchant)
                .append("&storeId=").append(store);
        if (display != null) url.append("&displayStatus=").append(display);
        url.append("&page=").append(page).append("&pageSize=").append(size);
        return url.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(AfterSaleHttpFixture.Reply reply) {
        return (List<Map<String, Object>>) reply.data().get("items");
    }

    private static void assertSummaryShape(Map<String, Object> item) {
        assertEquals(Set.of("orderId", "orderNo", "displayStatus", "payAmount",
                "appointmentStart", "appointmentEnd", "paidAt"), item.keySet(), item.toString());
        assertTrue(((String) item.get("payAmount")).matches("^(0|[1-9][0-9]{0,15})\\.[0-9]{2}$"),
                item.get("payAmount").toString());
    }

    @Test
    void ownerSeesOnlyOwnStoreOrdersWithStablePagingAndFilter() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            var orders = http.threePendingOrders();
            // Isolation setup: a second store of the same merchant holds no orders.
            f.sql("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,created_at,updated_at)"
                    + " VALUES(710312,710301,'QA 第二门店','QA address','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");

            // Unknown session first: the exact list path is bearer-protected.
            assertEquals(401, f.send("GET", listQuery("710301", "710302", null, 1, 20), (String) null, (String) null).status());

            var all = f.send("GET", listQuery("710301", "710302", null, 1, 20), (String) null, f.ownerToken);
            assertEquals(200, all.status(), all.toString());
            assertEquals("SUCCESS", all.envelope().get("code"));
            assertEquals("no-store", all.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(3, all.data().get("total"));
            assertEquals(20, all.data().get("pageSize"));
            var items = items(all);
            assertEquals(3, items.size());
            items.forEach(MerchantOrderListHttpAcceptanceTest::assertSummaryShape);
            // Stable sort created_at DESC, id DESC: newest booking first (equal pinned
            // created_at falls through to the id tiebreak).
            assertEquals(orders.get(2), items.get(0).get("orderId"));
            assertEquals(orders.get(1), items.get(1).get("orderId"));
            assertEquals(orders.get(0), items.get(2).get("orderId"));
            // The paid facts the merchant operates on are present verbatim.
            assertEquals("PENDING_CONFIRM", items.get(0).get("displayStatus"));
            assertNotNull(items.get(0).get("paidAt"));
            assertNotNull(items.get(0).get("appointmentStart"));
            assertNotNull(items.get(0).get("appointmentEnd"));

            // created_at dominates the id tiebreak: pushing the oldest order's created_at past
            // the others must move it to the head of the first page.
            f.sql("UPDATE pet_order SET created_at=created_at + INTERVAL 1 HOUR WHERE id=" + orders.get(0));
            var firstPage = f.send("GET", listQuery("710301", "710302", null, 1, 2), (String) null, f.ownerToken);
            assertEquals(3, firstPage.data().get("total"));
            assertEquals(List.of(orders.get(0), orders.get(2)),
                    items(firstPage).stream().map(item -> item.get("orderId")).toList());
            var secondPage = f.send("GET", listQuery("710301", "710302", null, 2, 2), (String) null, f.ownerToken);
            assertEquals(List.of(orders.get(1)),
                    items(secondPage).stream().map(item -> item.get("orderId")).toList());

            // The pending tab filters by the domain-computed display state.
            var pending = f.send("GET", listQuery("710301", "710302", "PENDING_CONFIRM", 1, 20), (String) null, f.ownerToken);
            assertEquals(3, pending.data().get("total"));
            assertTrue(items(pending).stream().allMatch(item -> "PENDING_CONFIRM".equals(item.get("displayStatus"))));
            var none = f.send("GET", listQuery("710301", "710302", "COMPLETED", 1, 20), (String) null, f.ownerToken);
            assertEquals(0, none.data().get("total"));
            assertEquals(List.of(), items(none));

            // Cross-store isolation: the sibling store of the same owner lists nothing.
            var sibling = f.send("GET", listQuery("710301", "710312", null, 1, 20), (String) null, f.ownerToken);
            assertEquals(200, sibling.status());
            assertEquals(0, sibling.data().get("total"));
            assertEquals(List.of(), items(sibling));

            // Malformed displayStatus is a 400 before any scope check.
            assertError(f.send("GET", listQuery("710301", "710302", "PENDING", 1, 20), (String) null, f.ownerToken),
                    400, "COMMON_INVALID_ARGUMENT");
        }
    }

    @Test
    void listHandsTheOrderToTheContract45ProcessRoute() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            var orders = http.threePendingOrders();
            String target = orders.get(2);

            var pending = f.send("GET", listQuery("710301", "710302", "PENDING_CONFIRM", 1, 20), (String) null, f.ownerToken);
            assertEquals(3, pending.data().get("total"));
            assertEquals(target, items(pending).get(0).get("orderId"));

            // The list entry point feeds the #129 confirm route; afterwards the pending tab shrinks.
            var confirmed = f.send("POST", "/merchant/orders/" + target + "/confirm",
                    Map.of("expectedConfirmRound", 0), bearer(f.ownerToken));
            assertEquals(200, confirmed.status(), confirmed.toString());

            var afterConfirm = f.send("GET", listQuery("710301", "710302", "PENDING_CONFIRM", 1, 20), (String) null, f.ownerToken);
            assertEquals(2, afterConfirm.data().get("total"));
            assertTrue(items(afterConfirm).stream().noneMatch(item -> target.equals(item.get("orderId"))));
            var stillThere = f.send("GET", listQuery("710301", "710302", null, 1, 20), (String) null, f.ownerToken);
            assertEquals(3, stillThere.data().get("total"));
            assertTrue(items(stillThere).stream().anyMatch(item -> target.equals(item.get("orderId"))
                    && "PENDING_SERVICE".equals(item.get("displayStatus"))));
        }
    }

    @Test
    void nonOwnerUnknownAndForeignCoordinatesShareOneForbiddenAnswer() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            http.threePendingOrders();

            // A fellow platform user (the buyer) is not the store OWNER.
            var buyer = f.send("GET", listQuery("710301", "710302", null, 1, 20), (String) null, f.buyerToken);
            assertError(buyer, 403, "COMMON_FORBIDDEN");
            // Unknown coordinates read exactly the same (anti-enumeration).
            var unknown = f.send("GET", listQuery("710399", "710399", null, 1, 20), (String) null, f.ownerToken);
            assertError(unknown, 403, "COMMON_FORBIDDEN");
            // A store id that exists but belongs to nobody's list scope is the same answer.
            var foreign = f.send("GET", listQuery("710301", "710313", null, 1, 20), (String) null, f.ownerToken);
            assertError(foreign, 403, "COMMON_FORBIDDEN");
            assertEquals(buyer.envelope().get("message"), unknown.envelope().get("message"));
            assertEquals(buyer.envelope().get("message"), foreign.envelope().get("message"));
        }
    }

    @Test
    void disabledHttpSwitchKeepsTheListRouteUnassembledAndDenied() throws Exception {
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
                    .noneMatch(route -> route.endsWith("/merchant/orders}")));
            assertEquals(403, f.send("GET", listQuery("710301", "710302", null, 1, 20), (String) null, f.ownerToken).status());
        }
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.toString());
        assertEquals(code, reply.envelope().get("code"));
        assertNull(reply.envelope().get("data"));
    }
}
