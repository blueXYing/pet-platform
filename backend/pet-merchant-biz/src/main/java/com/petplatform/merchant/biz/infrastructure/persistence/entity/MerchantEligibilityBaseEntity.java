package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/** Merchant/store portion of an eligibility snapshot; admission and signing come from the approved facts port. */
public final class MerchantEligibilityBaseEntity {
    private Long merchantId;
    private Long ownerUserId;
    private String merchantStatus;
    private Long storeId;
    private String storeStatus;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String merchantStatus) { this.merchantStatus = merchantStatus; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String storeStatus) { this.storeStatus = storeStatus; }
}
