package com.petplatform.coupon.api.query;

import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** C-side coupon campaign detail (contract 72 §5.1) and merchant coupon listing (contract 79 §5). */
public interface CouponCampaignQueryApi {

    CouponCampaignView getCampaign(String campaignId, String userId, QueryContext context);

    /**
     * Claimable merchant coupon campaigns of the merchant owning the store (contract 79 §5):
     * status SCHEDULED/ACTIVE and inside the claim window, ordered by end_at, id.
     */
    List<CouponCampaignView> listMerchantCampaigns(String storeId, String userId, QueryContext context);

    /** Internal facts for a marketing campaign binding: no C time-window hiding. */
    CouponCampaignFactsView facts(String campaignId, QueryContext context);

    record CouponCampaignFactsView(
            boolean exists, String status, String templateId, boolean templateUsable) {}

    /**
     * fundingType is PLATFORM/MERCHANT; merchantId/merchantName are null for platform campaigns.
     * claimed is "this user claimed at least once"; canClaim accounts for perUserLimit.
     */
    record CouponCampaignView(
            String campaignId,
            String name,
            String templateId,
            BigDecimal amountOff,
            BigDecimal thresholdAmount,
            String typeLabel,
            int remaining,
            int perUserLimit,
            boolean claimed,
            boolean canClaim,
            String reason,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String status,
            String fundingType,
            String merchantId,
            String merchantName) {}
}
