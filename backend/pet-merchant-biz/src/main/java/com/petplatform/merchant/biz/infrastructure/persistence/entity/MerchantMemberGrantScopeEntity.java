package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/**
 * Locked member + per-store grant row for one binding write (contract 54 §2). A missing grant
 * leaves the grant columns null; the service treats damaged cross-merchant references as
 * fail-closed, never as absent.
 */
public final class MerchantMemberGrantScopeEntity {
    private Long memberId;
    private Long userId;
    private String memberStatus;
    private Long memberVersion;
    private Long grantId;
    private String grantStatus;
    private Long grantVersion;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String value) { memberStatus = value; }
    public Long getMemberVersion() { return memberVersion; }
    public void setMemberVersion(Long value) { memberVersion = value; }
    public Long getGrantId() { return grantId; }
    public void setGrantId(Long value) { grantId = value; }
    public String getGrantStatus() { return grantStatus; }
    public void setGrantStatus(String value) { grantStatus = value; }
    public Long getGrantVersion() { return grantVersion; }
    public void setGrantVersion(Long value) { grantVersion = value; }
}
