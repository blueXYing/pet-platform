package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C-004 (contract 10 §3.7): the two order read routes join the mandatory MINIAPP session family
 * (an anonymous request gets the filter's 401 before any controller logic); deeper order
 * subpaths and lookalikes are unchanged.
 */
class CBearerSessionFilterOrderPathsTest {

    @Test
    void orderReadRoutesRequireTheSession() {
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/orders"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/orders/9500000000000701"));
        // Pre-existing order subpaths keep their own protection.
        assertTrue(CBearerSessionFilter.protectedPath(
            "/api/v1/c/orders/9500000000000701/verification-code"));
        assertTrue(CBearerSessionFilter.protectedPath(
            "/api/v1/c/orders/9500000000000701/aftersales"));
        assertTrue(CBearerSessionFilter.protectedPath(
            "/api/v1/c/orders/9500000000000701/aftersale-eligibility"));

        // Lookalikes and unimplemented neighbors stay outside the family.
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/ordersx"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/orders/1/2"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/orders//"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/c/order"));
    }

    /**
     * Contracts 27 §6.4 / 69 / 70: the merchant storefront reads are session-scoped even though
     * their HTTP switches default off; the security chain decides whether the route is assembled.
     */
    @Test
    void merchantStorefrontRoutesRequireTheSession() {
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/store-profile"));
        // 27 §6.4 2026-11-06 supplement (CCR-M-STORE-AVATAR-001): the avatar signing read
        // joins the session family; lookalike deeper subpaths stay out.
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/store-profile/avatar-url"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/store-profile/avatar"));
        // 56 §7 2026-11-06 supplement (CCR-M-REVIEW-VIEW-001): the aggregate read rides the
        // existing reviews prefix family.
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/reviews/summary"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/stats/overview"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/stats/services/top"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/customers"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/customers/710100"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/customers/710100/note"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/staff-members"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/staff-members/invitations"));
        assertTrue(CBearerSessionFilter.protectedPath(
            "/api/v1/merchant/staff-members/710303/disable"));
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/c/staff/invitations"));
        assertTrue(CBearerSessionFilter.protectedPath(
            "/api/v1/c/staff/invitations/710304/confirm"));
        // CCR-M-VERIFY-RECORD-001 (approved 2026-11-12): the merchant verification-record list
        // joins the mandatory MINIAPP session family (exact path, no subpaths); lookalikes stay out.
        assertTrue(CBearerSessionFilter.protectedPath("/api/v1/merchant/verifications"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/verifications/9501"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/verification"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/verificationsx"));

        // Unrelated lookalikes stay out of the merchant session family.
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/stats"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/customer"));
        assertFalse(CBearerSessionFilter.protectedPath("/api/v1/merchant/staff-member"));
    }
}
