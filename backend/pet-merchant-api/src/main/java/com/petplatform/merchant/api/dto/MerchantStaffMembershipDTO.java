package com.petplatform.merchant.api.dto;

/**
 * One selectable STAFF membership projection (HTTP10 §1 Membership; supplement 27 §5).
 * staffId is display-only history attribution returned for STAFF only; it is never an
 * authorization input and OWNER sessions never fabricate one.
 */
public record MerchantStaffMembershipDTO(
        String merchantId,
        String merchantName,
        String storeId,
        String storeName,
        String membershipKind,
        String staffId) {}
