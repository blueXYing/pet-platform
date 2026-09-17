package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/** Persistence projection owned by merchant-biz; never exposed through merchant-api. */
public final class MerchantStoreReadEntity {
    private Long merchantId;
    private Long ownerUserId;
    private String merchantName;
    private String merchantStatus;
    private Long storeId;
    private String storeName;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String phone;
    private String storeStatus;
    private Long storeVersion;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String merchantStatus) { this.merchantStatus = merchantStatus; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String storeStatus) { this.storeStatus = storeStatus; }
    public Long getStoreVersion() { return storeVersion; }
    public void setStoreVersion(Long storeVersion) { this.storeVersion = storeVersion; }
}
