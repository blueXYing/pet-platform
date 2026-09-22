package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;

/**
 * CCR-W2-API-001 service-domain proposal SVC-D5: consumer-facing display eligibility.
 *
 * <p>Read-only boolean facts for C-side catalog aggregation only. Unlike the owner-scoped three
 * queries this one has no ownership precondition, and it grants no merchant-operation authority:
 * the owner-scoped {@link MerchantQueryApi} remains the only authority surface. QueryContext
 * carries requestId/traceId linkage only; callers must not use it to impersonate a merchant
 * principal (API07: self-reported DTOs never establish a Principal).
 *
 * <p>Error contract: a positively confirmed missing merchant/store pair throws NOT_FOUND (the
 * caller maps it to hidden/404); read failures, facts-source failures and unknown status values
 * throw DEPENDENCY_UNAVAILABLE (fail closed, the caller maps it to 503). These two families must
 * never be conflated.
 */
public interface MerchantDisplayEligibilityApi {

    MerchantDisplayEligibilityDTO checkDisplayEligibility(MerchantDisplayEligibilityQuery query);
}
