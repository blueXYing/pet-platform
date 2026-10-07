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
}
