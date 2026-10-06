package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * CCR-C006-COUPON-POINTS-READ-001: the four new read routes join the mandatory MINIAPP session
 * family (anonymous request gets the filter's 401 before any controller logic), and unrelated
 * paths near them do not.
 */
class CBearerSessionFilterCouponPointsPathsTest {

    @Test
    void couponPointsReadRoutesRequireTheSession() {
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/coupons"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/coupons/9400000000000621"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/points/balance"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/points/ledger"));

        // Lookalikes stay outside the session family.
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/couponsx"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/points"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/points/balancex"));
    }
}
