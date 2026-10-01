package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/**
 * merchant_member_store_grant row: unique (member_id,store_id); nullable display staff_id must
 * reference a merchant_staff row of the same store whenever present (supplement 27 storage §2).
 */
public final class MerchantMemberGrantEntity {
    private Long id;
    private Long memberId;
    private Long storeId;
    private Long staffId;
    private String status;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public Long getStaffId() { return staffId; }
    public void setStaffId(Long value) { staffId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
