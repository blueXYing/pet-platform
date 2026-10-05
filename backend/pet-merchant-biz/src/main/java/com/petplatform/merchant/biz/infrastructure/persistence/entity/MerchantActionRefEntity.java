package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** (ownerId, actionCode) pair row for batched action reads on list pages (contract 54 §4). */
public final class MerchantActionRefEntity {
    private Long ownerId;
    private String actionCode;

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public String getActionCode() { return actionCode; }
    public void setActionCode(String value) { actionCode = value; }
}
