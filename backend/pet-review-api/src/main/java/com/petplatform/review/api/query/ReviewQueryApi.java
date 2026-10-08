package com.petplatform.review.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;

/**
 * Internal contract 07 §14.1 review read surface (REV-001 slice, 2026-10-07). The eligibility
 * view is a composition, not a recompute: the ORDER-domain facts (verification, the
 * verifiedAt+30d window, refund exclusions — {@link ReviewEligibilityDTO}) come from
 * OrderQueryApi.checkReviewEligibility, and this kernel overlays only its own fact,
 * REVIEW_ALREADY_EXISTS (registry 12 §11: one review per order). {@code getByOrder} (§14.1)
 * ships with the review read slices that need it; no caller exists in this slice.
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
}
