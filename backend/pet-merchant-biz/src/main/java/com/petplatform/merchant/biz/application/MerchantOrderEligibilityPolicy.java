package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.util.Set;

/** Pure policy for supplement 27 section 4; unknown or missing authority facts fail closed as 503. */
public final class MerchantOrderEligibilityPolicy {
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> APPLICATION_STATUSES =
            Set.of("DRAFT", "REVIEWING", "APPROVED", "REJECTED");
    // SSOT section 26 / PRD supplement 25 keep all four display states. Electronic consent does
    // not create SIGNING/FAILED in V1, but an authoritative legacy fact remains a known denial.
    private static final Set<String> SIGNING_STATUSES =
            Set.of("NOT_SIGNED", "SIGNING", "SIGNED", "FAILED");

    private MerchantOrderEligibilityPolicy() {}

    public static Decision evaluate(
            String merchantStatus,
            String storeStatus,
            MerchantEligibilityFactsReader.Facts facts
    ) {
        requireKnown(MERCHANT_STATUSES, merchantStatus, "merchant status");
        requireKnown(STORE_STATUSES, storeStatus, "store status");
        if (facts == null) unavailable("eligibility facts are missing");
        requireKnown(APPLICATION_STATUSES, facts.applicationStatus(), "application status");
        requireKnown(SIGNING_STATUSES, facts.signingStatus(), "signing status");

        boolean merchantEnabled = "ACTIVE".equals(merchantStatus);
        boolean storeEnabled = "ACTIVE".equals(storeStatus);
        boolean accepts = merchantEnabled
                && storeEnabled
                && "APPROVED".equals(facts.applicationStatus())
                && "SIGNED".equals(facts.signingStatus());
        return new Decision(merchantEnabled, storeEnabled, accepts);
    }

    public record Decision(boolean merchantEnabled, boolean storeEnabled, boolean acceptsNewOrders) {}

    private static void requireKnown(Set<String> allowed, String value, String field) {
        if (value == null || !allowed.contains(value)) unavailable("unknown " + field);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
