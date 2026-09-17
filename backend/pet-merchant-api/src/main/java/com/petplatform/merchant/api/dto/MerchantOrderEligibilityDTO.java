package com.petplatform.merchant.api.dto;

/** The approved five-field new-order eligibility projection; it says nothing about existing orders. */
public record MerchantOrderEligibilityDTO(
        String merchantId,
        String storeId,
        boolean merchantEnabled,
        boolean storeEnabled,
        boolean acceptsNewOrders
) {}
