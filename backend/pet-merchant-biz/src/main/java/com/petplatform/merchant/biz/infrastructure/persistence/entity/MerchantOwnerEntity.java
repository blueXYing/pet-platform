package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** Owner and lifecycle facts used for agreement authorization and first-consent gating. */
public final class MerchantOwnerEntity {
    private Long id;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
