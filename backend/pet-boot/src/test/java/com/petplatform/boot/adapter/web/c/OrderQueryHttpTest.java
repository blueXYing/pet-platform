package com.petplatform.boot.adapter.web.c;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.biz.apiimpl.OrderQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * C-004 controller acceptance over isolated real MySQL: the §3.7 read surface (list filter and
 * the domain-computed displayStatus, fixed created_at DESC/id DESC pagination, the detail fact
 * projection, anti-enumeration 404, missing-session 401, strict parameter 400s, default-off
 * assembly). Rows are seeded straight into pet_order because this is a read-side slice; the
 * creation kernel stays covered by the booking acceptance tests. The list filter assertions
 * cross-check the SQL predicates against the same Java computation used by the detail view.
 */
class OrderQueryHttpTest {

    private static final long OWNER = 9_500_000_000_000_601L;
    private static final long OTHER = 9_500_000_000_000_602L;
    private static final long MERCHANT = 9_500_000_000_000_603L;
    private static final long STORE = 9_500_000_000_000_604L;
    private static final long SERVICE = 9_500_000_000_000_605L;
    private static final long PET = 9_500_000_000_000_606L;
    private static final long RESERVATION_BASE = 9_500_000_000_000_610L;

    private static final long PENDING_PAY_1 = 9_500_000_000_000_701L;
    private static final long PENDING_CONFIRM = 9_500_000_000_000_702L;
    private static final long PENDING_SERVICE = 9_500_000_000_000_703L;
    private static final long COMPLETED = 9_500_000_000_000_704L;
    private static final long CANCELED = 9_500_000_000_000_705L;
    private static final long REFUNDING = 9_500_000_000_000_706L;
    private static final long REFUNDED = 9_500_000_000_000_707L;
    private static final long PARTIAL = 9_500_000_000_000_708L;
    private static final long REFUND_PENDING = 9_500_000_000_000_709L;
    private static final long AFTERSALE = 9_500_000_000_000_710L;
    private static final long PENDING_PAY_2 = 9_500_000_000_000_711L;
    private static final long FOREIGN = 9_500_000_000_000_712L;
    private static final long LATE_PAYMENT = 9_500_000_000_000_713L;
    /** Fixed read moment shared with the biz truth table: deterministic action windows. */
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-07T12:00:00Z");

    private Fixture fixture;
    private COrderQueryController orders;

    @BeforeEach
    void start() throws Exception {
        fixture = new Fixture();
        seed(fixture);
        orders = new COrderQueryController(new OrderQueryApiImpl(fixture.source,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static void seed(Fixture fixture) {
        JdbcTemplate jdbc = fixture.jdbc;
        // Two orders share one created_at to pin the id DESC tiebreaker.
        order(jdbc, PENDING_PAY_1, OWNER, "PENDING_PAYMENT", "INIT", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, null, "2026-10-01 10:00:00.000");
        order(jdbc, PENDING_CONFIRM, OWNER, "PENDING_CONFIRM", "PAID", "UNVERIFIED",
                "128.00", "10.00", "118.00", "0.00", null, null, null, null, "2026-10-02 10:00:00.000");
        order(jdbc, PENDING_SERVICE, OWNER, "PENDING_SERVICE", "PAID", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, null, "2026-10-03 10:00:00.000");
        order(jdbc, COMPLETED, OWNER, "COMPLETED", "PAID", "VERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, "2026-10-04 12:30:00.000", "2026-10-04 10:00:00.000");
        order(jdbc, CANCELED, OWNER, "CANCELED", "CLOSED", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, null, "2026-10-05 10:00:00.000");
        order(jdbc, REFUNDING, OWNER, "PENDING_SERVICE", "PAID", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", "6901", "AUTO_APPROVED", null, null, "2026-10-06 10:00:00.000");
        order(jdbc, REFUNDED, OWNER, "COMPLETED", "PAID", "VERIFIED",
                "88.00", "0.00", "88.00", "88.00", "6902", "APPROVED", null, "2026-10-05 11:00:00.000", "2026-10-07 10:00:00.000");
        order(jdbc, PARTIAL, OWNER, "COMPLETED", "PAID", "VERIFIED",
                "88.00", "0.00", "88.00", "30.00", "6903", null, "RESOLVED", "2026-10-06 11:00:00.000", "2026-10-08 10:00:00.000");
        order(jdbc, REFUND_PENDING, OWNER, "PENDING_SERVICE", "PAID", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, "PENDING_MERCHANT", null, null, "2026-10-09 10:00:00.000");
        order(jdbc, AFTERSALE, OWNER, "PENDING_SERVICE", "PAID", "UNVERIFIED",
                "88.00", "0.00", "88.00", "0.00", null, "REJECTED", "PROCESSING", null, "2026-10-10 10:00:00.000");
        order(jdbc, PENDING_PAY_2, OWNER, "PENDING_PAYMENT", "INIT", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, null, "2026-10-01 10:00:00.000");
        order(jdbc, FOREIGN, OTHER, "PENDING_SERVICE", "PAID", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", null, null, null, null, "2026-10-11 10:00:00.000");
        // SSOT late payment: the order stays closed (PAYMENT_TIMEOUT) with the auto full
        // refund already bound — every entry is closed while the money is on its way back.
        order(jdbc, LATE_PAYMENT, OWNER, "CANCELED", "PAID", "UNVERIFIED",
                "128.00", "0.00", "128.00", "0.00", "6904", null, null, null, "2026-10-03 12:00:00.000");
        state(jdbc, LATE_PAYMENT, "2026-10-03 00:30:00.000", null, "2026-10-03 01:00:00.000",
                "PAYMENT_TIMEOUT", null, null);
        // Action-window facts for the cited rules (contract 40 window, 48 confirm, 46 future
        // appointment for the reschedule row).
        state(jdbc, PENDING_PAY_1, "2030-01-01 00:00:00.000", "2026-10-01 09:30:00.000", null, null, null, null);
        state(jdbc, PENDING_PAY_2, "2030-01-01 00:00:00.000", "2026-10-01 09:30:00.000", null, null, null, null);
        state(jdbc, PENDING_CONFIRM, null, "2026-10-02 09:30:00.000", null, null,
                "2026-12-02 09:00:00.000", "2026-12-02 10:30:00.000");
        state(jdbc, PENDING_SERVICE, null, "2026-10-03 09:30:00.000", null, null, null, null);
        state(jdbc, COMPLETED, null, "2026-10-04 08:00:00.000", null, null, null, null);
        state(jdbc, REFUND_PENDING, null, "2026-10-09 09:30:00.000", null, null, null, null);
        state(jdbc, AFTERSALE, null, "2026-10-10 09:30:00.000", null, null, null, null);
    }

    /** Column overlays the base row builder does not model (read-side action windows). */
    private static void state(JdbcTemplate jdbc, long id, String paymentExpireAt, String confirmedAt,
            String canceledAt, String cancelReason, String appointmentStart, String appointmentEnd) {
        jdbc.update("UPDATE pet_order SET payment_expire_at=?, confirmed_at=?, canceled_at=?,"
                        + " cancel_reason=?, appointment_start_at=COALESCE(?, appointment_start_at),"
                        + " appointment_end_at=COALESCE(?, appointment_end_at) WHERE id=?",
                paymentExpireAt, confirmedAt, canceledAt, cancelReason,
                appointmentStart, appointmentEnd, id);
    }

    private static void order(JdbcTemplate jdbc, long id, long user, String stage, String payment,
            String verification, String original, String discount, String pay, String refunded,
            String refundOrder, String application, String aftersale, String verifiedAt, String createdAt) {
        // Pointer columns stay consistent with the status projections (schemas 48/49).
        Long applicationId = application == null ? null : id + 800_000L;
        Long aftersaleId = aftersale == null ? null : id + 900_000L;
        jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,"
                        + "pet_id,reservation_id,order_stage,payment_status,verification_status,"
                        + "current_refund_application_id,refund_order_id,current_aftersale_id,"
                        + "refund_application_status,aftersale_status,"
                        + "fulfillment_type,original_amount,discount_amount,pay_amount,refunded_amount,"
                        + "reschedule_count,confirm_mode,appointment_start_at,appointment_end_at,"
                        + "verified_at,version,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "'IN_STORE',?,?,?,?,0,NULL,"
                        + "?,?,?,0,?,?)",
                id, id + 500_000L, user, MERCHANT, STORE, SERVICE, PET, RESERVATION_BASE + (id % 100),
                stage, payment, verification, applicationId, refundOrder, aftersaleId,
                application, aftersale,
                original, discount, pay, refunded,
                createdAt.substring(0, 11) + "09:00:00.000", createdAt.substring(0, 11) + "10:30:00.000",
                verifiedAt, createdAt, createdAt);
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(com.petplatform.boot.config.CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", Long.toString(OWNER), Instant.now(),
                        "138****0000", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ApiResponse<Map<String, Object>> value) {
        return (List<Map<String, Object>>) value.data().get("items");
    }

    @Test
    void listIsNewestFirstWithTheIdTiebreakerAndComputedDisplayStatus() {
        ApiResponse<Map<String, Object>> value =
                orders.list(null, null, null, request("GET", "/api/v1/c/orders", null));
        assertEquals("SUCCESS", value.code());
        assertEquals(12L, value.data().get("total"));
        assertEquals(1, value.data().get("page"));
        assertEquals(20, value.data().get("pageSize"));
        List<Map<String, Object>> rows = items(value);
        assertEquals(12, rows.size());
        assertEquals(Long.toString(AFTERSALE), rows.get(0).get("orderId"));
        assertEquals("AFTERSALE", rows.get(0).get("displayStatus"));
        assertEquals(Long.toString(REFUND_PENDING), rows.get(1).get("orderId"));
        assertEquals("REFUND_PENDING_CONFIRM", rows.get(1).get("displayStatus"));
        assertEquals("PARTIAL_REFUND", rows.get(2).get("displayStatus"));
        assertEquals("REFUNDED", rows.get(3).get("displayStatus"));
        assertEquals("REFUNDING", rows.get(4).get("displayStatus"));
        assertEquals("CANCELED", rows.get(5).get("displayStatus"));
        assertEquals("COMPLETED", rows.get(6).get("displayStatus"));
        // The late-payment row displays as REFUNDING (refund bound, nothing projected yet).
        assertEquals(Long.toString(LATE_PAYMENT), rows.get(7).get("orderId"));
        assertEquals("REFUNDING", rows.get(7).get("displayStatus"));
        assertEquals("PENDING_SERVICE", rows.get(8).get("displayStatus"));
        assertEquals("PENDING_CONFIRM", rows.get(9).get("displayStatus"));
        // Same created_at: the higher id wins the stable tiebreak.
        assertEquals(Long.toString(PENDING_PAY_2), rows.get(10).get("orderId"));
        assertEquals(Long.toString(PENDING_PAY_1), rows.get(11).get("orderId"));
        // The foreign user's order never surfaces.
        assertTrue(rows.stream().noneMatch(row -> row.get("orderId").equals(Long.toString(FOREIGN))));
    }

    @Test
    void displayStatusFilterAgreesWithTheProjectedDetailStatus() {
        Map<String, Long> expected = Map.of(
                "PENDING_PAYMENT", PENDING_PAY_2, "PENDING_CONFIRM", PENDING_CONFIRM,
                "PENDING_SERVICE", PENDING_SERVICE, "COMPLETED", COMPLETED, "CANCELED", CANCELED,
                "REFUND_PENDING_CONFIRM", REFUND_PENDING, "REFUNDING", REFUNDING,
                "REFUNDED", REFUNDED, "PARTIAL_REFUND", PARTIAL, "AFTERSALE", AFTERSALE);
        for (var entry : expected.entrySet()) {
            ApiResponse<Map<String, Object>> value = orders.list(entry.getKey(), null, null,
                    request("GET", "/api/v1/c/orders", "displayStatus=" + entry.getKey()));
            long expectedTotal = entry.getKey().equals("PENDING_PAYMENT")
                    || entry.getKey().equals("REFUNDING") ? 2L : 1L;
            assertEquals(expectedTotal, value.data().get("total"), entry.getKey() + " total");
            for (Map<String, Object> row : items(value)) {
                assertEquals(entry.getKey(), row.get("displayStatus"));
                // Cross-check: the SQL filter and the Java computation agree row by row.
                ApiResponse<Map<String, Object>> detail = orders.detail(
                        (String) row.get("orderId"), request("GET", "/api/v1/c/orders/" + row.get("orderId"), null));
                assertEquals(entry.getKey(), detail.data().get("displayStatus"));
            }
        }
    }

    @Test
    void pagingIsStableAcrossPages() {
        ApiResponse<Map<String, Object>> page2 = orders.list(null, "2", "4",
                request("GET", "/api/v1/c/orders", "page=2&pageSize=4"));
        assertEquals(12L, page2.data().get("total"));
        assertEquals(4, items(page2).size());
        // Global newest-first order sliced at offset 4: REFUNDING, CANCELED, COMPLETED, LATE_PAYMENT.
        assertEquals(Long.toString(REFUNDING), items(page2).get(0).get("orderId"));
        assertEquals(Long.toString(LATE_PAYMENT), items(page2).get(3).get("orderId"));
        // A page past the end is empty, not an error.
        ApiResponse<Map<String, Object>> far = orders.list(null, "99", "20",
                request("GET", "/api/v1/c/orders", "page=99&pageSize=20"));
        assertEquals(0, items(far).size());
    }

    @Test
    void detailProjectsTheSection37FactFields() {
        ApiResponse<Map<String, Object>> detail = orders.detail(Long.toString(AFTERSALE),
                request("GET", "/api/v1/c/orders/" + AFTERSALE, null));
        Map<String, Object> data = detail.data();
        assertEquals(Long.toString(AFTERSALE), data.get("orderId"));
        assertEquals(Long.toString(AFTERSALE + 500_000L), data.get("orderNo"));
        assertEquals("AFTERSALE", data.get("displayStatus"));
        assertEquals("PENDING_SERVICE", data.get("orderStage"));
        assertEquals("PAID", data.get("paymentStatus"));
        // A rejected refund application is a plain fact here: it never affects the display tab.
        assertEquals("REJECTED", data.get("refundApplicationStatus"));
        assertNull(data.get("refundStatus"));
        assertEquals("PROCESSING", data.get("afterSaleStatus"));
        assertEquals("UNVERIFIED", data.get("verificationStatus"));
        assertEquals("88.00", data.get("payAmount"));
        assertEquals("2026-10-10T09:00:00.000Z", data.get("appointmentStart"));
        assertEquals("2026-10-10T10:30:00.000Z", data.get("appointmentEnd"));
        assertNull(data.get("verifiedAt"));

        // In-flight and succeeded refunds project the order-domain refund vocabulary.
        Map<String, Object> inFlight = orders.detail(Long.toString(REFUNDING),
                request("GET", "/api/v1/c/orders/" + REFUNDING, null)).data();
        assertEquals("REFUNDING", inFlight.get("displayStatus"));
        assertEquals("CREATED", inFlight.get("refundStatus"));
        assertEquals("AUTO_APPROVED", inFlight.get("refundApplicationStatus"));
        Map<String, Object> refunded = orders.detail(Long.toString(REFUNDED),
                request("GET", "/api/v1/c/orders/" + REFUNDED, null)).data();
        assertEquals("REFUNDED", refunded.get("displayStatus"));
        assertEquals("SUCCESS", refunded.get("refundStatus"));
        assertEquals("2026-10-05T11:00:00.000Z", refunded.get("verifiedAt"));
        Map<String, Object> completed = orders.detail(Long.toString(COMPLETED),
                request("GET", "/api/v1/c/orders/" + COMPLETED, null)).data();
        assertEquals("2026-10-04T12:30:00.000Z", completed.get("verifiedAt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> actions(Map<String, Object> data) {
        return (Map<String, Boolean>) data.get("actions");
    }

    private Map<String, Object> detailOf(long id) {
        return orders.detail(Long.toString(id),
                request("GET", "/api/v1/c/orders/" + id, null)).data();
    }

    /** The six OrderActions booleans at the fixed read moment, per cited rule (see 10 §3.7). */
    @Test
    void actionsFollowTheCitedRulesAcrossTheSeedTable() {
        // PENDING_PAYMENT inside the 40 window: only canPay.
        assertEquals(Map.of("canPay", true, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(PENDING_PAY_1)));
        // Contract 46: paid, unverified, first round, future appointment -> reschedule.
        assertEquals(Map.of("canPay", false, "canReschedule", true, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(PENDING_CONFIRM)));
        // Contract 49/47: confirmed pre-service order opens the refund application and the code.
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", true,
                "canShowVerificationCode", true, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(PENDING_SERVICE)));
        // Verified/completed on day 3: refund application, review (30d) and aftersale (7d).
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", true,
                "canShowVerificationCode", false, "canReview", true, "canApplyAfterSale", true),
                actions(detailOf(COMPLETED)));
        // SSOT: a pending refund application never blocks the code; it blocks a new application.
        assertEquals(Map.of("canPay", false, "canReschedule", true, "canApplyRefund", false,
                "canShowVerificationCode", true, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(REFUND_PENDING)));
        // SSOT: an unfulfilled aftersale (no refund_order) never blocks the code either.
        assertEquals(Map.of("canPay", false, "canReschedule", true, "canApplyRefund", true,
                "canShowVerificationCode", true, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(AFTERSALE)));
        // 07 §7.7: verified + partial refund keeps the review entry; money movements close refund/aftersale.
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", true, "canApplyAfterSale", false),
                actions(detailOf(PARTIAL)));
        // refund_order created or a full refund: every entry closed.
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(REFUNDING)));
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(REFUNDED)));
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(CANCELED)));
    }

    @Test
    void latePaymentOrderClosesEveryEntryAndListCarriesTheSameActions() {
        // SSOT: the late payment keeps the order closed; the automatic full refund is bound, so
        // nothing is payable, reschedulable, refundable, verifiable, reviewable or aftersale-able.
        assertEquals(Map.of("canPay", false, "canReschedule", false, "canApplyRefund", false,
                "canShowVerificationCode", false, "canReview", false, "canApplyAfterSale", false),
                actions(detailOf(LATE_PAYMENT)));

        // The list projection carries the identical actions object (same wire projection).
        ApiResponse<Map<String, Object>> list = orders.list("REFUNDING", null, null,
                request("GET", "/api/v1/c/orders", "displayStatus=REFUNDING"));
        List<Map<String, Object>> rows = items(list);
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals(actions(detailOf(Long.parseLong((String) row.get("orderId")))),
                    row.get("actions"));
        }
    }

    @Test
    void detailIsAntiEnumerationSafe() {
        // Foreign and absent orders share one indistinguishable answer.
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> orders.detail(Long.toString(FOREIGN),
                        request("GET", "/api/v1/c/orders/" + FOREIGN, null))).code());
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> orders.detail("9500000000000999",
                        request("GET", "/api/v1/c/orders/9500000000000999", null))).code());
        // Malformed path ids are plain 400s (same family as the credential route).
        for (String bad : new String[] {"abc", "01", "-1", "9223372036854775808", "1 "}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> orders.detail(bad, request("GET", "/api/v1/c/orders/" + bad, null))).code(), bad);
        }
        // No query string is accepted on the detail route.
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> orders.detail(Long.toString(AFTERSALE),
                        request("GET", "/api/v1/c/orders/" + AFTERSALE, "page=1"))).code());
    }

    @Test
    void listRejectsUnknownDuplicatedOrOutOfRangeParameters() {
        for (String query : new String[] {
                "displayStatus=BOGUS", "displayStatus=REFUNDING&foo=1", "foo=1",
                "page=0", "page=x", "page=-1", "page=99999999999",
                "pageSize=0", "pageSize=101", "pageSize=abc"}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> list(query)).code(), query);
        }
        // Valid values still pass through the same helper.
        assertEquals("SUCCESS", list("displayStatus=REFUNDING&page=1&pageSize=100").code());

        MockHttpServletRequest duplicated = request("GET", "/api/v1/c/orders", null);
        duplicated.addParameter("page", new String[] {"1", "2"});
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> orders.list(null, null, null, duplicated)).code());
        MockHttpServletRequest duplicatedStatus = request("GET", "/api/v1/c/orders", null);
        duplicatedStatus.addParameter("displayStatus", new String[] {"REFUNDING", "CANCELED"});
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> orders.list(null, null, null, duplicatedStatus)).code());
    }

    /** Binds the query string the way Spring would, then calls the controller method. */
    private ApiResponse<Map<String, Object>> list(String query) {
        MockHttpServletRequest req = request("GET", "/api/v1/c/orders", query);
        return orders.list(req.getParameter("displayStatus"), req.getParameter("page"),
                req.getParameter("pageSize"), req);
    }

    @Test
    void missingSessionIsUnauthorizedOnBothRoutes() {
        MockHttpServletRequest anonymousList = new MockHttpServletRequest("GET", "/api/v1/c/orders");
        assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> orders.list(null, null, null, anonymousList)).code());
        MockHttpServletRequest anonymousDetail =
                new MockHttpServletRequest("GET", "/api/v1/c/orders/" + AFTERSALE);
        assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> orders.detail(Long.toString(AFTERSALE), anonymousDetail)).code());
    }

    @Test
    void sliceStaysDefaultOffUntilTheCSessionSwitchIsEnabled() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.config.OrderQueryReadConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(OrderQueryApiImpl.class).isEmpty());
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(COrderQueryController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(COrderQueryController.class).isEmpty());
                });
    }

    /** Fresh isolated real MySQL with the authoritative schema; dropped on close, even on failure. */
    private static final class Fixture implements AutoCloseable {
        private static final String PREFIX = "corderq_";
        private static final List<String> SCHEMA = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "13-Async-Infra-Schema-v0.1.sql",
                "28-Merchant-Agreement-Schema-v0.1.sql",
                "29-Merchant-Application-Schema-v0.1.sql",
                "33-Service-Write-Schema-v0.1.sql",
                "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                "38-Booking-Create-Schema-v0.1.sql",
                "39-Booking-Expiry-Schema-v0.1.sql",
                "40-Payment-Foundation-Schema-v0.1.sql",
                "41-Payment-Dispatch-Schema-v0.1.sql",
                "42-Late-Refund-Execution-Schema-v0.1.sql",
                "45-Merchant-Order-Actions-Schema-v0.1.sql",
                "43-Payment-Refund-Dispatch-Schema-v0.1.sql",
                "44-Late-Refund-Order-Projection-Schema-v0.1.sql",
                "46-Order-Reschedule-Schema-v0.1.sql",
                "47-Verification-Credential-Schema-v0.1.sql",
                "48-Verification-Completion-Schema-v0.1.sql",
                "49-Refund-Application-Schema-v0.1.sql");

        private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private boolean created;

        private Fixture() throws Exception {
            var environment = System.getenv();
            String prefix = environment.containsKey("BOOKING_MYSQL_URL") ? "BOOKING" : "AUTH";
            String url = environment.get(prefix + "_MYSQL_URL");
            String user = environment.get(prefix + "_MYSQL_USER");
            String password = environment.get(prefix + "_MYSQL_PASSWORD");
            if (url == null || user == null || password == null) {
                throw new IllegalStateException("set BOOKING_MYSQL_URL/USER/PASSWORD or "
                        + prefix + "_MYSQL_URL/USER/PASSWORD for the isolated MySQL test");
            }
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must be a local server root");
            }
            admin = new JdbcTemplate(connect(url, user, password));
            source = connect(url + name
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try {
                String version = admin.queryForObject("SELECT VERSION()", String.class);
                if (version == null || !version.startsWith("8.")) {
                    throw new IllegalStateException("Real MySQL 8 is required");
                }
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                assertNotNull(root, "Repository root with the schema scripts was not found");
                for (String schema : SCHEMA) {
                    Path script = root.resolve("docs/03-database/" + schema);
                    try (Connection connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(script), StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static DataSource connect(String url, String user, String password) {
            return new DriverManagerDataSource(url, user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            if (created) {
                if (!name.startsWith(PREFIX) || !name.matches("[a-z0-9_]+")) {
                    throw new IllegalStateException("Refusing to drop unexpected database name: " + name);
                }
                admin.execute("DROP DATABASE `" + name + "`");
                created = false;
            }
        }
    }
}
