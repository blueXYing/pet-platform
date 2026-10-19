package com.petplatform.merchant.api.dto;

import java.util.List;

/**
 * Store profile projection (contract 27 §6.4 supplement, PRD 5.7 店铺基础信息). The read-only
 * half carries the audit-approved opening facts and the operational-status projection; the
 * profile half is the owner-maintained surface. All ids are canonical decimal strings, the
 * amounts-free text fields keep their PRD code-point bounds, and photoAssetIds reference
 * STORE_PURPOSE private assets owned by the same account (31号 pipeline).
 */
public record MerchantStoreProfileDTO(
        String merchantId,
        String storeId,
        ReadonlyFacts readonlyFacts,
        Profile profile) {

    /** Audit-approved facts the merchant cannot change in V1; status projection is read-only. */
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

    /** Owner-maintained basic information; full-replace update semantics (null = cleared). */
    public record Profile(
            String intro,
            String announcement,
            List<String> photoAssetIds,
            List<BusinessHour> businessHours,
            Integer promoLevel,
            String promoTitle,
            String promoText,
            String version) {}

    /** One informational business-hours segment; days are distinct week numbers 1..7 ascending. */
    public record BusinessHour(List<Integer> days, String start, String end) {}
}
