package com.petplatform.boot.booking;

import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Review C HTTP face acceptance (REV-001: contract 10 §3.14 + SSOT §11 + 07号 §7.7/§14, this
 * slice): both operations over real loopback HTTP, real MINIAPP sessions, isolated MySQL and
 * Redis, with every admission fact produced by the real order/verification kernels. Covers
 * the eligibility truth table (verified/30d window/partial refund eligible-but-excluded, the
 * 2026-10-07 refund-success ruling, unverified), the create lifecycle (201 first submit,
 * protected same-key replay 200, same-key-different-params 409, one-review-per-order 409,
 * score boundaries, 40/40/20 composite), the session/anti-enumeration boundary, strict JSON
 * transport shape and default-off assembly.
 */
class ReviewHttpAcceptanceTest {
    private static String eligibilityPath(String order) {
        return "/c/orders/" + order + "/review-eligibility";
    }

    private static String reviewsPath(String order) {
        return "/c/orders/" + order + "/reviews";
    }

    private static Map<String, Object> body(int store, int service, int staff) {
        return Map.of("storeScore", store, "serviceScore", service, "staffScore", staff);
    }

    /** Verified order via the real credential/verification kernels (buyer 710100). */
    private static String verified(AfterSaleHttpFixture f) throws Exception {
        return f.verifiedOrder();
    }

    /** §3.14 truth table over one boot: verified, window expired, refund rulings, unverified. */
    @Test void eligibilityTruthTableAndAntiEnumeration() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.review.enabled", true, "pet.review.http.enabled", true))) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));

            // Verified + inside the 30-day window: eligible, score included, deadline fact.
            String order = verified(f);
            var ok = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(200, ok.status(), ok.toString());
            assertEquals(Boolean.TRUE, ok.data().get("eligible"));
            assertEquals(Boolean.TRUE, ok.data().get("scoreIncluded"));
            assertNull(ok.data().get("rejectCode"));
            assertEquals("no-store, private", ok.headers().firstValue("Cache-Control").orElseThrow());
            String verifiedAt = f.text("SELECT DATE_FORMAT(verified_at,'%Y-%m-%dT%H:%i:%s.%f') FROM pet_order WHERE id=" + order);
            String expected = verifiedAt.replaceAll("^(\\d+-\\d+-\\d+T\\d+:\\d+:\\d+)\\.(\\d\\d\\d)\\d+$", "$1.$2Z");
            // verified_at + 30d in the same UTC millisecond frame (appendInstant(3) keeps .000).
            var deadline = java.time.Instant.parse(expected).plus(java.time.Duration.ofDays(30));
            String wire = new java.time.format.DateTimeFormatterBuilder().appendInstant(3)
                    .toFormatter().format(deadline);
            assertEquals(wire, ok.value("reviewDeadline"));

            // 30-day window expired (SSOT §11: the window anchors at the verification time).
            // The order read slice runs on the real system clock (its #123 wiring), so the
            // expired seed uses the real-now frame like OrderQueryHttpTest; the fixture's
            // parked 2030 verification stays inside the window for the checks above.
            f.sql("UPDATE pet_order SET verified_at='2026-09-01 09:00:20.000' WHERE id=" + order);
            var expired = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(200, expired.status());
            assertEquals(Boolean.FALSE, expired.data().get("eligible"));
            assertEquals("REVIEW_WINDOW_EXPIRED", expired.value("rejectCode"));
            assertNotNull(expired.value("reviewDeadline"));
            f.sql("UPDATE pet_order SET verified_at=DATE_ADD(verified_at,INTERVAL 31 DAY) WHERE id=" + order);

            // 2026-10-07 user ruling regression: full refund success (REFUNDED) is not reviewable.
            f.sql("UPDATE pet_order SET refund_order_id=910501, refunded_amount=pay_amount WHERE id=" + order);
            var refunded = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(Boolean.FALSE, refunded.data().get("eligible"));
            assertEquals("REVIEW_NOT_ELIGIBLE", refunded.value("rejectCode"));

            // In-flight refund (REFUNDING: refund_order exists, nothing refunded yet) is not reviewable.
            f.sql("UPDATE pet_order SET refunded_amount=0 WHERE id=" + order);
            var refunding = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(Boolean.FALSE, refunding.data().get("eligible"));
            assertEquals("REVIEW_NOT_ELIGIBLE", refunding.value("rejectCode"));

            // SSOT §11.2: partial refund after verification stays eligible but score-excluded.
            f.sql("UPDATE pet_order SET refunded_amount=1.00, pay_amount=128.00 WHERE id=" + order);
            var partial = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(Boolean.TRUE, partial.data().get("eligible"));
            assertEquals(Boolean.FALSE, partial.data().get("scoreIncluded"));
            assertNull(partial.value("rejectCode"));
            f.sql("UPDATE pet_order SET refund_order_id=NULL, refunded_amount=0 WHERE id=" + order);

            // Unverified (PENDING_SERVICE): not reviewable, no deadline fact exists. The fact
            // flip keeps one fixture order (the seeded window has one bookable slot at this
            // clock); §7.7 reads exactly these projection columns.
            f.sql("UPDATE pet_order SET verification_status='UNVERIFIED', verified_at=NULL, order_stage='PENDING_SERVICE' WHERE id=" + order);
            var raw = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(200, raw.status());
            assertEquals(Boolean.FALSE, raw.data().get("eligible"));
            assertEquals("REVIEW_NOT_VERIFIED", raw.value("rejectCode"));
            assertNull(raw.data().get("reviewDeadline"));

            // Session boundary: anonymous 401; a foreign buyer and an unknown order share the
            // order read's anti-enumeration 404; neither leaves any state behind.
            assertEquals(401, f.send("GET", eligibilityPath(order), null, (String) null).status());
            assertError(f.send("GET", eligibilityPath(order), null, f.otherToken), 404, "COMMON_NOT_FOUND");
            assertError(f.send("GET", eligibilityPath("900199999999999"), null, f.buyerToken), 404, "COMMON_NOT_FOUND");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));

            // Transport shape: no query parameters.
            assertError(f.send("GET", eligibilityPath(order) + "?x=1", null, f.buyerToken),
                    400, "COMMON_INVALID_ARGUMENT");
        }
    }

    /** Create lifecycle: 201/replay 200, idempotency conflict, one-per-order, composite, media gate. */
    @Test void createReviewLifecycleReplayConflictsAndScoreBoundaries() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.review.enabled", true, "pet.review.http.enabled", true))) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String order = verified(f);
            String path = reviewsPath(order);

            // Session boundary first: anonymous 401, foreign/unknown order the shared 404.
            assertEquals(401, f.send("POST", path, body(5, 4, 3), (String) null).status());
            assertError(f.send("POST", path, body(5, 4, 3), f.otherToken), 404, "COMMON_NOT_FOUND");
            assertError(f.send("POST", reviewsPath("900199999999999"), body(5, 4, 3), f.buyerToken),
                    404, "COMMON_NOT_FOUND");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));

            // Transport shape: strict JSON, no query, terminal request id.
            for (String bad : new String[] {
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":3,\"extra\":1}",
                    "{\"storeScore\":5,\"serviceScore\":4}",
                    "{\"storeScore\":null,\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":true,\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":4.5,\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":\"5\",\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":0,\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":6,\"serviceScore\":4,\"staffScore\":3}",
                    "{\"storeScore\":5,\"serviceScore\":0,\"staffScore\":3}",
                    "{\"storeScore\":5,\"serviceScore\":6,\"staffScore\":3}",
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":0}",
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":6}",
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":3,\"content\":\""
                            + "x".repeat(2001) + "\"}",
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":3,\"mediaFileIds\":[\"9001\"]}",
                    "{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":3}{\"storeScore\":5,\"serviceScore\":4,\"staffScore\":3}"}) {
                assertError(f.sendRaw("POST", path, bad, bearer(f.buyerToken)), 400, "COMMON_INVALID_ARGUMENT");
            }
            assertError(f.send("POST", path + "?x=1", Map.of("storeScore", 5, "serviceScore", 4, "staffScore", 3),
                    f.buyerToken), 400, "COMMON_INVALID_ARGUMENT");
            assertError(f.send("POST", path, Map.of("storeScore", 5, "serviceScore", 4, "staffScore", 3),
                    Map.of("Authorization", "Bearer " + f.buyerToken, "X-Request-Id", "not-a-uuid")),
                    400, "COMMON_INVALID_ARGUMENT");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));

            // First submit: 201, fixed receipt, kernel-owned 40/40/20 composite (REV-008: 5/4/3 -> 4.2).
            String key = UUID.randomUUID().toString();
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("storeScore", 5);
            input.put("serviceScore", 4);
            input.put("staffScore", 3);
            input.put("content", "整体服务很好");
            input.put("mediaFileIds", List.of());
            var created = f.send("POST", path, input, headers(f.buyerToken, key));
            assertEquals(201, created.status(), created.toString());
            String reviewId = created.value("reviewId");
            assertNotNull(reviewId);
            assertEquals(Boolean.TRUE, created.data().get("scoreIncluded"));
            assertEquals("no-store, private", created.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("4.2", f.text("SELECT CAST(composite_score AS CHAR) FROM review"));
            assertEquals("PUBLISHED", f.text("SELECT visibility_status FROM review"));
            assertEquals(1L, f.count("SELECT COUNT(*) FROM review WHERE score_included=1"));
            assertEquals(1L, f.count("SELECT COUNT(*) FROM command_idempotency WHERE status='SUCCEEDED'"));

            // Protected replay: same key + same params re-proves the session and answers 200/first receipt.
            var replay = f.send("POST", path, input, headers(f.buyerToken, key));
            assertEquals(200, replay.status());
            assertEquals(created.data(), replay.data());
            // Same key, different params stays a 409 idempotency conflict without a second row.
            Map<String, Object> changed = new LinkedHashMap<>(input);
            changed.put("staffScore", 4);
            assertError(f.send("POST", path, changed, headers(f.buyerToken, key)), 409, "IDEMPOTENCY_KEY_CONFLICT");
            // A fresh key hits the one-review-per-order admission (registry 12 §11).
            assertError(f.send("POST", path, input, f.buyerToken), 409, "REVIEW_ALREADY_EXISTS");
            assertEquals(1, f.count("SELECT COUNT(*) FROM review"));

            // The eligibility face now reports the review domain's own overlay.
            var after = f.send("GET", eligibilityPath(order), null, f.buyerToken);
            assertEquals(Boolean.FALSE, after.data().get("eligible"));
            assertEquals("REVIEW_ALREADY_EXISTS", after.value("rejectCode"));
        }
    }

    /** SSOT §11.2: a partial refund admitted after verification creates scoreIncluded=false. */
    @Test void partialRefundReviewIsPublicButScoreExcluded() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.review.enabled", true, "pet.review.http.enabled", true))) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String order = verified(f);
            f.sql("UPDATE pet_order SET refund_order_id=910601, refunded_amount=28.00, pay_amount=128.00 WHERE id=" + order);
            var created = f.send("POST", reviewsPath(order), Map.of(
                    "storeScore", 5, "serviceScore", 5, "staffScore", 5), f.buyerToken);
            assertEquals(201, created.status(), created.toString());
            assertEquals(Boolean.FALSE, created.data().get("scoreIncluded"));
            assertEquals(1L, f.count("SELECT COUNT(*) FROM review WHERE score_included=0"));
            assertEquals("5.0", f.text("SELECT CAST(composite_score AS CHAR) FROM review"));
        }
    }

    /** REV-002/REV-007 negatives on the write face: unverified and refund-excluded are 409. */
    @Test void createRejectsUnverifiedAndRefundedOrders() throws Exception {
        try (var f = new AfterSaleHttpFixture(Map.of(
                "pet.review.enabled", true, "pet.review.http.enabled", true))) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            // One verified order; §7.7 admission reads exactly the projection facts below.
            String order = verified(f);
            // Unverified (10号 §3.14 / test matrix REV-002 semantics).
            f.sql("UPDATE pet_order SET verification_status='UNVERIFIED' WHERE id=" + order);
            assertError(f.send("POST", reviewsPath(order), body(5, 4, 3), f.buyerToken),
                    409, "REVIEW_NOT_VERIFIED");
            // Refund success ruling: REFUNDED and REFUNDING both stay 409 REVIEW_NOT_ELIGIBLE.
            f.sql("UPDATE pet_order SET verification_status='VERIFIED', refund_order_id=910701, refunded_amount=pay_amount WHERE id=" + order);
            assertError(f.send("POST", reviewsPath(order), body(5, 4, 3), f.buyerToken),
                    409, "REVIEW_NOT_ELIGIBLE");
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));
        }
    }

    /** Default-off assembly: without pet.review.http.enabled the routes stay unreachable (403). */
    @Test void reviewFaceStaysOffWithoutItsSwitch() throws Exception {
        try (var f = new AfterSaleHttpFixture()) {
            f.ordinary.t.r.f.f.db.script("14-Command-Idempotency-Schema-v0.1.sql");
            String order = verified(f);
            assertEquals(403, f.send("GET", eligibilityPath(order), null, f.buyerToken).status());
            assertEquals(403, f.send("POST", reviewsPath(order), body(5, 4, 3), f.buyerToken).status());
            assertEquals(0, f.count("SELECT COUNT(*) FROM review"));
        }
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
