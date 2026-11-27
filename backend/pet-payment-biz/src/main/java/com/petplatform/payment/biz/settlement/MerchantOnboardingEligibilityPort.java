package com.petplatform.payment.biz.settlement;

/**
 * Merchant facts behind the "开通收款" admission check (Ruling 44 §2 step 4: archive normal, application
 * approved, platform agreement signed). Implemented in the composition root over the merchant module's
 * API; payment never reads merchant tables. {@link #find} returning {@code null} means "no such merchant".
 */
public interface MerchantOnboardingEligibilityPort {

    MerchantOnboardingFacts find(String merchantId);

    /**
     * Plain, non-sensitive merchant facts. {@code ownerUserId} is the user the merchant belongs to and the
     * owner of operator-uploaded onboarding attachments.
     */
    record MerchantOnboardingFacts(
            String merchantId,
            String ownerUserId,
            String merchantName,
            String merchantStatus,
            String merchantTypeCode,
            String cityCode,
            String applicationStatus,
            String signingStatus) {}

    /** Fail-closed port when the composition root wires nothing: every merchant counts as not found. */
    static MerchantOnboardingEligibilityPort failClosed() {
        return merchantId -> null;
    }
}
