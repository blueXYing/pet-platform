package com.petplatform.merchant.api.dto;

/** Contact data is masked; IDs, decimal coordinates and version stay JSON-safe strings. */
public record MerchantStoreDTO(
        String merchantId,
        String storeId,
        String merchantName,
        String storeName,
        String address,
        String longitude,
        String latitude,
        String phoneMasked,
        String merchantStatus,
        String storeStatus,
        String version
) {}
