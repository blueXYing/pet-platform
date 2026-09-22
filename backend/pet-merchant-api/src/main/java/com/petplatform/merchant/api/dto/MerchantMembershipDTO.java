package com.petplatform.merchant.api.dto;

/** One selectable owner-scoped store projection (HTTP10 membership; CCR-W2-ADMISSION-001 §2). */
public record MerchantMembershipDTO(
        String merchantId, String merchantName, String storeId, String storeName, String membershipKind) {}
