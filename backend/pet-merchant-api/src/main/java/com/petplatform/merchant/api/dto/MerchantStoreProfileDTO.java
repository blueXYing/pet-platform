package com.petplatform.merchant.api.dto;

import java.util.List;

/**
 * Store profile projection (contract 27 §6.4 supplement, PRD 5.7 店铺基础信息; 2026-10-19 CCR
 * expansion). The read-only half carries the audit-approved opening facts (city/qualification/
 * location stay read-only — the minimal ruling: merchant-maintained name/address/phone do not
 * trigger re-review) and the operational-status projection (now including the merchant-set
 * RESTING state); the profile half is the owner-maintained surface. All ids are canonical
 * decimal strings, the text fields keep their PRD code-point bounds, and photoAssetIds /
 * avatarAssetId reference STORE_PHOTO private assets owned by the same account (31号 pipeline).
 */
public record MerchantStoreProfileDTO(
        String merchantId,
        String storeId,
        ReadonlyFacts readonlyFacts,
        Profile profile) {

    /** Audit-approved qualification facts plus the operating-status projection; storeName /
     *  address / phone are merchant-maintained through the same full-replace write (CCR
     *  minimal ruling: no re-review), cityCode/location/qualification stay read-only. */
    public record ReadonlyFacts(
            String storeName,
            String address,
            String cityCode,
            String phoneMasked,
            String longitude,
            String latitude,
            String merchantStatus,
            String storeStatus,
            String operatingStatus,
            String applicationStatus) {}

    /** Owner-maintained basic information; full-replace update semantics (empty = cleared). */
    public record Profile(
            String intro,
            String announcement,
            List<String> photoAssetIds,
            List<BusinessHour> businessHours,
            Integer promoLevel,
            String promoTitle,
            String promoText,
            String avatarAssetId,
            String businessStatus,
            String version) {}

    /** One informational business-hours segment; days are distinct week numbers 1..7 ascending. */
    public record BusinessHour(List<Integer> days, String start, String end) {}
}
