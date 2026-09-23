package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;

/**
 * CCR-W2-API-001 store-read proposal STR-D6: consumer-facing store display projection (the fifth
 * merchant query, an explicit supplement to supplement 27 section 4).
 *
 * <p>Read-only store profile projection for C-side catalog browsing only. Unlike the owner-scoped
 * three queries this one has no ownership precondition, and it grants no merchant-operation
 * authority: the owner-scoped {@link MerchantQueryApi} remains the only authority surface.
 * QueryContext carries requestId/traceId linkage only; callers must not use it to impersonate a
 * merchant principal (API07: self-reported DTOs never establish a Principal). Anonymous browsing
 * (STR-D8) calls these with an empty context subject; visibility never depends on the subject.
 *
 * <p>Visibility is the approved three-condition conjunction (merchantEnabled AND storeEnabled AND
 * acceptsNewOrders), evaluated with the same {@code MerchantOrderEligibilityPolicy} as the owner
 * surface inside one repeatable-read snapshot; the page evaluates the policy once per distinct
 * merchant/store pair of the page ("whole-page eligibility"), never row by row repeated reads.
 *
 * <p>Error contract (same split as SVC-D5, never conflated): a positively confirmed missing or
 * ineligible store throws NOT_FOUND for {@code getDisplayStore} (the caller maps it to 404) and is
 * simply excluded by {@code pageDisplayStores}; a confirmed absence of matching visible stores in
 * the requested cities is a normal empty page, not an error. Read failures, facts-source failures,
 * unknown status values and profile-compat integrity violations (missing compat row or malformed
 * city code on an otherwise eligible merchant) throw DEPENDENCY_UNAVAILABLE (fail closed; the
 * caller maps it to 503, whole page).
 */
public interface MerchantStoreDisplayApi {

    MerchantStoreDisplayPageDTO pageDisplayStores(MerchantStoreDisplayPageQuery query);

    MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query);
}
