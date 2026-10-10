package com.petplatform.review.api.command;

import com.petplatform.common.CommandContext;
import java.util.List;

/**
 * Internal contract 07 §14.2 review write surface. REV-001 (2026-10-07) delivered create; every
 * create re-runs the §7.7 order eligibility inside its own execution transaction (contract 07:
 * "评价创建前必须调用 OrderQueryApi.checkReviewEligibility()") and the review domain's own
 * one-review-per-order guard; the 40/40/20 composite (SSOT §11.1) and scoreIncluded (SSOT
 * §11.2 partial-refund exclusion) are kernel facts, never caller claims.
 *
 * <p>REV-002 (2026-10-10, CCR-REVIEW-APPEAL-001 / contract 56) delivers the §14.2 appeal
 * command plus the same-batch operator decision. SSOT §11.3 is the only product-rule source:
 * a merchant appeals a violating review <b>at most once per review</b>, and the appeal is
 * strictly not an aftersale re-review. The operator outcome types are exactly Schema06's
 * registered terminals — APPROVED (the review is hidden) and REJECTED (it stays published) —
 * and a decided appeal is final: no re-decision, no second appeal, no PROCESSING transition.
 */
public interface ReviewCommandApi {

    /** Creates the order's single review; idempotent per the five-tuple requestId binding. */
    ReviewCreateResult create(ReviewCreateCommand command);

    /**
     * HTTP boundary outcome (201 first submit / 200 protected replay) for the C face; the
     * kernel keeps the truth, mirroring the aftersale/refund createWithOutcome precedent.
     */
    default CreationOutcome createWithOutcome(ReviewCreateCommand command) {
        throw new UnsupportedOperationException("creation outcome unavailable");
    }

    /**
     * Contract 07 §14.2: one appeal per review (uk_review_appeal_once + service guard);
     * idempotent per the five-tuple requestId binding. OWNER authority over the review's
     * merchant/store is re-proven on every submit and protected replay.
     */
    default ReviewAppealResult appeal(ReviewAppealCommand command) {
        throw new UnsupportedOperationException("review appeal unavailable");
    }

    /** Appeal boundary outcome (201 first submit / 200 protected replay), M face. */
    default AppealOutcome appealWithOutcome(ReviewAppealCommand command) {
        throw new UnsupportedOperationException("appeal outcome unavailable");
    }

    /** Operator terminal decision on a SUBMITTED appeal; APPROVED hides the review. */
    default ReviewAppealDecisionResult decide(ReviewAppealDecisionCommand command) {
        throw new UnsupportedOperationException("appeal decision unavailable");
    }

    /**
     * Scores are the contract's 1..5 integers on all three dimensions; content is optional
     * (0..2000 code points); mediaFileIds keeps the contract shape — the media capability is
     * not open in this slice, so the kernel admits at most an empty list.
     */
    record ReviewCreateCommand(CommandContext context, String orderId,
            int storeScore, int serviceScore, int staffScore,
            String content, List<String> mediaFileIds) {}

    /** Fixed first-success receipt: the created review id plus the SSOT §11.2 score fact. */
    record ReviewCreateResult(String reviewId, boolean scoreIncluded) {}

    record CreationOutcome(ReviewCreateResult result, boolean created) {}

    /** reason: Schema06 NOT NULL VARCHAR(1000) — trimmed non-blank, 1..1000 code points. */
    record ReviewAppealCommand(CommandContext context, String reviewId, String reason) {}

    /** Fixed first-success receipt; status is SUBMITTED (PROCESSING stays a reserved value). */
    record ReviewAppealResult(String appealId, String reviewId, String status, String createdAt) {}

    record AppealOutcome(ReviewAppealResult result, boolean created) {}

    /** decisionType: APPROVED / REJECTED only (Schema06 terminals). */
    record ReviewAppealDecisionCommand(
            CommandContext context, String appealId, String decisionType, String reason) {}

    record ReviewAppealDecisionResult(String appealId, String reviewId, String status,
            String decisionType, String decisionReason, String decidedAt, String reviewVisibility) {}
}
