package com.petplatform.service.biz.infrastructure.persistence.entity;

/**
 * One (category, store) availability bucket from the grouped ACTIVE-service count (contract 74
 * §3.1). The service domain intersects {@code storeId} with the C-visible store set supplied by
 * the merchant domain and aggregates the surviving buckets, so the category page never needs a
 * per-category query.
 */
public final class CatalogCategoryStoreCountEntity {
    private Long categoryId;
    private Long storeId;
    private Long serviceCount;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getServiceCount() { return serviceCount; }
    public void setServiceCount(Long serviceCount) { this.serviceCount = serviceCount; }
}
