package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** One selectable STAFF membership row for the memberships list projection. */
public final class MerchantStaffMembershipRowEntity {
    private Long merchantId;
    private String merchantName;
    private String merchantStatus;
    private Long storeId;
    private String storeName;
    private String storeStatus;
    private Long memberId;
    private String memberStatus;
    private Long memberVersion;
    private Long grantId;
    private Long grantStaffId;
    private String grantStatus;
    private Long grantVersion;
    private Long grantStaffStoreId;
    private Long grantStaffMerchantId;

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
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String value) { memberStatus = value; }
    public Long getMemberVersion() { return memberVersion; }
    public void setMemberVersion(Long value) { memberVersion = value; }
    public Long getGrantId() { return grantId; }
    public void setGrantId(Long value) { grantId = value; }
    public Long getGrantStaffId() { return grantStaffId; }
    public void setGrantStaffId(Long value) { grantStaffId = value; }
    public String getGrantStatus() { return grantStatus; }
    public void setGrantStatus(String value) { grantStatus = value; }
    public Long getGrantVersion() { return grantVersion; }
    public void setGrantVersion(Long value) { grantVersion = value; }
    public Long getGrantStaffStoreId() { return grantStaffStoreId; }
    public void setGrantStaffStoreId(Long value) { grantStaffStoreId = value; }
    public Long getGrantStaffMerchantId() { return grantStaffMerchantId; }
    public void setGrantStaffMerchantId(Long value) { grantStaffMerchantId = value; }
}
