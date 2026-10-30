package com.petplatform.service.api.dto;

import java.util.List;

/**
 * Contract 74 §3.1/§3.2 C-end service-category read projections (B-SVC-03/04). Deliberately
 * separate from the operations-side category dictionary types: the C reader carries an anonymous
 * availability projection only (counts are computed by the caller from C-visible stores) and
 * never exposes status/version/audit facts.
 */
public final class CatalogServiceCategoryTypes {
    private CatalogServiceCategoryTypes() {}

    /**
     * One enabled category with at least one available store. {@code storeCount} counts distinct
     * C-visible stores that hold at least one ACTIVE service in the category; {@code serviceCount}
     * counts those ACTIVE services (contract 74 §3.1).
     */
    public record CatalogServiceCategoryItem(
            String categoryCode,
            String categoryName,
            String parentCode,
            String iconUrl,
            String description,
            int sortNo,
            int storeCount,
            int serviceCount) {}

    /** Ordered by {@code sortNo ASC, categoryId ASC}; empty when no category has supply. */
    public record CatalogServiceCategoryPage(List<CatalogServiceCategoryItem> items) {
        public CatalogServiceCategoryPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * Distinct stores holding at least one ACTIVE service in the resolved enabled category, in
     * ascending storeId order. The caller intersects these with its own store-visibility read.
     */
    public record CatalogServiceCategoryStores(String categoryCode, List<String> storeIds) {
        public CatalogServiceCategoryStores {
            storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
        }
    }
}
