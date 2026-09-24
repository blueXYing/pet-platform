package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public final class MerchantStaffScopeEntity {
    private Long merchantId;
    private Long ownerUserId;
    private String merchantStatus;
    private Long storeId;
    private String storeStatus;
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String value) { merchantStatus = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String value) { storeStatus = value; }
}
