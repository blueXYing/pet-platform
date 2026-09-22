package com.petplatform.merchant.api.dto;

/**
 * SVC-D5 display-only projection. Semantics equal the approved owner-facing
 * {@link MerchantOrderEligibilityDTO} five-field projection (same policy, same fail-closed
 * behaviour on unknown facts) but with no ownership requirement; it authorizes nothing.
 */
public record MerchantDisplayEligibilityDTO(
        String merchantId,
        String storeId,
        boolean merchantEnabled,
        boolean storeEnabled,
        boolean acceptsNewOrders
) {}
