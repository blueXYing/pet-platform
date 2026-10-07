package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** One merchant_member_invitation row (contract 54 storage); phone is masked in projections. */
public final class MerchantMemberInvitationEntity {
    private Long id;
    private Long merchantId;
    private Long storeId;
    private String phone;
    private String memberName;
    private String status;
    private Long invitedBy;
    private Long confirmedBy;
    private Long memberId;
    private Long version;
    private String pendingMarker;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String value) { memberName = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long value) { invitedBy = value; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long value) { confirmedBy = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getPendingMarker() { return pendingMarker; }
    public void setPendingMarker(String value) { pendingMarker = value; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { createdAt = value; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { updatedAt = value; }
}
