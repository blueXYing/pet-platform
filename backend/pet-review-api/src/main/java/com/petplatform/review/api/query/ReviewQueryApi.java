package com.petplatform.review.api.query;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;
import java.util.List;

/**
 * Internal contract 07 §14.1 review read surface. REV-001 (2026-10-07) delivered the
 * eligibility composition: the ORDER-domain facts (verification, the verifiedAt+30d window,
 * refund exclusions — {@link ReviewEligibilityDTO}) come from
 * OrderQueryApi.checkReviewEligibility, and this kernel overlays only its own fact,
 * REVIEW_ALREADY_EXISTS (registry 12 §11: one review per order). {@code getByOrder} (§14.1)
 * still has no caller and stays unimplemented.
 *
 * <p>REV-002 (2026-10-10, CCR-REVIEW-APPEAL-001 / contract 56) adds the appeal read faces:
 * the OWNER store projection (list/detail with the appeal state overlay) and the operator
 * appeal list/detail. Reading re-proves current authority exactly like the write side.
 */
public interface ReviewQueryApi {

    /**
     * Review eligibility for the session user's own order: order facts per §7.7, with the
     * review domain's one-review-per-order overlay on top. Absent/foreign orders are the
     * order module's single COMMON_NOT_FOUND (anti-enumeration), never a distinct error.
     */
    ReviewEligibilityDTO checkEligibility(ReviewEligibilityQuery query);

    /** orderId is the canonical decimal string form of the pet_order id. */
    record ReviewEligibilityQuery(String orderId, QueryContext context) {}

    /** OWNER store review list with the appeal state overlay (contract 56 §3). */
    default ReviewPage listStoreReviews(StoreReviewListQuery query) {
        throw new UnsupportedOperationException("store review list unavailable");
    }

    /** OWNER single review with the appeal facts (contract 56 §3). */
    default ReviewDetail getStoreReview(StoreReviewGetQuery query) {
        throw new UnsupportedOperationException("store review detail unavailable");
    }

    /** Operator appeal list, optional status filter (contract 56 §3). */
    default AppealPage listAppeals(AppealListQuery query) {
        throw new UnsupportedOperationException("appeal list unavailable");
    }

    /** Operator appeal detail including the appealed review facts (contract 56 §3). */
    default AppealDetail getAppeal(AppealGetQuery query) {
        throw new UnsupportedOperationException("appeal detail unavailable");
    }

    record StoreReviewListQuery(CommandContext context, String merchantId, String storeId,
            Integer page, Integer pageSize) {
        public StoreReviewListQuery {
            page = page == null ? 1 : page;
            pageSize = pageSize == null ? 20 : pageSize;
        }
    }

    record StoreReviewGetQuery(CommandContext context, String reviewId) {}

    record AppealListQuery(CommandContext context, String status, Integer page, Integer pageSize) {
        public AppealListQuery {
            page = page == null ? 1 : page;
            pageSize = pageSize == null ? 20 : pageSize;
        }
    }

    record AppealGetQuery(CommandContext context, String appealId) {}

    record ReviewPage(int page, int pageSize, long total, List<ReviewSummary> items) {
        public ReviewPage { items = List.copyOf(items); }
    }

    /** Scores are one-decimal strings ("4.0"); composite is the kernel 40/40/20 fact. */
    record ReviewSummary(String reviewId, String orderId, String storeScore, String serviceScore,
            String staffScore, String compositeScore, boolean scoreIncluded,
            String visibilityStatus, String content, String createdAt,
            String appealStatus, String appealId) {}

    record ReviewDetail(ReviewSummary review, String appealReason, String appealCreatedAt,
            String decisionReason, String decidedAt) {}

    record AppealPage(int page, int pageSize, long total, List<AppealSummary> items) {
        public AppealPage { items = List.copyOf(items); }
    }

    record AppealSummary(String appealId, String reviewId, String merchantId, String storeId,
            String orderId, String status, String reason, String createdAt, String decidedAt) {}

    record AppealDetail(AppealSummary appeal, ReviewSummary review,
            String decisionReason, String decidedBy) {}
}
