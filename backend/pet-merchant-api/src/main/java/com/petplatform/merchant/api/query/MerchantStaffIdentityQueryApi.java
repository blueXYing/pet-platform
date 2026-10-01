package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantStaffIdentityFactsDTO;
import com.petplatform.merchant.api.dto.MerchantStaffMembershipPageDTO;

/**
 * Internal STAFF login-identity facts (supplement 27 §5; HTTP10 workbench admission §1).
 * Called only by trusted session adapters: the operator is always the real MINIAPP USER and
 * every call re-reads current member/grant relations. No binding command exists in this slice.
 */
public interface MerchantStaffIdentityQueryApi {

    /** Enabled member + enabled store grants for the session user, filtered before paging. */
    MerchantStaffMembershipPageDTO listStaffMemberships(MerchantStaffMembershipQuery query);

    /** Current facts for one merchant store; no relation or revoked member is anti-enumeration 404. */
    MerchantStaffIdentityFactsDTO getStaffFacts(MerchantStaffIdentityFactsQuery query);

    /**
     * Fail-closed action gate under the caller's shared store guard: re-verifies member, store
     * grant, granted action and operating facts inside the caller's transaction. No grant row
     * implies no permission; unknown or unreadable facts never pass.
     */
    void requireStaffAction(MerchantStaffActionQuery query);
}
