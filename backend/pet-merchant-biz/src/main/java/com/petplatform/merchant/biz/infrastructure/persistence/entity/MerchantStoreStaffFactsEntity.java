package com.petplatform.merchant.biz.infrastructure.persistence.entity;

public class MerchantStoreStaffFactsEntity {
    private Long storeId;
    private Long merchantId;

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
}
