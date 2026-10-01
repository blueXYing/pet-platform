package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/**
 * Joined merchant/store/member/grant projection for one user and store. A missing grant leaves
 * the grant columns null; damaged cross-merchant references are fail-closed, never filtered out.
 */
public final class MerchantStaffIdentityScopeEntity {
    private Long merchantId;
    private String merchantStatus;
    private Long merchantVersion;
    private Long storeId;
    private String storeStatus;
    private Long storeVersion;
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
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String value) { merchantStatus = value; }
    public Long getMerchantVersion() { return merchantVersion; }
    public void setMerchantVersion(Long value) { merchantVersion = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String value) { storeStatus = value; }
    public Long getStoreVersion() { return storeVersion; }
    public void setStoreVersion(Long value) { storeVersion = value; }
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
