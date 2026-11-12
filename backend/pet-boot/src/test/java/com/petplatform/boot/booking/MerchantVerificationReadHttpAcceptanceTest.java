package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.merchant.MerchantVerificationReadController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * CCR-M-VERIFY-RECORD-001 merchant verification-record list acceptance (approved 2026-11-12,
 * switch pet.verification.merchant-read.http.enabled default OFF): the OWNER read route over
 * real loopback HTTP, real MINIAPP sessions, isolated MySQL and Redis with the kernel admission
 * unchanged. One real OWNER verification is driven through the 48号 completion kernel; a STAFF
 * record plus its merchant_staff profile row are seeded directly (the write side keeps its own
 * suites). Proves the CCR §9 display pair (STAFF real name / OWNER 「店长」), the
 * CCR-M-ORDER-CARD-001 card assembly with null degradation, the anti-enumeration 403 family
 * (non-OWNER, unknown coordinates), the 63号 query discipline and the switch-off boundary.
 */
class MerchantVerificationReadHttpAcceptanceTest {

    private static final String LIST = "/merchant/verifications?merchantId=710301&storeId=710302";
    private static final long STAFF_PROFILE = 710350L;
    // 88xx ids stay outside both the seeded 710xxx constants and the fixture's 9160… generator,
    // so the seeded row can never collide with a kernel-generated snowflake unique key.
    private static final long GHOST_ORDER = 8800000000000201L;
    private static final long STAFF_RECORD = 8800000000000101L;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(AfterSaleHttpFixture.Reply reply) {
        return (List<Map<String, Object>>) reply.data().get("items");
    }

    /** Real OWNER verification + seeded STAFF record/profile; returns the OWNER order id. */
    private static String verifiedWithStaffRecord(AfterSaleHttpFixture f) {
        String order = f.verifiedOrder();
        f.jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,phone,employment_status,service_enabled,version,created_at,updated_at)"
                + " VALUES(?,710301,710302,'李四',NULL,'ACTIVE',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", STAFF_PROFILE);
        f.jdbc.update("INSERT INTO verification_record(id,verification_no,order_id,store_id,operator_staff_id,verify_method,"
                        + "request_id,verified_at,created_at,operator_type,operator_id,membership_kind,command_id,credential_id,attempt_id,order_version)"
                        + " VALUES(?,?,?,710302,?,'SCAN','qa-merchant-read-staff','2030-01-02 09:00:00','2030-01-02 09:00:00',"
                        + "'MERCHANT_STAFF',?,'STAFF',8800000000000301,8800000000000401,8800000000000501,7)",
                STAFF_RECORD, STAFF_RECORD, GHOST_ORDER, STAFF_PROFILE, STAFF_PROFILE);
        return order;
    }

    private static void assertItemShape(Map<String, Object> item) {
        // CCR §3/§4: exactly the nine wire keys — no operatorStaffId/operatorId/phone ever.
        assertEquals(Set.of("verificationId", "orderId", "serviceName", "payAmount",
                "customerNickname", "customerAvatarUrl", "verifiedAt", "operatorKind",
                "operatorName"), item.keySet(), item.toString());
        assertTrue(item.keySet().stream().noneMatch(key -> key.toLowerCase().contains("phone")),
                item.toString());
        assertTrue(item.get("verifiedAt") instanceof String
                && ((String) item.get("verifiedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                item.toString());
    }

    @Test
    void ownerSeesTheRecordListWithCardsAndTheCcrDisplayPair() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.verification.merchant-read.http.enabled", true))) {
            String order = verifiedWithStaffRecord(f);

            // Session boundary first: anonymous is filtered 401 before any controller logic.
            assertEquals(401, f.send("GET", LIST, null, (String) null).status());

            var list = f.send("GET", LIST, null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            assertEquals("SUCCESS", list.envelope().get("code"));
            assertEquals("no-store", list.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(2L, list.data().get("total"));
            assertEquals(20, list.data().get("pageSize"));
            var items = items(list);
            assertEquals(2, items.size());
            items.forEach(MerchantVerificationReadHttpAcceptanceTest::assertItemShape);

            // Fixed sort verified_at DESC: the (later) seeded STAFF record is first.
            Map<String, Object> staff = items.get(0);
            assertEquals(Long.toString(STAFF_RECORD), staff.get("verificationId"));
            assertEquals(Long.toString(GHOST_ORDER), staff.get("orderId"));
            assertEquals("STAFF", staff.get("operatorKind"));
            assertEquals("李四", staff.get("operatorName"));
            // Ghost order has no card in this store: facts degrade to null, never fabricated.
            assertNull(staff.get("serviceName"), staff.toString());
            assertNull(staff.get("payAmount"), staff.toString());
            assertNull(staff.get("customerNickname"), staff.toString());
            assertNull(staff.get("customerAvatarUrl"), staff.toString());

            Map<String, Object> owner = items.get(1);
            assertEquals("OWNER", owner.get("operatorKind"));
            assertEquals("店长", owner.get("operatorName"));
            assertEquals(order, owner.get("orderId"));
            assertTrue(owner.get("serviceName") instanceof String, owner.toString());
            assertTrue(((String) owner.get("payAmount")).matches("^(0|[1-9][0-9]{0,15})\\.[0-9]{2}$"),
                    owner.toString());
            assertEquals(f.jdbc.queryForObject(
                    "SELECT pay_amount FROM verification_record vr JOIN pet_order o ON o.id=vr.order_id WHERE vr.membership_kind='OWNER'",
                    java.math.BigDecimal.class).setScale(2).toPlainString(), owner.get("payAmount"));
            assertTrue(owner.get("customerNickname") == null || owner.get("customerNickname") instanceof String,
                    owner.toString());
            // verifiedAt is the stored verified_at read back as UTC wall time at millisecond precision.
            java.time.LocalDateTime stored = f.jdbc.queryForObject(
                    "SELECT verified_at FROM verification_record WHERE membership_kind='OWNER'", java.time.LocalDateTime.class);
            assertEquals(stored.atOffset(java.time.ZoneOffset.UTC).toInstant(),
                    java.time.Instant.parse((String) owner.get("verifiedAt")));

            // CCR §4 red line: the raw operator identity never crosses the surface — the item
            // keyset is pinned above, and no wire value may equal the OWNER's real user id.
            for (Map<String, Object> item : items) {
                for (Object value : item.values()) {
                    assertNotEquals("710300", Objects.toString(value, null), "operatorId must not leak");
                }
            }
        }
    }

    @Test
    void orderIdAndRangeFiltersFollowTheQueryDiscipline() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.verification.merchant-read.http.enabled", true))) {
            String order = verifiedWithStaffRecord(f);

            // Exact orderId filter isolates the OWNER record.
            var byOrder = f.send("GET", LIST + "&orderId=" + order, null, f.ownerToken);
            assertEquals(200, byOrder.status(), byOrder.toString());
            assertEquals(1L, byOrder.data().get("total"));
            assertEquals("OWNER", items(byOrder).get(0).get("operatorKind"));

            // Paired range around the seeded STAFF instant (2030-01-02) isolates it too.
            var ranged = f.send("GET", LIST
                    + "&verifiedFrom=2030-01-02T00:00:00.000Z&verifiedTo=2030-01-02T23:59:59.999Z",
                    null, f.ownerToken);
            assertEquals(1L, ranged.data().get("total"));
            assertEquals("STAFF", items(ranged).get(0).get("operatorKind"));

            // 63号 discipline: single-sided range, inverted range, span over 366 days.
            assertError(f.send("GET", LIST + "&verifiedFrom=2030-01-02T00:00:00.000Z", null, f.ownerToken),
                    400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", LIST
                            + "&verifiedFrom=2030-01-03T00:00:00.000Z&verifiedTo=2030-01-02T00:00:00.000Z",
                    null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", LIST
                            + "&verifiedFrom=2029-01-01T00:00:00.000Z&verifiedTo=2030-03-01T00:00:00.000Z",
                    null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            // Unknown query parameter and bad page bounds share the 400 family.
            assertError(f.send("GET", LIST + "&who=1", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", LIST + "&page=0", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", LIST + "&pageSize=101", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
        }
    }

    @Test
    void nonOwnerAndForeignCoordinatesShareOneForbiddenAnswer() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.verification.merchant-read.http.enabled", true))) {
            verifiedWithStaffRecord(f);
            // A fellow platform user (the buyer, and the verified order's customer) is not the OWNER.
            var buyer = f.send("GET", LIST, null, f.buyerToken);
            assertError(buyer, 403, "COMMON_FORBIDDEN");
            // Unknown coordinates read exactly the same (anti-enumeration).
            var unknown = f.send("GET",
                    "/merchant/verifications?merchantId=710399&storeId=710399", null, f.ownerToken);
            assertError(unknown, 403, "COMMON_FORBIDDEN");
            var foreign = f.send("GET",
                    "/merchant/verifications?merchantId=710301&storeId=710313", null, f.ownerToken);
            assertError(foreign, 403, "COMMON_FORBIDDEN");
            assertEquals(buyer.envelope().get("message"), unknown.envelope().get("message"));
            assertEquals(buyer.envelope().get("message"), foreign.envelope().get("message"));
            assertEquals(0L, f.count("SELECT COUNT(*) FROM verification_record WHERE store_id=710313"));
        }
    }

    /** Default off: without the http flag neither the controller nor the route is registered. */
    @Test
    void defaultOffRegistersNoControllerOrRoute() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantVerificationReadController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantVerificationReadController.class).isEmpty());
                });
        try (var f = new AfterSaleHttpFixture()) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(MerchantVerificationReadController.class).isEmpty());
            assertTrue(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                    .getHandlerMethods().keySet().stream()
                    .map(Object::toString)
                    .noneMatch(route -> route.endsWith("/merchant/verifications}")));
            assertEquals(403, f.send("GET", LIST, null, f.ownerToken).status());
        }
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply + " message=" + reply.envelope().get("message"));
        assertEquals(code, reply.envelope().get("code"), reply.toString());
        assertNull(reply.envelope().get("data"));
    }
}
