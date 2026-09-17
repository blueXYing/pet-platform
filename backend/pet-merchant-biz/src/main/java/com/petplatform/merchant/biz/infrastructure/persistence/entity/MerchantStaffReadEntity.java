package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** Persistence projection owned by merchant-biz; no member or login fact is inferred from it. */
public final class MerchantStaffReadEntity {
    private Long merchantId;
    private Long ownerUserId;
    private String merchantStatus;
    private Long storeId;
    private String storeStatus;
    private Long staffId;
    private String staffName;
    private String phone;
    private String employmentStatus;
    private Integer serviceEnabled;
    private Long staffVersion;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String merchantStatus) { this.merchantStatus = merchantStatus; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String storeStatus) { this.storeStatus = storeStatus; }
    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmploymentStatus() { return employmentStatus; }
    public void setEmploymentStatus(String employmentStatus) { this.employmentStatus = employmentStatus; }
    public Integer getServiceEnabled() { return serviceEnabled; }
    public void setServiceEnabled(Integer serviceEnabled) { this.serviceEnabled = serviceEnabled; }
    public Long getStaffVersion() { return staffVersion; }
    public void setStaffVersion(Long staffVersion) { this.staffVersion = staffVersion; }
}
