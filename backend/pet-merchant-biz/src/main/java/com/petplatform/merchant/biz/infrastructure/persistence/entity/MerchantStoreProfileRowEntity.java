package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** One merchant_store_profile row (27号 §6.4 supplement; SQL06 untouched). */
public final class MerchantStoreProfileRowEntity {
    private Long storeId;
    private Long merchantId;
    private String intro;
    private String announcement;
    private String businessHours;
    private Integer promoLevel;
    private String promoTitle;
    private String promoText;
    private String photoAssetIds;
    private Long version;

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public String getAnnouncement() { return announcement; }
    public void setAnnouncement(String announcement) { this.announcement = announcement; }
    public String getBusinessHours() { return businessHours; }
    public void setBusinessHours(String businessHours) { this.businessHours = businessHours; }
    public Integer getPromoLevel() { return promoLevel; }
    public void setPromoLevel(Integer promoLevel) { this.promoLevel = promoLevel; }
    public String getPromoTitle() { return promoTitle; }
    public void setPromoTitle(String promoTitle) { this.promoTitle = promoTitle; }
    public String getPromoText() { return promoText; }
    public void setPromoText(String promoText) { this.promoText = promoText; }
    public String getPhotoAssetIds() { return photoAssetIds; }
    public void setPhotoAssetIds(String photoAssetIds) { this.photoAssetIds = photoAssetIds; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    /** merchant+store opening facts joined for the read projection. */
    public static final class Scope {
        private Long merchantId;
        private Long storeId;
        private Long ownerUserId;
        private String merchantName;
        private String storeName;
        private String address;
        private java.math.BigDecimal longitude;
        private java.math.BigDecimal latitude;
        private String phone;
        private String merchantStatus;
        private String storeStatus;
        private String cityCode;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getOwnerUserId() { return ownerUserId; }
        public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public String getStoreName() { return storeName; }
        public void setStoreName(String storeName) { this.storeName = storeName; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public java.math.BigDecimal getLongitude() { return longitude; }
        public void setLongitude(java.math.BigDecimal longitude) { this.longitude = longitude; }
        public java.math.BigDecimal getLatitude() { return latitude; }
        public void setLatitude(java.math.BigDecimal latitude) { this.latitude = latitude; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getMerchantStatus() { return merchantStatus; }
        public void setMerchantStatus(String merchantStatus) { this.merchantStatus = merchantStatus; }
        public String getStoreStatus() { return storeStatus; }
        public void setStoreStatus(String storeStatus) { this.storeStatus = storeStatus; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    }

    /** Audit receipt row (field names only, never values). */
    public static final class Audit {
        private long id;
        private long actorId;
        private String action;
        private long merchantId;
        private long storeId;
        private byte[] requestKey;
        private byte[] requestId;
        private String traceId;
        private Long fromVersion;
        private long toVersion;
        private String changedFields;
        private LocalDateTime occurredAt;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public long getActorId() { return actorId; }
        public void setActorId(long actorId) { this.actorId = actorId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public long getMerchantId() { return merchantId; }
        public void setMerchantId(long merchantId) { this.merchantId = merchantId; }
        public long getStoreId() { return storeId; }
        public void setStoreId(long storeId) { this.storeId = storeId; }
        public byte[] getRequestKey() { return requestKey; }
        public void setRequestKey(byte[] requestKey) { this.requestKey = requestKey; }
        public byte[] getRequestId() { return requestId; }
        public void setRequestId(byte[] requestId) { this.requestId = requestId; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public Long getFromVersion() { return fromVersion; }
        public void setFromVersion(Long fromVersion) { this.fromVersion = fromVersion; }
        public long getToVersion() { return toVersion; }
        public void setToVersion(long toVersion) { this.toVersion = toVersion; }
        public String getChangedFields() { return changedFields; }
        public void setChangedFields(String changedFields) { this.changedFields = changedFields; }
        public LocalDateTime getOccurredAt() { return occurredAt; }
        public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    }
}
