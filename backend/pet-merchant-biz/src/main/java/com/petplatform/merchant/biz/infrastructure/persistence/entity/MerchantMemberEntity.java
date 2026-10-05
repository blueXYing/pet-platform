package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** merchant_member row: approved supplement-27 storage design §2; never derived from staff phone. */
public final class MerchantMemberEntity {
    private Long id;
    private Long merchantId;
    private Long userId;
    private String status;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
}
