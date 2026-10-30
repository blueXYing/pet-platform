package com.petplatform.merchant.biz.infrastructure.persistence.entity;

/**
 * One store row of the featured-card facts read (contract 74 §3.4): the display conjunction's
 * status columns, the compat city fact and the owner-maintained profile fields (intro, avatar
 * reference, promotion). The contact phone and coordinates are deliberately not selected — the
 * featured card never carries PII.
 */
public class MerchantFeaturedStoreFactRowEntity {
    private Long merchantId;
    private String merchantName;
    private String merchantStatus;
    private Long storeId;
    private String storeName;
    private String address;
    private String storeStatus;
    private String cityCode;
    private String intro;
    private String avatarAssetId;
    private Integer promoLevel;
    private String promoTitle;
    private String promoText;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(String merchantStatus) { this.merchantStatus = merchantStatus; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getStoreStatus() { return storeStatus; }
    public void setStoreStatus(String storeStatus) { this.storeStatus = storeStatus; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public String getAvatarAssetId() { return avatarAssetId; }
    public void setAvatarAssetId(String avatarAssetId) { this.avatarAssetId = avatarAssetId; }
    public Integer getPromoLevel() { return promoLevel; }
    public void setPromoLevel(Integer promoLevel) { this.promoLevel = promoLevel; }
    public String getPromoTitle() { return promoTitle; }
    public void setPromoTitle(String promoTitle) { this.promoTitle = promoTitle; }
    public String getPromoText() { return promoText; }
    public void setPromoText(String promoText) { this.promoText = promoText; }
}
