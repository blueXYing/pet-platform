package com.petplatform.merchant.api.dto;

/** Service-person profile only; it intentionally carries no login or merchant-member identity. */
public record MerchantStaffDTO(
        String merchantId,
        String storeId,
        String staffId,
        String staffName,
        String phoneMasked,
        String employmentStatus,
        boolean serviceEnabled,
        String version
) {}
