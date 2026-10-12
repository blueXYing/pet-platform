package com.petplatform.review.biz.application;

import com.petplatform.common.CommandContext;

/**
 * REV-002 appeal-side authority ports (CCR-REVIEW-APPEAL-001 / contract 56). The review kernel
 * never touches the merchant/auth domains directly (ARCH: biz never depends on biz): boot
 * assembles this port over the MER order authority + the AUTH admin action check, exactly the
 * Contract50/51 adapter precedent. Implementations re-prove authority on every call and on
 * every protected replay; the service layer additionally runs them inside the store-guarded
 * execution transaction.
 */
public interface ReviewAppealPorts {

    /** OWNER write authority for the review's merchant/store (MER requireOwner semantics). */
    void requireReviewOwner(CommandContext context, String merchantId, String storeId);

    /** OWNER read authority (existing-store read family: ACTIVE/OFFLINE/FROZEN). */
    void requireReviewOwnerRead(CommandContext context, String merchantId, String storeId);

    /** Single-appeal admin action check (review.appeal.read / review.appeal.decide). */
    void requireAppealAdmin(CommandContext context, String appealId, String merchantId,
            String storeId, String action);

    /** Collection-level admin read gate for the appeal list (review.appeal.read). */
    void requireAppealAdminList(CommandContext context);
}
