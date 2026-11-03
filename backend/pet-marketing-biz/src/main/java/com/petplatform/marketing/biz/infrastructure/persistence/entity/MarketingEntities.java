package com.petplatform.marketing.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mutable MyBatis row projections. Public fields keep the new module compact; the MyBatis
 * reflector maps snake_case columns onto camelCase fields (mapUnderscoreToCamelCase=true in
 * {@link com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore}).
 */
public final class MarketingEntities {

    private MarketingEntities() {}

    public static final class CampaignRow {
        public Long id;
        public String campaignCode;
        public String name;
        public String ownerType;
        public Long ownerMerchantId;
        public Long ownerStoreId;
        public String campaignType;
        public String participationMode;
        public String status;
        public String title;
        public String subtitle;
        public String tagCode;
        public String tagName;
        public String coverUrl;
        public String cityCodes;
        public String serviceCategoryCodes;
        public LocalDateTime startAt;
        public LocalDateTime endAt;
        public LocalDateTime enrollStartAt;
        public LocalDateTime enrollEndAt;
        public Integer enrollmentQuota;
        public Long couponCampaignId;
        public String pageSchemaJson;
        public Integer pageDraftVersion;
        public String publishedPageSchemaJson;
        public Integer pageVersion;
        public Integer sortWeight;
        public String rejectReason;
        public Long version;
        public String createdByType;
        public Long createdById;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class CampaignStoreRow {
        public Long id;
        public Long campaignId;
        public Long merchantId;
        public Long storeId;
        public String source;
        public String status;
        public String offerTag;
        public String offerTitle;
        public String offerCopy;
        public Integer sortWeight;
        public Long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class PlacementRow {
        public Long id;
        public String positionCode;
        public Long campaignId;
        public String campaignTitle;
        public String cityCode;
        public String titleOverride;
        public String subtitleOverride;
        public String imageUrlOverride;
        public String templateCode;
        public Integer sortWeight;
        public LocalDateTime startAt;
        public LocalDateTime endAt;
        public String status;
        public Long version;
        public Long createdById;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class EnrollmentRow {
        public Long id;
        public Long campaignId;
        public String campaignTitle;
        public Long merchantId;
        public Long storeId;
        public String storeName;
        public String status;
        public String offerTag;
        public String offerTitle;
        public String offerCopy;
        public String serviceIds;
        public String materials;
        public String rejectReason;
        public LocalDateTime submittedAt;
        public LocalDateTime decidedAt;
        public Long decidedBy;
        public Long version;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class BindingRow {
        public Long id;
        public String requestKey;
        public String commandType;
        public String paramsSha256;
        public byte[] paramsCanonical;
        public String status;
        public String receiptJson;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class PageVersionRow {
        public Long id;
        public Long campaignId;
        public Integer version;
        public String schemaJson;
        public String publishedByType;
        public Long publishedById;
        public LocalDateTime publishedAt;
    }

    public static final class TagRow {
        public String tagCode;
        public String tagName;
        public Long campaignCount;
    }

    public static final class AssetRow {
        public Long id;
        public String contentType;
        public Long bytes;
        public Integer width;
        public Integer height;
        public String sha256;
        public String objectKey;
        public byte[] content;
        public String status;
        public Long createdById;
        public LocalDateTime createdAt;
    }

    /** CCR-MKT-003 home content block; sponsor ids are reserved for later merchant bidding. */
    public static final class HomeBlockRow {
        public Long id;
        public String blockCode;
        public String positionCode;
        public String templateCode;
        public String title;
        public String subtitle;
        public String tagName;
        public String imageUrl;
        public String targetType;
        public String targetValue;
        public String cityCode;
        public Long sponsorMerchantId;
        public Long sponsorStoreId;
        public Integer sortWeight;
        public LocalDateTime startAt;
        public LocalDateTime endAt;
        public String status;
        public Long version;
        public Long createdById;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class CouponCampaignRow {
        public Long id;
        public String name;
        public Long templateId;
        public String audienceType;
        public Integer perUserLimit;
        public Integer totalLimit;
        public BigDecimal budgetLimit;
        public Integer issuedCount;
        public BigDecimal issuedAmount;
        public LocalDateTime startAt;
        public LocalDateTime endAt;
        public String claimMethod;
        public String status;
        public Long version;
        public Long createdById;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }
}
