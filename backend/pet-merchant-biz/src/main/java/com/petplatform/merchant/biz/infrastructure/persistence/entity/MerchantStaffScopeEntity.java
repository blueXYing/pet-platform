package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public final class MerchantStaffScopeEntity {
    private Long merchantId;
    private Long ownerUserId;
    private String merchantStatus;
    private Long storeId;
    private String storeStatus;
    private String cityCode;
    private Long merchantVersion;
    private Long storeVersion;
    private Long profileVersion;
    public String getCityCode() { return cityCode; }
    public void setCityCode(String value) { cityCode=value; }
    public Long getMerchantVersion() { return merchantVersion; }
    public void setMerchantVersion(Long value) { merchantVersion=value; }
    public Long getStoreVersion() { return storeVersion; }
    public void setStoreVersion(Long value) { storeVersion=value; }
    public Long getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Long value) { profileVersion=value; }
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
