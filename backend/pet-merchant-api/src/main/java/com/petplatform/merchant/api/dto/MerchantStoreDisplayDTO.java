package com.petplatform.merchant.api.dto;

/**
 * STR-D6 display-only store projection: the approved MerchantStoreDTO profile fields (masked
 * contact, optional coordinates) plus the compat-sourced {@code cityCode}, and none of the
 * authority fields (merchantStatus/storeStatus/version) — visibility already implies eligibility
 * for a C-side reader. It authorizes nothing; no booking or scheduling fact is implied.
 */
public record MerchantStoreDisplayDTO(
        String merchantId,
        String storeId,
        String merchantName,
        String storeName,
        String address,
        String longitude,
        String latitude,
        String phoneMasked,
        String cityCode
) {}
