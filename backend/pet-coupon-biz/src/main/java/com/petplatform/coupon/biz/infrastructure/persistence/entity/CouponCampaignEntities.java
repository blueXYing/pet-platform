package com.petplatform.coupon.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** coupon_campaign / coupon_claim rows (contract 72) plus merchant coupon projections (contract 79). Public fields for compact MyBatis mapping. */
public final class CouponCampaignEntities {

    private CouponCampaignEntities() {}

    public static final class CampaignRow {
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
        // Joined template projection (read side only).
        public String templateName;
        public String templateStatus;
        public LocalDateTime templateValidStartAt;
        public LocalDateTime templateValidEndAt;
        public String ruleJson;
        // Merchant ownership (contract 79). Platform campaigns: ownerType=PLATFORM, ownerMerchantId=0.
        public String ownerType;
        public Long ownerMerchantId;
        public Long ownerStoreId;
        public String createdByType;
        public String description;
        public String rejectReason;
        public LocalDateTime submittedAt;
        public LocalDateTime reviewedAt;
        public String reviewedByType;
        public Long reviewedById;
        public String templateFundingType;
        public Long templateOwnerMerchantId;

        public boolean merchantOwned() {
            return "MERCHANT".equals(ownerType);
        }
    }

    public static final class ClaimRow {
        public Long id;
        public Long campaignId;
        public Long userId;
        public Long couponInstanceId;
        public Integer claimNo;
        public LocalDateTime claimedAt;
        public String requestId;
    }

    /** merchant_coupon_budget row (contract 79 §2.2); campaign* are filled only by the audit read. */
    public static final class MerchantCouponBudgetRow {
        public Long id;
        public Long campaignId;
        public Long merchantId;
        public BigDecimal budgetLimit;
        public BigDecimal issuedAmount;
        public BigDecimal usedAmount;
        public Long version;
        public BigDecimal campaignIssuedAmount;
        public BigDecimal campaignAmountOff;
        public Long campaignUsedCount;
    }

    /** Merchant coupon instance USED for an order, with its campaign and template funding. */
    public static final class MerchantCouponUsageRow {
        public Long couponInstanceId;
        public Long campaignId;
        public Long campaignMerchantId;
        public String fundingType;
        public Long templateOwnerMerchantId;
        public String ruleJson;
    }

    /** Per-campaign usage counters of one merchant (list cards). */
    public static final class CampaignUsageRow {
        public Long campaignId;
        public Long usedCount;
        public Long drivenOrderCount;
    }

    /** Merchant overview counters (contract 79 §3.1). */
    public static final class MerchantCouponStatsRow {
        public Long totalCampaignCount;
        public Long activeCampaignCount;
        public Long pendingReviewCount;
        public Long monthIssuedCount;
        public Long issuedCount;
        public Long usedCount;
        public Long drivenOrderCount;
    }
}
