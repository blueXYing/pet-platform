package com.petplatform.service.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contract 74 §3.4/§6.5 featured-card service projection (B-CARD-01). One row per ACTIVE service
 * of the requested stores; the caller (boot composition) merges the order-domain per-service sold
 * counts and applies the confirmed {@code soldCount DESC, serviceId ASC} top-2 selection, because
 * pet_order columns can never appear in service-module SQL (AGENTS red line). The category fields
 * are the real dictionary values; a service whose dictionary row is missing is damaged facts and
 * fails the read closed, never a fabricated category.
 */
public final class FeaturedStoreServiceTypes {
    private FeaturedStoreServiceTypes() {}

    /** One ACTIVE service of a featured store; categorySortNo carries the dictionary order. */
    public record FeaturedStoreServiceItem(
            String serviceId,
            String storeId,
            String merchantId,
            String serviceName,
            String categoryId,
            String categoryCode,
            String categoryName,
            Integer categorySortNo,
            BigDecimal salePrice,
            BigDecimal listPrice,
            int durationMinutes,
            String fulfillmentType) {}

    /** Ordered by storeId ASC then serviceId ASC; empty when no requested store has an ACTIVE service. */
    public record FeaturedStoreServicePage(List<FeaturedStoreServiceItem> items) {
        public FeaturedStoreServicePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
