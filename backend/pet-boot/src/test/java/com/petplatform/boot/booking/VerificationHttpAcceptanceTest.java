package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.c.CVerificationCredentialController;
import com.petplatform.boot.adapter.web.merchant.MerchantOrderVerificationController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Verification HTTP face acceptance (contract 47 §4 v0.2 + contract 48 K2 v0.3): the two C-side
 * credential routes and the merchant scan completion over real loopback HTTP, real MINIAPP
 * sessions, isolated MySQL and Redis. The OWNER and STAFF completion paths, the five-tuple
 * idempotent replay, the K1 anti-enumeration matrix and the fail-closed boundaries are all
 * exercised through the wire; staff member/grant rows are fixtures only (binding delivery stays
 * with the 54号 slice).
 */
class VerificationHttpAcceptanceTest {
    private static final String STAFF_USER = "710310";
    private static final long STAFF_PROFILE = 710303L;
    private static final long MEMBER = 710311L;
    private static final long GRANT = 710312L;
    private static final String VERIFY_ACTION = "merchant.order.verify";

    /** The aftersale HTTP composition plus both verification http switches and the staff kernel. */
    static final class Http implements AutoCloseable {
        final AfterSaleHttpFixture f;
        final String staffToken;

        Http() throws Exception {
            this(Map.of());
        }

        Http(Map<String, Object> extra) throws Exception {
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("pet.verification.credential.http.enabled", true);
            overrides.put("pet.verification.completion.http.enabled", true);
            overrides.put("pet.merchant.staff-identity.enabled", true);
            overrides.put("pet.verification.staff-identity.enabled", true);
            overrides.putAll(extra);
            // The contract-52 staff gate reads real SQL29 application facts; the AFS fixture does
            // not assemble that slice, so the verification fixture provides the genuine reader.
            java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(9_170_000_000_000_000L);
            f = new AfterSaleHttpFixture(overrides, true, (beans, source) -> beans.registerBean(
                    "qaVerificationApplicationFacts",
                    com.petplatform.merchant.biz.application.ApplicationReviewFactsReader.class,
                    () -> new com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader(
                            source, ids::incrementAndGet)));
            try {
                f.ordinary.t.r.f.f.db.script("52-Merchant-Staff-Identity-Schema-v0.1.sql");
                f.jdbc.update("INSERT INTO user_account(id,nickname,status,created_at,updated_at)"
                        + " VALUES(?,'核销HTTP员工','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        Long.parseLong(STAFF_USER));
                staffToken = f.loginUser(STAFF_USER, "13800000310");
            } catch (Exception failure) {
                f.close();
                throw failure;
            }
        }

        /** One fresh order per fixture: the seeded 2030 window holds a single booking. */
        String readyOrder() throws Exception {
            String order = f.ordinary.t.ready();
            f.at(f.clock.instant().plusSeconds(30));
            return order;
        }

        String issue(String order) throws Exception {
            var reply = f.send("POST", "/c/orders/" + order + "/verification-code",
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), f.buyerToken);
            assertEquals(200, reply.status(), reply.toString());
            return reply.value("code");
        }

        void seedStaff(String memberStatus, String grantStatus, Long staffProfileId, String action) {
            f.jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at)"
                    + " VALUES(?,?,?,'ENABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MEMBER, 710301L, Long.parseLong(STAFF_USER));
            f.jdbc.update("UPDATE merchant_member SET status=? WHERE id=?", memberStatus, MEMBER);
            f.jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,staff_id,status,version,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", GRANT, MEMBER, Long.parseLong(STORE), staffProfileId, grantStatus);
            if (action != null) {
                f.jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at)"
                        + " VALUES(9101,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MEMBER, Long.parseLong(STORE), action);
            }
        }

        public void close() {
            f.close();
        }
    }

    @Test
    void ownerScanCompletesWithIdempotentReplayAndCEndCredentialRules() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.readyOrder();
            String cPath = "/c/orders/" + order + "/verification-code";

            assertEquals(401, f.send("GET", cPath, null, (String) null).status());
            assertEquals(401, f.send("POST", cPath,
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), (String) null).status());
            assertEquals(403, f.send("GET", "/c/orders/1/verification-code", null, f.buyerToken).status());

            var none = f.send("GET", cPath, null, f.buyerToken);
            assertEquals(200, none.status(), none.toString());
            assertEquals("NONE", none.data().get("status"));
            assertNull(none.data().get("code"));
            assertEquals("0", none.data().get("credentialVersion"));
            assertNull(none.data().get("lockedUntil"));
            assertEquals("no-store", none.headers().firstValue("Cache-Control").orElseThrow());

            String issueKey = UUID.randomUUID().toString();
            var issued = f.send("POST", cPath,
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), headers(f.buyerToken, issueKey));
            assertEquals(200, issued.status(), issued.toString());
            String code = issued.value("code");
            assertTrue(code.matches("[0-9A-Z]{32}"), code);
            assertEquals("1", issued.value("credentialVersion"));
            assertNotNull(issued.value("credentialId"));
            assertEquals(issued.value("expiresAt"), issued.value("refreshAfter"));
            var active = f.send("GET", cPath, null, f.buyerToken);
            assertEquals("ACTIVE", active.data().get("status"));
            assertEquals(code, active.data().get("code"));

            var replay = f.send("POST", cPath,
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), headers(f.buyerToken, issueKey));
            assertEquals(200, replay.status());
            assertEquals(issued.data(), replay.data());
            assertError(f.send("POST", cPath, Map.of("expectedCredentialVersion", "1", "refreshKind", "MANUAL"),
                    headers(f.buyerToken, issueKey)), 409, "IDEMPOTENCY_KEY_CONFLICT");
            assertError(f.send("POST", cPath, Map.of("expectedCredentialVersion", "1", "refreshKind", "INITIAL"),
                    headers(f.buyerToken, UUID.randomUUID().toString())), 409, "COMMON_CONFLICT");
            assertError(f.send("GET", cPath + "?code=" + code, null, f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", cPath, Map.of("expectedCredentialVersion", "1"), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", cPath, Map.of("expectedCredentialVersion", "1", "refreshKind", "SOMETIMES"), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", cPath, Map.of("expectedCredentialVersion", 1, "refreshKind", "INITIAL"), f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            String raw = f.json.writeValueAsString(Map.of("expectedCredentialVersion", "1", "refreshKind", "MANUAL"));
            assertError(f.sendRaw("POST", cPath, raw.replace("\"MANUAL\"", "\"MANUAL\",\"MANUAL\":\"x\""), bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", cPath, "{\"expectedCredentialVersion\":null,\"refreshKind\":\"MANUAL\"}", bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", cPath, null, f.otherToken), 403, "COMMON_FORBIDDEN");

            String verifyPath = "/merchant/orders/" + order + "/verification";
            assertEquals(401, f.send("POST", verifyPath, Map.of("verificationCode", code), (String) null).status());
            assertError(f.send("POST", verifyPath, Map.of("verificationCode", "bad code"), f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", verifyPath, Map.of("verificationCode", code, "storeId", STORE), f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.sendRaw("POST", verifyPath, "{\"verificationCode\":null}", bearer(f.ownerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", verifyPath, Map.of(), headers(f.ownerToken, UUID.randomUUID().toString())), 400, "COMMON_INVALID_ARGUMENT");

            var bad = f.send("POST", verifyPath, Map.of("verificationCode", "WRONGCODE1"), f.ownerToken);
            assertEquals(200, bad.status(), bad.toString());
            assertEquals("VERIFICATION_CODE_INVALID", bad.data().get("resultCode"));
            assertNull(bad.data().get("verificationId"));
            assertNull(bad.data().get("verifiedAt"));
            assertNull(bad.data().get("orderVersion"));

            String verifyKey = UUID.randomUUID().toString();
            var verified = f.send("POST", verifyPath, Map.of("verificationCode", code), headers(f.ownerToken, verifyKey));
            assertEquals(200, verified.status(), verified.toString());
            assertEquals("VERIFIED", verified.data().get("resultCode"));
            assertNotNull(verified.data().get("verificationId"));
            assertNotNull(verified.data().get("verifiedAt"));
            assertNotNull(verified.data().get("orderVersion"));
            assertEquals(true, verified.envelope().get("success"));
            assertEquals("no-store", verified.headers().firstValue("Cache-Control").orElseThrow());
            var verifyReplay = f.send("POST", verifyPath, Map.of("verificationCode", code), headers(f.ownerToken, verifyKey));
            assertEquals(200, verifyReplay.status());
            assertEquals(verified.data(), verifyReplay.data());
            assertError(f.send("POST", verifyPath, Map.of("verificationCode", code), f.ownerToken), 409, "VERIFICATION_ALREADY_DONE");
            assertError(f.send("POST", verifyPath, Map.of("verificationCode", "WRONGCODE2"), f.ownerToken), 409, "VERIFICATION_ALREADY_DONE");

            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record WHERE operator_type='USER' AND operator_id=710300"
                    + " AND membership_kind='OWNER' AND operator_staff_id IS NULL"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'"));
            assertEquals("COMPLETED", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));
            assertEquals("VERIFIED", f.text("SELECT verification_status FROM pet_order WHERE id=" + order));
        }
    }

    @Test
    void staffScanTraceableIdentityAndAntiEnumerationMatrix() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.readyOrder();
            String code = http.issue(order);
            String path = "/merchant/orders/" + order + "/verification";
            Map<String, Object> body = Map.of("verificationCode", code);

            // No confirmed relation at all (invite never confirmed): 404 anti-enumeration.
            http.seedStaff("ENABLED", "ENABLED", STAFF_PROFILE, VERIFY_ACTION);
            f.jdbc.update("DELETE FROM merchant_member_store_action WHERE member_id=" + MEMBER);
            f.jdbc.update("DELETE FROM merchant_member_store_grant WHERE id=" + GRANT);
            f.jdbc.update("DELETE FROM merchant_member WHERE id=" + MEMBER);
            assertError(f.send("POST", path, body, http.staffToken), 404, "COMMON_NOT_FOUND");

            f.jdbc.update("INSERT INTO merchant_member(id,merchant_id,user_id,status,version,created_at,updated_at)"
                    + " VALUES(" + MEMBER + ",710301," + STAFF_USER + ",'DISABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            f.jdbc.update("INSERT INTO merchant_member_store_grant(id,member_id,store_id,staff_id,status,version,created_at,updated_at)"
                    + " VALUES(" + GRANT + "," + MEMBER + "," + STORE + "," + STAFF_PROFILE + ",'ENABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            assertError(f.send("POST", path, body, http.staffToken), 403, "COMMON_FORBIDDEN");

            f.jdbc.update("UPDATE merchant_member SET status='REVOKED' WHERE id=" + MEMBER);
            assertError(f.send("POST", path, body, http.staffToken), 404, "COMMON_NOT_FOUND");

            f.jdbc.update("UPDATE merchant_member SET status='ENABLED' WHERE id=" + MEMBER);
            f.jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at)"
                    + " VALUES(9102," + MEMBER + "," + STORE + ",'merchant.order.read',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            assertError(f.send("POST", path, body, http.staffToken), 403, "COMMON_FORBIDDEN");

            f.jdbc.update("INSERT INTO merchant_member_store_action(id,member_id,store_id,action_code,created_at,updated_at)"
                    + " VALUES(9103," + MEMBER + "," + STORE + ",'" + VERIFY_ACTION + "',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            f.jdbc.update("UPDATE merchant_member_store_grant SET status='REVOKED' WHERE id=" + GRANT);
            assertError(f.send("POST", path, body, http.staffToken), 404, "COMMON_NOT_FOUND");

            // Grant only for another store: no relation for the order's own store (PRD §5.8).
            f.jdbc.update("UPDATE merchant_member_store_grant SET status='ENABLED', store_id=710999 WHERE id=" + GRANT);
            assertError(f.send("POST", path, body, http.staffToken), 404, "COMMON_NOT_FOUND");
            f.jdbc.update("UPDATE merchant_member_store_grant SET store_id=" + STORE + " WHERE id=" + GRANT);

            // A grant without a traceable staff profile can never complete.
            f.jdbc.update("UPDATE merchant_member_store_grant SET staff_id=NULL WHERE id=" + GRANT);
            assertError(f.send("POST", path, body, http.staffToken), 403, "COMMON_FORBIDDEN");
            f.jdbc.update("UPDATE merchant_member_store_grant SET staff_id=" + STAFF_PROFILE + " WHERE id=" + GRANT);

            // FROZEN/OFFLINE fail closed for staff (D5, no legacy exception).
            f.jdbc.update("UPDATE merchant SET status='FROZEN' WHERE id=710301");
            assertError(f.send("POST", path, body, http.staffToken), 403, "COMMON_FORBIDDEN");
            f.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=710301");
            f.jdbc.update("UPDATE merchant_store SET status='OFFLINE' WHERE id=" + STORE);
            assertError(f.send("POST", path, body, http.staffToken), 403, "COMMON_FORBIDDEN");
            f.jdbc.update("UPDATE merchant_store SET status='ACTIVE' WHERE id=" + STORE);

            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_attempt"));
            assertEquals("PENDING_SERVICE", f.text("SELECT order_stage FROM pet_order WHERE id=" + order));

            String key = UUID.randomUUID().toString();
            var verified = f.send("POST", path, body, headers(http.staffToken, key));
            assertEquals(200, verified.status(), verified.toString());
            assertEquals("VERIFIED", verified.data().get("resultCode"));
            assertEquals(verified.data(), f.send("POST", path, body, headers(http.staffToken, key)).data());

            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_record WHERE operator_type='MERCHANT_STAFF'"
                    + " AND operator_id=" + STAFF_PROFILE + " AND membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE));
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_attempt WHERE operator_type='MERCHANT_STAFF'"
                    + " AND membership_kind='STAFF' AND operator_staff_id=" + STAFF_PROFILE + " AND result='SUCCESS'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM verification_credential_command WHERE actor_type='USER'"
                    + " AND actor_id=" + STAFF_USER + " AND state='SUCCEEDED'"));
            var event = f.json.readTree(f.text(
                    "SELECT payload FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'"));
            assertEquals("MERCHANT_STAFF", event.get("operatorType").asText());
            assertEquals("STAFF", event.get("membershipKind").asText());
            assertEquals(Long.toString(STAFF_PROFILE), event.get("operatorStaffId").asText());
        }
    }

    @Test
    void refundOrderBlocksBothSidesFailClosed() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.readyOrder();
            String code = http.issue(order);
            f.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,"
                    + "refund_ratio,status,initiator_type,created_at,updated_at) VALUES(99,99,?,'FULL',"
                    + "'PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", Long.parseLong(order));
            assertError(f.send("GET", "/c/orders/" + order + "/verification-code", null, f.buyerToken),
                    409, "VERIFICATION_BLOCKED_BY_REFUND");
            assertError(f.send("POST", "/merchant/orders/" + order + "/verification",
                    Map.of("verificationCode", code), f.ownerToken), 409, "VERIFICATION_BLOCKED_BY_REFUND");
            assertEquals(0, f.count("SELECT COUNT(*) FROM verification_record"));
        }
    }

    @Test
    void pendingAftersaleAloneDoesNotBlockTheScanAndIsInvalidatedByIt() throws Exception {
        try (var http = new Http()) {
            var f = http.f;
            String order = http.readyOrder();
            String code = http.issue(order);
            // A real active legacy (SQL48) aftersale case bound to the order projection.
            f.jdbc.update("INSERT INTO aftersale_case(id,aftersale_no,order_id,user_id,merchant_id,"
                    + "store_id,status,source_stage,description,active_flag,created_at,version)"
                    + " VALUES(42,42,?,710100,710301,?,'PENDING','UNVERIFIED_POST_START',"
                    + "'QA pending aftersale before scan',1,UTC_TIMESTAMP(3),0)",
                    Long.parseLong(order), Long.parseLong(STORE));
            f.jdbc.update("UPDATE pet_order SET current_aftersale_id=42, aftersale_status='PENDING'"
                    + " WHERE id=" + Long.parseLong(order));
            assertEquals("ACTIVE", f.send("GET", "/c/orders/" + order + "/verification-code", null, f.buyerToken)
                    .data().get("status"));
            var done = f.send("POST", "/merchant/orders/" + order + "/verification",
                    Map.of("verificationCode", code), f.ownerToken);
            assertEquals(200, done.status(), done.toString());
            assertEquals("VERIFIED", done.data().get("resultCode"));
            // The successful scan invalidates the current unfulfilled aftersale (48号 K2 linkage).
            assertEquals("INVALIDATED", f.text("SELECT status FROM aftersale_case WHERE id=42"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM aftersale_case WHERE id=42 AND active_flag=1"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM aftersale_verification_proof WHERE order_id=" + order));
        }
    }

    @Test
    void disabledSwitchesKeepRoutesUnassembled() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(CVerificationCredentialController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CVerificationCredentialController.class).isEmpty());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantOrderVerificationController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantOrderVerificationController.class).isEmpty());
                });
        // Kernels stay on, only the http switches off: no controllers, no routes, requests denied.
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.merchant.staff-identity.enabled", true,
                "pet.verification.staff-identity.enabled", true))) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(CVerificationCredentialController.class).isEmpty());
            assertTrue(context.getBeansOfType(MerchantOrderVerificationController.class).isEmpty());
            assertTrue(verificationMappings(context).isEmpty());
            assertEquals(403, f.send("GET", "/c/orders/1/verification-code", null, f.buyerToken).status());
            assertEquals(403, f.send("POST", "/c/orders/1/verification-code",
                    Map.of("expectedCredentialVersion", "0", "refreshKind", "INITIAL"), f.buyerToken).status());
            assertEquals(403, f.send("POST", "/merchant/orders/1/verification",
                    Map.of("verificationCode", "ABC"), f.ownerToken).status());
        }
    }

    private static List<String> verificationMappings(ConfigurableApplicationContext app) {
        return app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .map(Object::toString)
                .filter(route -> route.contains("verification-code") || route.endsWith("/verification}"))
                .toList();
    }

    private static Map<String, String> headers(String token, String requestId) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.toString());
        assertEquals(code, reply.envelope().get("code"));
        assertNull(reply.envelope().get("data"));
    }
}
