package com.petplatform.boot.adapter.web.review;

import static com.petplatform.boot.adapter.web.review.ReviewAppealHttpSupport.*;

import com.petplatform.review.api.command.ReviewCommandApi;
import com.petplatform.review.api.query.ReviewQueryApi;
import com.petplatform.review.api.query.ReviewQueryApi.AppealDetail;
import com.petplatform.review.api.query.ReviewQueryApi.AppealListQuery;
import com.petplatform.review.api.query.ReviewQueryApi.AppealSummary;
import com.petplatform.review.api.query.ReviewQueryApi.ReviewDetail;
import com.petplatform.review.api.query.ReviewQueryApi.ReviewSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract56 review-appeal HTTP face (REV-002): the OWNER reads/appeals over
 * /api/v1/merchant/reviews and the ADMIN_WEB operator lists/decides over
 * /api/v1/admin/review-appeals. No aftersale re-review surface exists here. Strict JSON
 * (unknown fields 400), terminal X-Request-Id UUID idempotency on writes (201 first submit /
 * 200 protected replay on the appeal), decimal-string IDs and no-store on every reply; every
 * authority fact is re-proven inside the kernel's store-guarded transaction.
 */
@RestController
@ConditionalOnProperty(name = "pet.review.appeal.http.enabled", havingValue = "true")
public final class MerchantReviewAppealController {

    private static final Set<String> APPEAL_BODY = Set.of("reason");
    private static final Set<String> DECISION_BODY = Set.of("decisionType", "reason");
    private static final Set<String> APPEAL_STATUSES = Set.of(
            "SUBMITTED", "PROCESSING", "APPROVED", "REJECTED");

    private final ReviewCommandApi commands;
    private final ReviewQueryApi queries;

    public MerchantReviewAppealController(ReviewCommandApi commands, ReviewQueryApi queries) {
        this.commands = Objects.requireNonNull(commands);
        this.queries = Objects.requireNonNull(queries);
    }

    // -------------------------------------------------------------- M face (OWNER)

    @GetMapping("/api/v1/merchant/reviews")
    public ResponseEntity<Map<String, Object>> listStore(HttpServletRequest r) {
        onlyParameters(r, "merchantId", "storeId", "page", "pageSize");
        noBody(r);
        var query = new ReviewQueryApi.StoreReviewListQuery(context(r, false, null),
                id(r.getParameter("merchantId")), id(r.getParameter("storeId")),
                number(r.getParameter("page"), 1, 10_000),
                number(r.getParameter("pageSize"), 20, 50));
        var page = queries.listStoreReviews(query);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", page.page());
        data.put("pageSize", page.pageSize());
        data.put("total", page.total());
        data.put("items", page.items().stream().map(MerchantReviewAppealController::summary).toList());
        return ok(data, 200, r);
    }

    @GetMapping("/api/v1/merchant/reviews/{reviewId}")
    public ResponseEntity<Map<String, Object>> getStore(
            @PathVariable String reviewId, HttpServletRequest r) {
        onlyParameters(r);
        noBody(r);
        ReviewDetail detail = queries.getStoreReview(
                new ReviewQueryApi.StoreReviewGetQuery(context(r, false, null), id(reviewId)));
        Map<String, Object> data = summary(detail.review());
        data.put("appealReason", detail.appealReason());
        data.put("appealCreatedAt", detail.appealCreatedAt());
        data.put("decisionReason", detail.decisionReason());
        data.put("decidedAt", detail.decidedAt());
        return ok(data, 200, r);
    }

    @PostMapping("/api/v1/merchant/reviews/{reviewId}/appeal")
    public ResponseEntity<Map<String, Object>> appeal(
            @PathVariable String reviewId, HttpServletRequest r) {
        var context = context(r, true, null);
        var body = body(r, APPEAL_BODY);
        var outcome = commands.appealWithOutcome(new ReviewCommandApi.ReviewAppealCommand(
                context, id(reviewId), reason(body, "reason")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appealId", outcome.result().appealId());
        data.put("reviewId", outcome.result().reviewId());
        data.put("status", outcome.result().status());
        data.put("createdAt", outcome.result().createdAt());
        return ok(data, outcome.created() ? 201 : 200, r);
    }

    // -------------------------------------------------------------- O face (ADMIN_WEB)

    @GetMapping("/api/v1/admin/review-appeals")
    public ResponseEntity<Map<String, Object>> listAppeals(HttpServletRequest r) {
        onlyParameters(r, "page", "pageSize", "status");
        noBody(r);
        String status = r.getParameter("status");
        if (status != null && !APPEAL_STATUSES.contains(status)) throw invalid();
        var page = queries.listAppeals(new AppealListQuery(context(r, false, "review.appeal.read"),
                status, number(r.getParameter("page"), 1, 10_000),
                number(r.getParameter("pageSize"), 20, 50)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", page.page());
        data.put("pageSize", page.pageSize());
        data.put("total", page.total());
        data.put("items", page.items().stream().map(MerchantReviewAppealController::appeal).toList());
        return ok(data, 200, r);
    }

    @GetMapping("/api/v1/admin/review-appeals/{appealId}")
    public ResponseEntity<Map<String, Object>> getAppeal(
            @PathVariable String appealId, HttpServletRequest r) {
        onlyParameters(r);
        noBody(r);
        AppealDetail detail = queries.getAppeal(new ReviewQueryApi.AppealGetQuery(
                context(r, false, "review.appeal.read"), id(appealId)));
        Map<String, Object> data = appeal(detail.appeal());
        data.put("decisionReason", detail.decisionReason());
        data.put("decidedBy", detail.decidedBy());
        data.put("review", summary(detail.review()));
        return ok(data, 200, r);
    }

    @PostMapping("/api/v1/admin/review-appeals/{appealId}/decision")
    public ResponseEntity<Map<String, Object>> decide(
            @PathVariable String appealId, HttpServletRequest r) {
        var context = context(r, true, "review.appeal.decide");
        var body = body(r, DECISION_BODY);
        var receipt = commands.decide(new ReviewCommandApi.ReviewAppealDecisionCommand(
                context, id(appealId), oneOf(body, "decisionType", "APPROVED", "REJECTED"),
                reason(body, "reason")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appealId", receipt.appealId());
        data.put("reviewId", receipt.reviewId());
        data.put("status", receipt.status());
        data.put("decisionType", receipt.decisionType());
        data.put("decisionReason", receipt.decisionReason());
        data.put("decidedAt", receipt.decidedAt());
        data.put("reviewVisibility", receipt.reviewVisibility());
        return ok(data, 200, r);
    }

    // -------------------------------------------------------------- shapes / errors

    private static Map<String, Object> summary(ReviewSummary s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reviewId", s.reviewId());
        data.put("orderId", s.orderId());
        data.put("storeScore", s.storeScore());
        data.put("serviceScore", s.serviceScore());
        data.put("staffScore", s.staffScore());
        data.put("compositeScore", s.compositeScore());
        data.put("scoreIncluded", s.scoreIncluded());
        data.put("visibilityStatus", s.visibilityStatus());
        data.put("content", s.content());
        data.put("createdAt", s.createdAt());
        data.put("appealStatus", s.appealStatus());
        data.put("appealId", s.appealId());
        return data;
    }

    private static Map<String, Object> appeal(AppealSummary a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appealId", a.appealId());
        data.put("reviewId", a.reviewId());
        data.put("merchantId", a.merchantId());
        data.put("storeId", a.storeId());
        data.put("orderId", a.orderId());
        data.put("status", a.status());
        data.put("reason", a.reason());
        data.put("createdAt", a.createdAt());
        data.put("decidedAt", a.decidedAt());
        return data;
    }

    static ResponseEntity<Map<String, Object>> ok(Object data, int status, HttpServletRequest r) {
        return ResponseEntity.status(status).headers(noStore())
                .body(envelope("SUCCESS", "ok", data, trace(r)));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> failure(RuntimeException failure,
            HttpServletRequest r) {
        return error(failure, r);
    }
}
