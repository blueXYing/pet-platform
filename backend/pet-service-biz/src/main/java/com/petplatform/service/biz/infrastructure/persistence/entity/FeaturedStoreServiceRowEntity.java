package com.petplatform.service.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/**
 * One ACTIVE service row of the featured-card batch read (contract 74 §6.5): the service's own
 * facts plus its resolved dictionary category (code, name, sort number). The dictionary fields are
 * nullable only so a missing row can be detected and rejected as damaged facts by the application
 * layer — a featured card never ships without a real category.
 */
public class FeaturedStoreServiceRowEntity {
    private Long serviceId;
    private Long storeId;
    private Long merchantId;
    private String serviceName;
    private Long categoryId;
    private String categoryCode;
    private String categoryName;
    private Integer categorySortNo;
    private BigDecimal salePrice;
    private BigDecimal listPrice;
    private Integer durationMinutes;
    private String fulfillmentType;

    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Integer getCategorySortNo() { return categorySortNo; }
    public void setCategorySortNo(Integer categorySortNo) { this.categorySortNo = categorySortNo; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public BigDecimal getListPrice() { return listPrice; }
    public void setListPrice(BigDecimal listPrice) { this.listPrice = listPrice; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
}
