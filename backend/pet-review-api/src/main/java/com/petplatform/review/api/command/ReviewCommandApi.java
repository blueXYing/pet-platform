package com.petplatform.review.api.command;

import com.petplatform.common.CommandContext;
import java.util.List;

/**
 * Internal contract 07 §14.2 review write surface (REV-001 slice, 2026-10-07). Every create
 * re-runs the §7.7 order eligibility inside its own execution transaction (contract 07:
 * "评价创建前必须调用 OrderQueryApi.checkReviewEligibility()") and the review domain's own
 * one-review-per-order guard; the 40/40/20 composite (SSOT §11.1: store 40% + service 40% +
 * staff 20%, one decimal) and scoreIncluded (SSOT §11.2 partial-refund exclusion) are kernel
 * facts, never caller claims. The §14.2 appeal command is the REV-002 slice and is not part
 * of this interface yet.
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
}
