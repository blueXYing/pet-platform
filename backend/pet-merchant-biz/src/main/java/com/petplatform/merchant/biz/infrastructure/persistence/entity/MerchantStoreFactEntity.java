package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** Merchant/store naming and status facts for the confirm-page projection (contract 54 §4). */
public final class MerchantStoreFactEntity {
    private Long merchantId;
    private String merchantName;
    private String merchantStatus;
    private Long storeId;
    private String storeName;
    private String storeStatus;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String value) { merchantName = value; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String value) { merchantStatus = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String value) { storeName = value; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String value) { storeStatus = value; }
}
