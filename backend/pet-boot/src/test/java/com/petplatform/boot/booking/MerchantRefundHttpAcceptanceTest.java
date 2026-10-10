package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.adapter.web.merchant.MerchantRefundApplicationController;
import com.petplatform.refund.biz.application.RefundApplicationService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Merchant refund-application HTTP face acceptance (contract 57 / HTTP10 §4.4-§4.6 over the
 * 49号 kernel, switch pet.refund.merchant.http.enabled default OFF): list/detail/approve/
 * reject over real loopback HTTP, real MINIAPP sessions, isolated MySQL and Redis with the
 * kernel admission, five-tuple idempotency and deadline race unchanged. The worker stays
 * off and the channel stays forbidden: approve proves the APPROVED decision plus the
 * REFUND_APPLICATION_CREATE task, never a money move.
 */
class MerchantRefundHttpAcceptanceTest {

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

    private static String query(String merchantId, String storeId) {
        return "?merchantId=" + merchantId + "&storeId=" + storeId;
    }

    @Test
    void ownerApprovesOverHttpWithReplayAndProcessedConflict() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String application = pendingApplication(f);
            String order = f.text("SELECT CAST(order_id AS CHAR) FROM refund_application");

            // The pending backlog: exact wire fields, two-decimal amount, UTC millisecond instants.
            var list = f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            assertEquals(Boolean.TRUE, list.envelope().get("success"));
            assertEquals("no-store", list.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(1, list.data().get("total"));
            @SuppressWarnings("unchecked")
            Map<String, Object> row = ((List<Map<String, Object>>) list.data().get("items")).get(0);
            assertEquals(application, row.get("applicationId"));
            assertEquals(order, row.get("orderId"));
            assertEquals("PENDING_MERCHANT", row.get("status"));
            assertEquals("0", row.get("applicationVersion"));
            assertEquals("QA_REASON", row.get("reasonCode"));
            assertTrue(((String) row.get("refundAmount")).matches("^(0|[1-9][0-9]{0,15})\\.[0-9]{2}$"), String.valueOf(row.get("refundAmount")));
            assertEquals(20, list.data().get("pageSize"));

            // Detail carries the same fields plus the (still null) terminal facts.
            var detail = f.send("GET", "/merchant/refund-applications/" + application + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals(200, detail.status(), detail.toString());
            assertEquals(application, detail.value("applicationId"));
            assertNull(detail.data().get("decidedAt"));
            assertNull(detail.data().get("decisionId"));
            assertNull(detail.data().get("refundOrderId"));

            // Approve: fixed receipt, kernel facts (decision + create task), no money move.
            String key = UUID.randomUUID().toString();
            var approved = f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                Map.of("X-Request-Id", key, "Authorization", "Bearer " + f.ownerToken));
            assertEquals(200, approved.status(), approved.toString());
            assertEquals("APPROVED", approved.value("applicationStatus"));
            assertEquals("1", approved.value("applicationVersion"));
            assertEquals(order, approved.value("orderId"));
            assertNotNull(approved.value("decidedAt"));
            String decision = approved.value("decisionId");
            assertNotNull(decision);
            assertEquals("APPROVED", f.text("SELECT status FROM refund_application"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision WHERE status='APPROVED'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='REFUND_APPLICATION_CREATE'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(0, f.channelCalls.get());

            // Same key + same params replays the persisted first receipt.
            var replay = f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                Map.of("X-Request-Id", key, "Authorization", "Bearer " + f.ownerToken));
            assertEquals(200, replay.status());
            assertEquals(approved.data(), replay.data());

            // Same key with different content (reject) is an idempotency conflict.
            assertError(f.send("POST", "/merchant/refund-applications/" + application + "/reject",
                Map.of("reasonText", "改主意了 QA"), Map.of("X-Request-Id", key, "Authorization", "Bearer " + f.ownerToken)),
                409, "IDEMPOTENCY_KEY_CONFLICT");

            // A fresh key on the decided application is a plain processed conflict.
            assertError(f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                owner(UUID.randomUUID().toString(), f.ownerToken)), 409, "REFUND_APPLICATION_ALREADY_PROCESSED");

            // The decided round leaves the pending backlog; detail keeps the terminal facts.
            var after = f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals(0, after.data().get("total"));
            var decided = f.send("GET", "/merchant/refund-applications/" + application + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals("APPROVED", decided.value("status"));
            assertEquals(decision, decided.value("decisionId"));
            assertNotNull(decided.value("decidedAt"));
        }
    }

    @Test
    void ownerRejectsWithReasonBoundaryAndBuyerMayReapply() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String first = pendingApplication(f);
            String path = "/merchant/refund-applications/" + first + "/reject";

            // Reason surface first: missing/blank is 400 REFUND_MERCHANT_REASON_REQUIRED, the
            // shape violations are 400 COMMON_INVALID_ARGUMENT — none reaches admission.
            for (var body : List.of(
                    Map.<String, Object>of(),
                    Map.of("reasonText", "   "),
                    Map.of("reasonText", ""))) {
                assertError(f.send("POST", path, body, owner(UUID.randomUUID().toString(), f.ownerToken)),
                    400, "REFUND_MERCHANT_REASON_REQUIRED");
            }
            assertError(f.send("POST", path, (String) null, owner(UUID.randomUUID().toString(), f.ownerToken)),
                400, "REFUND_MERCHANT_REASON_REQUIRED");
            assertError(f.sendRaw("POST", path, "{\"reasonText\":null}", bearer(f.ownerToken)),
                400, "REFUND_MERCHANT_REASON_REQUIRED");
            assertError(f.sendRaw("POST", path, "{\"reasonText\":123}", bearer(f.ownerToken)),
                400, "REFUND_MERCHANT_REASON_REQUIRED");
            for (var raw : List.of(
                    "{\"reasonText\":\"x\",\"extra\":1}",
                    "{\"reasonText\":\"ok\"}{\"reasonText\":\"ok\"}",
                    "{\"reasonText\":\"" + "a".repeat(501) + "\"}")) {
                assertError(f.sendRaw("POST", path, raw, bearer(f.ownerToken)), 400, "COMMON_INVALID_ARGUMENT");
            }
            assertError(f.send("POST", path + "?x=1", Map.of("reasonText", "带查询参数 QA"),
                owner(UUID.randomUUID().toString(), f.ownerToken)), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path, Map.of("reasonText", "缺请求编号 QA"),
                Map.of("Authorization", "Bearer " + f.ownerToken, "X-Request-Id", "not-a-uuid")),
                400, "COMMON_INVALID_ARGUMENT");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_command WHERE command_namespace=CAST('refund.application.decide' AS BINARY)"));

            // Reject: REJECTED receipt, no refund order, no create task for this round.
            var rejected = f.send("POST", path, Map.of("reasonText", "服务人员已按约到店等待，暂不同意退款"),
                owner(UUID.randomUUID().toString(), f.ownerToken));
            assertEquals(200, rejected.status(), rejected.toString());
            assertEquals("REJECTED", rejected.value("applicationStatus"));
            assertEquals("1", rejected.value("applicationVersion"));
            assertEquals("REJECTED", f.text("SELECT status FROM refund_application"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_application_decision WHERE status='REJECTED'"));

            // §38: the buyer may apply again — a new id, a fresh 24h deadline, back in the backlog.
            String order = f.text("SELECT CAST(order_id AS CHAR) FROM refund_application");
            var app = f.context.getBean(RefundApplicationService.class);
            f.as(f.buyerToken);
            var reapplied = app.apply(f.ordinary.applyCommand(order));
            RequestContextHolder.resetRequestAttributes();
            assertEquals("PENDING_MERCHANT", reapplied.applicationStatus());
            assertNotEquals(first, reapplied.applicationId());
            var list = f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals(1, list.data().get("total"));
            assertEquals(reapplied.applicationId(), ((List<?>) list.data().get("items")).isEmpty() ? null
                : ((Map<?, ?>) ((List<?>) list.data().get("items")).get(0)).get("applicationId"));
        }
    }

    @Test
    void nonOwnerForeignAndUnknownTargetsFailClosedWithoutEnumeration() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            String application = pendingApplication(f);
            assertEquals(401, f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, (String) null).status());
            assertEquals(401, f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(), (String) null).status());

            // Another MINIAPP session never reaches the kernel.
            assertError(f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.otherToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("GET", "/merchant/refund-applications/" + application + query(MERCHANT, STORE), null, f.otherToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                owner(UUID.randomUUID().toString(), f.otherToken)), 403, "COMMON_FORBIDDEN");

            // Reads have no 404 oracle: unknown application, unknown store and a foreign store
            // coordinate all answer the same 403.
            for (String unknown : List.of("9007199254740993", "123")) {
                assertError(f.send("GET", "/merchant/refund-applications/" + unknown + query(MERCHANT, STORE), null, f.ownerToken), 403, "COMMON_FORBIDDEN");
            }
            assertError(f.send("GET", "/merchant/refund-applications" + query(MERCHANT, "9007199254740993"), null, f.ownerToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("GET", "/merchant/refund-applications" + query("9007199254740993", STORE), null, f.ownerToken), 403, "COMMON_FORBIDDEN");

            // Commands keep the kernel semantics: unknown application 404, foreign session 403.
            assertError(f.send("POST", "/merchant/refund-applications/9007199254740993/approve", Map.of(),
                owner(UUID.randomUUID().toString(), f.ownerToken)), 404, "REFUND_APPLICATION_NOT_FOUND");
            assertError(f.send("POST", "/merchant/refund-applications/9007199254740993/reject",
                Map.of("reasonText", "未知申请 QA"), owner(UUID.randomUUID().toString(), f.ownerToken)),
                404, "REFUND_APPLICATION_NOT_FOUND");
            // None of the probes decided anything; the anti-enumeration 403 leaves the kernel's
            // fail-persisted RESERVED binding (49号: 失败保留绑定) but never a SUCCEEDED one.
            assertEquals("PENDING_MERCHANT", f.text("SELECT status FROM refund_application"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_command WHERE command_namespace=CAST('refund.application.decide' AS BINARY) AND state='SUCCEEDED'"));
        }
    }

    @Test
    void pastDeadlineIsRejectedOverHttpAndSystemTakesOver() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            pendingApplication(f);
            // Move the fixture clock (both the DB session clock and the session clock) past the
            // stored deadline, then re-login the owner at the new time.
            var deadline = OffsetDateTime.parse(f.text("SELECT DATE_FORMAT(merchant_deadline,'%Y-%m-%dT%H:%i:%s.%fZ') FROM refund_application").replace(',', '.'));
            f.at(deadline.toInstant().plusSeconds(1));
            f.loginOwner();
            String application = f.text("SELECT CAST(id AS CHAR) FROM refund_application");
            assertError(f.send("POST", "/merchant/refund-applications/" + application + "/approve", Map.of(),
                owner(UUID.randomUUID().toString(), f.ownerToken)), 409, "REFUND_MERCHANT_DEADLINE_PASSED");
            assertError(f.send("POST", "/merchant/refund-applications/" + application + "/reject",
                Map.of("reasonText", "过期拒绝 QA"), owner(UUID.randomUUID().toString(), f.ownerToken)),
                409, "REFUND_MERCHANT_DEADLINE_PASSED");
            assertEquals(0, f.count("SELECT COUNT(*) FROM refund_application_decision"));
            assertEquals("PENDING_MERCHANT", f.text("SELECT status FROM refund_application"));
            // The pending backlog stays readable for the owner past the deadline.
            var list = f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            assertEquals(1, list.data().get("total"));
        }
    }

    @Test
    void listQueryShapeIsBounded() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of("pet.refund.merchant.http.enabled", true))) {
            pendingApplication(f);
            assertError(f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE) + "&displayStatus=REFUND_PENDING_CONFIRM", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE) + "&page=0", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE) + "&pageSize=101", null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", "/merchant/refund-applications?merchantId=" + MERCHANT, null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("GET", "/merchant/refund-applications/abc" + query(MERCHANT, STORE), null, f.ownerToken), 400, "COMMON_INVALID_ARGUMENT");
            // Paging: page bounds respected, unknown page reads empty without error.
            var page = f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE) + "&page=2&pageSize=1", null, f.ownerToken);
            assertEquals(200, page.status());
            assertEquals(1, page.data().get("total"));
            assertTrue(((List<?>) page.data().get("items")).isEmpty());
        }
    }

    /** Default off: without the merchant http flag neither the controller nor the route exists. */
    @Test
    void disabledHttpSwitchKeepsRoutesUnassembledAndDenied() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(MerchantRefundApplicationController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(MerchantRefundApplicationController.class).isEmpty());
                });
        try (var f = new AfterSaleHttpFixture()) {
            ConfigurableApplicationContext context = f.context;
            assertTrue(context.getBeansOfType(MerchantRefundApplicationController.class).isEmpty());
            assertTrue(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                    .getHandlerMethods().keySet().stream()
                    .map(Object::toString)
                    .noneMatch(route -> route.contains("/refund-applications")));
            assertEquals(403, f.send("GET", "/merchant/refund-applications" + query(MERCHANT, STORE), null, f.ownerToken).status());
            assertEquals(403, f.send("POST", "/merchant/refund-applications/1/approve", Map.of(), f.ownerToken).status());
            assertEquals(403, f.send("POST", "/merchant/refund-applications/1/reject", Map.of("reasonText", "x"), f.ownerToken).status());
        }
    }

    private static Map<String, String> owner(String requestId, String token) {
        return Map.of("X-Request-Id", requestId, "Authorization", "Bearer " + token);
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply + " message=" + reply.envelope().get("message"));
        assertEquals(code, reply.envelope().get("code"), reply + " message=" + reply.envelope().get("message"));
        assertNull(reply.envelope().get("data"));
    }
}
