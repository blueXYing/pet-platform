package com.petplatform.service.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/** service_item projection joined with its category name; camel case maps from snake columns. */
public final class ServiceItemReadEntity {
    private Long id;
    private Long merchantId;
    private Long storeId;
    private Long categoryId;
    private String categoryName;
    private String serviceName;
    private String description;
    private BigDecimal price;
    private Integer durationMinutes;
    private String fulfillmentType;
    private Long coverAssetId;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
    public Long getCoverAssetId() { return coverAssetId; }
    public void setCoverAssetId(Long coverAssetId) { this.coverAssetId = coverAssetId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
