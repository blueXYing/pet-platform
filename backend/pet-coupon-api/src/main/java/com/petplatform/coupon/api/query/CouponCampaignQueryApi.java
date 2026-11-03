package com.petplatform.coupon.api.query;

import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** C-side coupon campaign detail (contract 72 §5.1). */
public interface CouponCampaignQueryApi {

    CouponCampaignView getCampaign(String campaignId, String userId, QueryContext context);

    /** Internal facts for a marketing campaign binding: no C time-window hiding. */
    CouponCampaignFactsView facts(String campaignId, QueryContext context);

    record CouponCampaignFactsView(
            boolean exists, String status, String templateId, boolean templateUsable) {}

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
            String status) {}
}
