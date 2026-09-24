package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public class MerchantStaffFactsEntity {
    private Long staffId;
    private Long merchantId;
    private Long storeId;
    private String employmentStatus;
    private Integer serviceEnabled;

    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getEmploymentStatus() { return employmentStatus; }
    public void setEmploymentStatus(String employmentStatus) { this.employmentStatus = employmentStatus; }
    public Integer getServiceEnabled() { return serviceEnabled; }
    public void setServiceEnabled(Integer serviceEnabled) { this.serviceEnabled = serviceEnabled; }
}
