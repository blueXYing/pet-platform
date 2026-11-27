package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Contract-79 §5: the per-store merchant coupon list is an anonymous GET (optional session), like
 * the contract-72 campaign detail; the claim stays mandatory-session and lookalikes stay outside.
 */
class CBearerSessionFilterCouponCampaignPathsTest {

    @Test
    void couponCampaignReadsAreOptionalSessionGets() {
        assertTrue(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaigns", "GET"));
        assertTrue(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaigns/9400000000000621", "GET"));
        assertFalse(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaigns", "POST"));
        assertFalse(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaigns/", "GET"));
        assertFalse(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaignsx", "GET"));
        assertFalse(CBearerSessionFilter.optionalSessionPath("/api/v1/c/coupon-campaigns/1/claims", "GET"));
    }

    @Test
    void claimStaysMandatorySession() {
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/coupon-campaigns/9400000000000621/claims"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/coupon-campaigns"));
    }
}
