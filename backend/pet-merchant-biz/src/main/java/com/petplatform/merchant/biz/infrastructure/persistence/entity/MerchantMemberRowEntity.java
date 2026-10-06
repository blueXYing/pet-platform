package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/**
 * OWNER member-list projection for one store (contract 54 §4): contract-52 relation facts plus
 * the display name/phone from the member's CONFIRMED invitation row (never stored in 52 tables).
 */
public final class MerchantMemberRowEntity {
    private Long merchantId;
    private Long storeId;
    private Long memberId;
    private Long userId;
    private String memberName;
    private String phone;
    private String memberStatus;
    private Long memberVersion;
    private String grantStatus;
    private Long grantVersion;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String value) { memberName = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String value) { memberStatus = value; }
    public Long getMemberVersion() { return memberVersion; }
    public void setMemberVersion(Long value) { memberVersion = value; }
    public String getGrantStatus() { return grantStatus; }
    public void setGrantStatus(String value) { grantStatus = value; }
    public Long getGrantVersion() { return grantVersion; }
    public void setGrantVersion(Long value) { grantVersion = value; }
}
