package com.petplatform.order.api.dto;

import java.time.OffsetDateTime;

/**
 * Internal contract 07 §7.7 (REV-001 slice, 2026-10-07): the ORDER-domain view of whether an
 * order may still be reviewed. The order module owns every fact behind these four fields
 * (verification, the verifiedAt+30d window, refund_order/refunded amounts) — the same truth
 * that projects {@code OrderActions.canReview} — so the review kernel and the C HTTP face
 * never recompute them. {@code scoreIncluded} states SSOT §11.2: a review admitted after a
 * post-verification partial refund is public but excluded from score statistics.
 * {@code reviewDeadline} is {@code verifiedAt + 30 days} and stays null before verification;
 * {@code rejectCode} is null while eligible and otherwise one of REVIEW_NOT_VERIFIED /
 * REVIEW_WINDOW_EXPIRED / REVIEW_NOT_ELIGIBLE (registry 12 §11; the refund-success exclusion
 * follows the 2026-10-07 user ruling that a fully refunded or in-flight-refund order cannot
 * be reviewed). REVIEW_ALREADY_EXISTS is the review domain's own overlay, never set here.
 */
public record ReviewEligibilityDTO(
        boolean eligible,
        boolean scoreIncluded,
        OffsetDateTime reviewDeadline,
        String rejectCode) {}
