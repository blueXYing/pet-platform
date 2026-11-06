package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.refund.api.command.RefundApplicationTimeoutApi;
import com.petplatform.refund.biz.application.RefundApplicationService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * CCR-M-REFUND-VIEW-001 (approved 2026-11-06 §3.1/§3.3; §3.2 NOT approved) over real loopback
 * HTTP, real MINIAPP sessions and isolated MySQL/Redis; no controller/service mocks. The OWNER
 * list and detail project the buyer nickname, the user domain's server-masked phone and the
 * order-time service name (expected values read from the authoritative rows); the detail adds a
 * timeline derived only from persisted timestamps (pending: APPLIED+MERCHANT_DEADLINE; decided:
 * +DECIDED; bound refund order: +REFUND_ORDER_CREATED), ascending. Non-owner, foreign-store and
 * unknown reads keep the same bare 403 without any customer fact; blank/absent facts degrade to
 * explicit nulls; the raw phone, the buyer id and the sealed buyer reason text never appear.
 */
class MerchantRefundViewHttpAcceptanceTest {
    private static final List<String> CUSTOMER_KEYS = List.of("customerNickname", "customerPhoneMasked", "serviceName");
    private static final Set<String> LIST_KEYS = Set.of("applicationId", "applicationNo", "orderId", "status",
            "applicationVersion", "reasonCode", "refundAmount", "createdAt", "merchantDeadline",
            "customerNickname", "customerPhoneMasked", "serviceName");
    private static final Set<String> DETAIL_KEYS = Set.of("applicationId", "applicationNo", "orderId", "status",
            "applicationVersion", "reasonCode", "refundAmount", "createdAt", "merchantDeadline", "decidedAt",
            "decisionId", "refundOrderId", "customerNickname", "customerPhoneMasked", "serviceName", "timeline");
    /** RefundApplicationAcceptanceTest.F#applyCommand seals this buyer text (49号 cipher). */
    private static final String SEALED_REASON = "private refund reason";

    /** A verified (post-service) order with the buyer's PENDING_MERCHANT application applied. */
    private static String pendingApplication(AfterSaleHttpFixture f) throws Exception {
        String order = f.verifiedOrder();
        var app = f.context.getBean(RefundApplicationService.class);
        f.as(f.buyerToken);
        var applied = app.apply(f.ordinary.applyCommand(order));
        RequestContextHolder.resetRequestAttributes();
        assertEquals("PENDING_MERCHANT", applied.applicationStatus(), applied.toString());
        return applied.applicationId();
    }
    private static String query() { return "?merchantId=" + MERCHANT + "&storeId=" + STORE; }

    @Test void ownerListAndDetailProjectCustomerFactsAndAFactualAscendingTimeline() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String application = pendingApplication(f);
            String order = f.text("SELECT CAST(order_id AS CHAR) FROM refund_application");
            var facts = Facts.of(f, order);
            assertTrue(facts.masked().matches("^1[0-9]{2}\\*{4}[0-9]{4}$"), facts.masked());

            // List: the nine contract-57 keys plus the three customer keys, never a timeline.
            var list = f.send("GET", "/merchant/refund-applications" + query(), null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            var item = onlyItem(list);
            assertEquals(LIST_KEYS, item.keySet());
            assertEquals(application, item.get("applicationId"));
            facts.assertCustomer(item);
            facts.assertNoPrivateFacts(f, list);

            // Pending detail: exactly APPLIED + MERCHANT_DEADLINE, each its own persisted instant.
            var detail = f.send("GET", "/merchant/refund-applications/" + application + query(), null, f.ownerToken);
            assertEquals(200, detail.status(), detail.toString());
            assertEquals(DETAIL_KEYS, detail.data().keySet());
            facts.assertCustomer(detail.data());
            assertEquals(List.of(entry("APPLIED", detail.value("createdAt")), entry("MERCHANT_DEADLINE", detail.value("merchantDeadline"))),
                    timeline(detail));
            facts.assertNoPrivateFacts(f, detail);

            // Approve over HTTP: DECIDED appears at the persisted decision instant; no refund order yet.
            var approved = f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                    Map.of("X-Request-Id", UUID.randomUUID().toString(), "Authorization", "Bearer " + f.ownerToken));
            assertEquals(200, approved.status(), approved.toString());
            var decided = f.send("GET", "/merchant/refund-applications/" + application + query(), null, f.ownerToken);
            assertEquals(200, decided.status(), decided.toString());
            assertEquals(List.of("APPLIED", "DECIDED", "MERCHANT_DEADLINE"), stages(decided));
            assertEquals(approved.value("decidedAt"), decided.value("decidedAt"));
            assertEquals(decided.value("decidedAt"), timeline(decided).get(1).get("occurredAt"));
            assertAscending(decided);
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            facts.assertCustomer(decided.data());
            facts.assertNoPrivateFacts(f, decided);

            // The durable REFUND_APPLICATION_CREATE step (worker off, driven directly as the task
            // handler would) binds the refund order: REFUND_ORDER_CREATED is its own created_at.
            String refund = f.context.getBean(RefundApplicationService.class).createApproved(new RefundApplicationTimeoutApi.Create(
                    RefundApplicationAcceptanceTest.task("REFUND_APPLICATION_CREATE", application), application,
                    approved.value("decisionId"), STORE));
            var created = f.send("GET", "/merchant/refund-applications/" + application + query(), null, f.ownerToken);
            assertEquals(200, created.status(), created.toString());
            assertEquals(refund, created.value("refundOrderId"));
            assertEquals(List.of("APPLIED", "DECIDED", "REFUND_ORDER_CREATED", "MERCHANT_DEADLINE"), stages(created));
            assertEquals(databaseInstant(f.text("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM refund_order WHERE id=" + refund)),
                    Instant.parse((String) timeline(created).get(2).get("occurredAt")));
            assertAscending(created);
            facts.assertCustomer(created.data());
            facts.assertNoPrivateFacts(f, created);
            // The read projection moved no money and changed no decision semantics.
            assertEquals(0, f.channelCalls.get());
            assertEquals("APPROVED", created.value("status"));
            assertEquals("1", created.value("applicationVersion"));
        }
    }

    @Test void nonOwnerForeignAndUnknownReadsKeepTheBare403WithoutAnyCustomerFact() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String application = pendingApplication(f);
            var facts = Facts.of(f, f.text("SELECT CAST(order_id AS CHAR) FROM refund_application"));
            // Another user and the application's own buyer are not the store OWNER: same 403, no fact.
            for (String token : List.of(f.otherToken, f.buyerToken)) {
                var denied = f.send("GET", "/merchant/refund-applications/" + application + query(), null, token);
                assertEquals(403, denied.status(), denied.toString());
                facts.assertNoCustomer(f, denied);
                var page = f.send("GET", "/merchant/refund-applications" + query(), null, token);
                assertEquals(403, page.status(), page.toString());
                facts.assertNoCustomer(f, page);
            }
            // The real OWNER asking for unknown ids or a foreign store reads the same 403.
            for (String unknown : List.of("9007199254740993", "123")) {
                var absent = f.send("GET", "/merchant/refund-applications/" + unknown + query(), null, f.ownerToken);
                assertEquals(403, absent.status(), absent.toString());
                assertEquals("COMMON_FORBIDDEN", absent.code());
                facts.assertNoCustomer(f, absent);
            }
            var foreign = f.send("GET", "/merchant/refund-applications?merchantId=" + MERCHANT + "&storeId=9007199254740993", null, f.ownerToken);
            assertEquals(403, foreign.status(), foreign.toString());
            facts.assertNoCustomer(f, foreign);
            var foreignDetail = f.send("GET", "/merchant/refund-applications/" + application + "?merchantId=" + MERCHANT
                    + "&storeId=9007199254740993", null, f.ownerToken);
            assertEquals(403, foreignDetail.status(), foreignDetail.toString());
            facts.assertNoCustomer(f, foreignDetail);
        }
    }

    @Test void blankNicknameMissingPhoneAndMissingSnapshotDegradeToExplicitNulls() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String application = pendingApplication(f);
            String order = f.text("SELECT CAST(order_id AS CHAR) FROM refund_application");
            var facts = Facts.of(f, order);
            // A blank nickname normalizes to null; the other facts stay factual.
            f.sql("UPDATE user_account SET nickname='   ' WHERE id=" + BUYER);
            var detail = f.send("GET", "/merchant/refund-applications/" + application + query(), null, f.ownerToken);
            assertEquals(200, detail.status(), detail.toString());
            assertTrue(detail.data().containsKey("customerNickname"));
            assertNull(detail.data().get("customerNickname"));
            assertEquals(facts.masked(), detail.data().get("customerPhoneMasked"));
            assertEquals(facts.service(), detail.data().get("serviceName"));
            // No nickname, no bound phone and no service snapshot: every key present and null.
            f.sql("UPDATE user_account SET nickname=NULL, phone=NULL WHERE id=" + BUYER);
            f.sql("DELETE FROM order_service_snapshot WHERE order_id=" + order);
            var list = f.send("GET", "/merchant/refund-applications" + query(), null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            var item = onlyItem(list);
            for (String key : CUSTOMER_KEYS) {
                assertTrue(item.containsKey(key), key);
                assertNull(item.get(key), key);
            }
            assertEquals(application, item.get("applicationId"));
            var after = f.send("GET", "/merchant/refund-applications/" + application + query(), null, f.ownerToken);
            assertEquals(200, after.status(), after.toString());
            for (String key : CUSTOMER_KEYS) {
                assertTrue(after.data().containsKey(key), key);
                assertNull(after.data().get(key), key);
            }
            // Degraded customer facts never change the factual timeline.
            assertEquals(List.of("APPLIED", "MERCHANT_DEADLINE"), stages(after));
            facts.assertNoPrivateFacts(f, after);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyItem(AfterSaleHttpFixture.Reply page) {
        var items = (List<Map<String, Object>>) page.data().get("items");
        assertEquals(1, items.size());
        return items.get(0);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> timeline(AfterSaleHttpFixture.Reply reply) {
        return (List<Map<String, Object>>) reply.data().get("timeline");
    }
    private static List<Object> stages(AfterSaleHttpFixture.Reply reply) {
        return timeline(reply).stream().map(entry -> entry.get("stage")).toList();
    }
    private static Map<String, Object> entry(String stage, String occurredAt) {
        return Map.of("stage", stage, "occurredAt", occurredAt);
    }
    private static void assertAscending(AfterSaleHttpFixture.Reply reply) {
        Instant previous = null;
        for (var entry : timeline(reply)) {
            assertEquals(Set.of("stage", "occurredAt"), entry.keySet());
            String text = (String) entry.get("occurredAt");
            assertTrue(text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), text);
            Instant at = Instant.parse(text);
            if (previous != null) assertFalse(at.isBefore(previous), timeline(reply).toString());
            previous = at;
        }
    }
    private static Instant databaseInstant(String text) {
        return OffsetDateTime.parse(text.replace(',', '.')).toInstant();
    }

    /** Authoritative row facts: the buyer's nickname/raw phone and the order's service snapshot. */
    private record Facts(String nickname, String phone, String service) {
        static Facts of(AfterSaleHttpFixture f, String order) {
            var facts = new Facts(f.text("SELECT nickname FROM user_account WHERE id=" + BUYER),
                    f.text("SELECT phone FROM user_account WHERE id=" + BUYER),
                    f.text("SELECT service_name FROM order_service_snapshot WHERE order_id=" + order));
            for (String value : Arrays.asList(facts.nickname, facts.phone, facts.service))
                assertNotNull(value, "the real booking fixture must seed every projected fact");
            return facts;
        }
        String masked() { return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4); }
        void assertCustomer(Map<String, Object> data) {
            assertEquals(nickname, data.get("customerNickname"));
            assertEquals(masked(), data.get("customerPhoneMasked"));
            assertEquals(service, data.get("serviceName"));
        }
        void assertNoPrivateFacts(AfterSaleHttpFixture f, AfterSaleHttpFixture.Reply reply) throws Exception {
            String wire = f.json.writeValueAsString(reply.envelope());
            assertFalse(wire.contains(phone), "raw phone leaked");
            assertFalse(wire.contains(SEALED_REASON), "sealed buyer reason text leaked (§3.2 not approved)");
            String lower = wire.toLowerCase(Locale.ROOT);
            for (String key : List.of("reasontext", "userid", "mobile", "cipher", "avatar"))
                assertFalse(lower.contains(key), key + " in " + wire);
        }
        void assertNoCustomer(AfterSaleHttpFixture f, AfterSaleHttpFixture.Reply reply) throws Exception {
            assertNull(reply.envelope().get("data"));
            String wire = f.json.writeValueAsString(reply.envelope());
            for (String key : CUSTOMER_KEYS) assertFalse(wire.contains(key), key);
            assertFalse(wire.contains("timeline"), wire);
            for (String fact : List.of(nickname, phone, masked(), service)) assertFalse(wire.contains(fact), fact);
            assertFalse(wire.contains(SEALED_REASON), wire);
        }
    }
}
