package com.petplatform.coupon.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** coupon_campaign / coupon_claim rows (contract 72). Public fields for compact MyBatis mapping. */
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
    }

    public static final class ClaimRow {
        public Long id;
        public Long campaignId;
        public Long userId;
        public Long couponInstanceId;
        public LocalDateTime claimedAt;
        public String requestId;
    }
}
