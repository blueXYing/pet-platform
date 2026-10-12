package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Review appeal HTTP acceptance (REV-002: SSOT §11.3 + contract 56 + 06号 review_appeal): the
 * OWNER read/appeal faces and the ADMIN_WEB list/detail/decision face over real loopback HTTP,
 * real MINIAPP/ADMIN_WEB sessions, isolated MySQL and Redis, and every authority fact produced
 * by the real kernels (store guard, MER OWNER link, AUTH admin action check). Covers the
 * one-appeal-per-review rule (uk + service guard + race backstop code path), protected replay,
 * the uniform anti-enumeration 404, the terminal decision (APPROVED hides the review,
 * REJECTED keeps it published, no re-decision), and the default-off assembly.
 */
class ReviewAppealHttpAcceptanceTest {
    private static final String MERCHANT = AfterSaleHttpFixture.MERCHANT;
    private static final String STORE = AfterSaleHttpFixture.STORE;

    private static Map<String, Object> reviewToggles() {
        return Map.of("pet.review.enabled", true, "pet.review.http.enabled", true,
                "pet.review.appeal.enabled", true, "pet.review.appeal.http.enabled", true);
    }

    /** Creates one published review over the real C face and returns its id. */
    private static String publishedReview(AfterSaleHttpFixture f) throws Exception {
        String order = f.verifiedOrder();
        var created = f.send("POST", "/c/orders/" + order + "/reviews",
                Map.of("storeScore", 5, "serviceScore", 4, "staffScore", 3,
                        "content", "服务尚可，但上门时间迟到了二十分钟"),
                f.buyerToken);
        assertEquals(201, created.status(), created.toString());
        return created.value("reviewId");
    }

    private static String appealPath(String reviewId) {
        return "/merchant/reviews/" + reviewId + "/appeal";
    }

    /** Full M lifecycle: list projection, one 201 appeal, protected replay, one-time rule. */
    @Test void ownerListAppealReplayAndOneTimeRule() throws Exception {
        try (var f = new AfterSaleHttpFixture(reviewToggles())) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String reviewId = publishedReview(f);
            assertEquals("PUBLISHED", f.text("SELECT visibility_status FROM review"));

            // Owner list: one review, no appeal yet (appealStatus/appealId explicitly null).
            var list = f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT + "&storeId=" + STORE,
                    null, f.ownerToken);
            assertEquals(200, list.status(), list.toString());
            assertEquals(1L, ((Number) list.data().get("total")).longValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> item = ((List<Map<String, Object>>) list.data().get("items")).get(0);
            assertEquals(reviewId, Objects.toString(item.get("reviewId")));
            assertEquals("4.2", Objects.toString(item.get("compositeScore")));
            assertEquals("PUBLISHED", Objects.toString(item.get("visibilityStatus")));
            assertNull(item.get("appealStatus"));
            assertNull(item.get("appealId"));
            assertEquals("no-store, private", list.headers().firstValue("Cache-Control").orElseThrow());

            // Detail carries the same projection before any appeal exists.
            var detail = f.send("GET", "/merchant/reviews/" + reviewId, null, f.ownerToken);
            assertEquals(200, detail.status());
            assertEquals("服务尚可，但上门时间迟到了二十分钟", detail.value("content"));
            assertNull(detail.value("appealReason"));

            // Strict transport: unknown body field, non-UUID request id and blank reason are 400.
            assertError(f.sendRaw("POST", appealPath(reviewId),
                    "{\"reason\":\"内容失实，涉及辱骂与不实信息\",\"extra\":1}", bearer(f.ownerToken)),
                    400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", appealPath(reviewId), Map.of("reason", "内容失实"),
                    Map.of("Authorization", "Bearer " + f.ownerToken, "X-Request-Id", "not-a-uuid")),
                    400, "COMMON_INVALID_ARGUMENT");
            for (String bad : new String[] {"", "   "}) {
                assertError(f.send("POST", appealPath(reviewId), Map.of("reason", bad), f.ownerToken),
                        400, "COMMON_INVALID_ARGUMENT");
            }
            assertError(f.sendRaw("POST", appealPath(reviewId),
                    "{\"reason\":\"" + "长".repeat(1001) + "\"}", bearer(f.ownerToken)),
                    400, "COMMON_INVALID_ARGUMENT");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review_appeal"));

            // First appeal: 201, fixed receipt, SUBMITTED terminal-eligible state.
            String key = UUID.randomUUID().toString();
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + f.ownerToken, "X-Request-Id", key);
            var appealed = f.send("POST", appealPath(reviewId),
                    Map.of("reason", "评价内容失实，涉及辱骂与不实信息"), headers);
            assertEquals(201, appealed.status(), appealed.toString());
            String appealId = appealed.value("appealId");
            assertNotNull(appealId);
            assertEquals("SUBMITTED", appealed.value("status"));
            assertEquals(1L, f.count("SELECT COUNT(*) FROM review_appeal WHERE status='SUBMITTED'"));

            // Protected replay: same key + same reason answers 200 with the first receipt.
            var replay = f.send("POST", appealPath(reviewId),
                    Map.of("reason", "评价内容失实，涉及辱骂与不实信息"), headers);
            assertEquals(200, replay.status());
            assertEquals(appealed.data(), replay.data());
            // Same key, different reason: 409 idempotency conflict, no second row.
            assertError(f.send("POST", appealPath(reviewId),
                    Map.of("reason", "换了理由的重复请求"), headers), 409, "IDEMPOTENCY_KEY_CONFLICT");
            // A fresh key hits SSOT §11.3's one-appeal-per-review admission.
            assertError(f.send("POST", appealPath(reviewId),
                    Map.of("reason", "再次申诉同一评价"), f.ownerToken), 409, "REVIEW_APPEAL_ALREADY_USED");
            assertEquals(1, f.count("SELECT COUNT(*) FROM review_appeal"));

            // The list/detail projections now overlay the appeal facts.
            var afterList = f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT + "&storeId=" + STORE,
                    null, f.ownerToken);
            @SuppressWarnings("unchecked")
            Map<String, Object> appealedItem =
                    ((List<Map<String, Object>>) afterList.data().get("items")).get(0);
            assertEquals("SUBMITTED", Objects.toString(appealedItem.get("appealStatus")));
            assertEquals(appealId, Objects.toString(appealedItem.get("appealId")));
            var afterDetail = f.send("GET", "/merchant/reviews/" + reviewId, null, f.ownerToken);
            assertEquals("评价内容失实，涉及辱骂与不实信息", afterDetail.value("appealReason"));
            assertEquals("SUBMITTED", afterDetail.value("appealStatus"));
        }
    }

    /** Owner admission and anti-enumeration: non-OWNER 403 on the list, uniform 404 by id. */
    @Test void ownerAdmissionAndAntiEnumeration() throws Exception {
        try (var f = new AfterSaleHttpFixture(reviewToggles())) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String reviewId = publishedReview(f);

            // The buyer session is a USER, not this store's OWNER: the list face denies 403.
            assertError(f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT + "&storeId=" + STORE,
                    null, f.buyerToken), 403, "COMMON_FORBIDDEN");
            assertError(f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT + "&storeId=" + STORE,
                    null, f.otherToken), 403, "COMMON_FORBIDDEN");
            // The OWNER claiming foreign coordinates is equally denied.
            assertError(f.send("GET", "/merchant/reviews?merchantId=900199999999&storeId=" + STORE,
                    null, f.ownerToken), 403, "COMMON_FORBIDDEN");
            // Anonymous: 401 on every face.
            assertEquals(401, f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT
                    + "&storeId=" + STORE, null, (String) null).status());
            assertEquals(401, f.send("POST", appealPath(reviewId),
                    Map.of("reason", "匿名申诉"), (String) null).status());

            // By-id faces: a foreign session and an unknown review share the same 404 (56号).
            assertError(f.send("GET", "/merchant/reviews/" + reviewId, null, f.buyerToken),
                    404, "REVIEW_NOT_FOUND");
            assertError(f.send("POST", appealPath(reviewId), Map.of("reason", "他人评价申诉"),
                    f.buyerToken), 404, "REVIEW_NOT_FOUND");
            assertError(f.send("GET", "/merchant/reviews/900199999999", null, f.ownerToken),
                    404, "REVIEW_NOT_FOUND");
            assertError(f.send("POST", appealPath("900199999999"), Map.of("reason", "未知评价"),
                    f.ownerToken), 404, "REVIEW_NOT_FOUND");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review_appeal"));

            // Frozen store: the read stays available (existing-store family), the write denies.
            f.sql("UPDATE merchant_store SET status='FROZEN' WHERE id=" + STORE);
            assertEquals(200, f.send("GET", "/merchant/reviews/" + reviewId, null, f.ownerToken).status());
            assertError(f.send("POST", appealPath(reviewId), Map.of("reason", "冻结后的申诉"), f.ownerToken),
                    404, "REVIEW_NOT_FOUND");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review_appeal"));
            f.sql("UPDATE merchant_store SET status='ACTIVE' WHERE id=" + STORE);
        }
    }

    /** Admin lifecycle: list/detail, terminal APPROVED (review hidden), replay, no re-decision. */
    @Test void adminListDecisionReplayAndFinality() throws Exception {
        try (var f = new AfterSaleHttpFixture(reviewToggles())) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String reviewId = publishedReview(f);
            var appealed = f.send("POST", appealPath(reviewId),
                    Map.of("reason", "评价包含辱骂内容，与事实不符"), f.ownerToken);
            assertEquals(201, appealed.status(), appealed.toString());
            String appealId = appealed.value("appealId");

            // Admin list: the SUBMITTED filter and the unfiltered view both find it.
            var submitted = f.send("GET", "/admin/review-appeals?status=SUBMITTED", null, f.adminToken);
            assertEquals(200, submitted.status(), submitted.toString());
            assertEquals(1L, ((Number) submitted.data().get("total")).longValue());
            var all = f.send("GET", "/admin/review-appeals", null, f.adminToken);
            assertEquals(1L, ((Number) all.data().get("total")).longValue());
            var decidedOnly = f.send("GET", "/admin/review-appeals?status=APPROVED", null, f.adminToken);
            assertEquals(0L, ((Number) decidedOnly.data().get("total")).longValue());
            assertError(f.send("GET", "/admin/review-appeals?status=UNKNOWN", null, f.adminToken),
                    400, "COMMON_INVALID_ARGUMENT");

            // Admin detail: the appeal facts plus the appealed review projection.
            var detail = f.send("GET", "/admin/review-appeals/" + appealId, null, f.adminToken);
            assertEquals(200, detail.status(), detail.toString());
            assertEquals("评价包含辱骂内容，与事实不符", detail.value("reason"));
            @SuppressWarnings("unchecked")
            Map<String, Object> review = (Map<String, Object>) detail.data().get("review");
            assertEquals(reviewId, Objects.toString(review.get("reviewId")));
            assertEquals("PUBLISHED", Objects.toString(review.get("visibilityStatus")));
            assertNull(detail.value("decidedBy"));

            // Unknown appeal: 404; the MINIAPP session cannot read the admin face (401/403).
            assertError(f.send("GET", "/admin/review-appeals/900199999999", null, f.adminToken),
                    404, "COMMON_NOT_FOUND");
            assertEquals(401, f.send("GET", "/admin/review-appeals", null, (String) null).status());
            assertError(f.send("GET", "/admin/review-appeals", null, f.ownerToken),
                    401, "COMMON_UNAUTHORIZED");

            // Terminal decision APPROVED: the review stops public display in the same transaction.
            String key = UUID.randomUUID().toString();
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + f.adminToken, "X-Request-Id", key);
            var decision = f.send("POST", "/admin/review-appeals/" + appealId + "/decision",
                    Map.of("decisionType", "APPROVED", "reason", "核查属实，评价内容违规，隐藏处理"), headers);
            assertEquals(200, decision.status(), decision.toString());
            assertEquals("APPROVED", decision.value("status"));
            assertEquals("HIDDEN", decision.value("reviewVisibility"));
            assertEquals("HIDDEN", f.text("SELECT visibility_status FROM review"));
            assertEquals("APPROVED", f.text("SELECT status FROM review_appeal"));
            assertNotNull(f.text("SELECT decided_by FROM review_appeal WHERE id=" + appealId));

            // Protected replay: the same key returns the original receipt without a re-decision.
            var replay = f.send("POST", "/admin/review-appeals/" + appealId + "/decision",
                    Map.of("decisionType", "APPROVED", "reason", "核查属实，评价内容违规，隐藏处理"), headers);
            assertEquals(200, replay.status());
            assertEquals(decision.data(), replay.data());
            // A fresh key cannot re-decide the terminal appeal (56号 §4 finality).
            assertError(f.send("POST", "/admin/review-appeals/" + appealId + "/decision",
                    Map.of("decisionType", "REJECTED", "reason", "尝试改判"), f.adminToken),
                    409, "COMMON_CONFLICT");
            // Strict transport: the decision body is exactly {decisionType, reason}.
            assertError(f.sendRaw("POST", "/admin/review-appeals/" + appealId + "/decision",
                    "{\"decisionType\":\"APPROVED\",\"reason\":\"x\",\"extra\":1}", bearer(f.adminToken)),
                    400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", "/admin/review-appeals/" + appealId + "/decision",
                    Map.of("decisionType", "PROCESSING", "reason", "非法类型"), f.adminToken),
                    400, "COMMON_INVALID_ARGUMENT");

            // The M projections now show the terminal state on both review and appeal.
            var ownerDetail = f.send("GET", "/merchant/reviews/" + reviewId, null, f.ownerToken);
            assertEquals("HIDDEN", ownerDetail.value("visibilityStatus"));
            assertEquals("APPROVED", ownerDetail.value("appealStatus"));
            assertNotNull(ownerDetail.value("decisionReason"));
            assertNotNull(ownerDetail.value("decidedAt"));
        }
    }

    /** REJECTED keeps the review published; a second review on another order still appeals. */
    @Test void rejectedDecisionKeepsReviewPublished() throws Exception {
        try (var f = new AfterSaleHttpFixture(reviewToggles())) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String reviewId = publishedReview(f);
            var appealed = f.send("POST", appealPath(reviewId),
                    Map.of("reason", "我认为该评价描述与服务实际不符"), f.ownerToken);
            assertEquals(201, appealed.status());
            String appealId = appealed.value("appealId");

            var decision = f.send("POST", "/admin/review-appeals/" + appealId + "/decision",
                    Map.of("decisionType", "REJECTED", "reason", "评价内容与订单事实一致，维持展示"),
                    f.adminToken);
            assertEquals(200, decision.status(), decision.toString());
            assertEquals("REJECTED", decision.value("status"));
            assertEquals("PUBLISHED", decision.value("reviewVisibility"));
            assertEquals("PUBLISHED", f.text("SELECT visibility_status FROM review"));
            assertNotNull(f.text("SELECT decided_at FROM review_appeal WHERE id=" + appealId));

            // The one-appeal rule holds after a rejection too (SSOT §11.3: no second chance).
            assertError(f.send("POST", appealPath(reviewId),
                    Map.of("reason", "被驳回后再次申诉"), f.ownerToken), 409, "REVIEW_APPEAL_ALREADY_USED");
            assertEquals(1, f.count("SELECT COUNT(*) FROM review_appeal"));
        }
    }

    /** Default-off: without pet.review.appeal.http.enabled every new route stays unreachable. */
    @Test void appealFaceStaysOffWithoutItsSwitch() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.review.enabled", true, "pet.review.http.enabled", true))) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String reviewId = publishedReview(f);
            assertEquals(403, f.send("GET", "/merchant/reviews?merchantId=" + MERCHANT
                    + "&storeId=" + STORE, null, f.ownerToken).status());
            assertEquals(403, f.send("GET", "/merchant/reviews/" + reviewId, null, f.ownerToken).status());
            assertEquals(403, f.send("POST", appealPath(reviewId),
                    Map.of("reason", "未开放的申诉面"), f.ownerToken).status());
            assertEquals(403, f.send("GET", "/admin/review-appeals", null, f.adminToken).status());
            assertEquals(403, f.send("POST", "/admin/review-appeals/900199999999/decision",
                    Map.of("decisionType", "APPROVED", "reason", "未开放"), f.adminToken).status());
            assertEquals(0, f.count("SELECT COUNT(*) FROM review_appeal"));
        }
    }

    private static void assertError(AfterSaleHttpFixture.Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply + " message=" + reply.envelope().get("message"));
        assertEquals(code, reply.envelope().get("code"), reply + " message=" + reply.envelope().get("message"));
        assertNull(reply.envelope().get("data"));
    }
}
