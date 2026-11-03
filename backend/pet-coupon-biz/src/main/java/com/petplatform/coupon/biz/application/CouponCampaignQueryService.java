package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** C-side coupon campaign detail/claimability projection (contract 72 §5.1). */
public final class CouponCampaignQueryService implements CouponCampaignQueryApi {

    private final CouponAdminStore store;
    private final Clock clock;

    public CouponCampaignQueryService(CouponAdminStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public CouponCampaignView getCampaign(String campaignId, String userId, com.petplatform.common.QueryContext context) {
        long id = id(campaignId);
        Long user = userId == null || userId.isBlank() ? null : id(userId);
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(id);
            if (row == null) throw notFound();
            LocalDateTime now = nowUtc();
            if (!"ACTIVE".equals(row.status) && !"SCHEDULED".equals(row.status)) throw notFound();
            if (row.startAt.isAfter(now) || !row.endAt.isAfter(now)) throw notFound();
            CouponRule rule = CouponRule.parse(row.ruleJson);
            BigDecimal amountOff = rule == null ? BigDecimal.ZERO : rule.amountOff();
            BigDecimal threshold = rule == null ? null : rule.thresholdAmount();
            String typeLabel = CouponRule.typeLabel(row.ruleJson);
            if (typeLabel == null) typeLabel = threshold == null ? "无门槛券" : "满减券";
            int remaining = Math.max(0, row.totalLimit - (row.issuedCount == null ? 0 : row.issuedCount));
            boolean claimed = user != null && mapper.countUserClaims(id, user) > 0;
            boolean templateUsable = "ACTIVE".equals(row.templateStatus)
                    && row.templateValidStartAt != null && row.templateValidEndAt != null
                    && !now.isBefore(row.templateValidStartAt) && !now.isAfter(row.templateValidEndAt);
            boolean budgetOk = row.issuedAmount == null
                    || row.issuedAmount.add(amountOff).compareTo(row.budgetLimit) <= 0;
            String reason = null;
            if (claimed) reason = "CLAIMED";
            else if (!templateUsable || rule == null) reason = "TEMPLATE_UNAVAILABLE";
            else if (remaining <= 0) reason = "SOLD_OUT";
            else if (!budgetOk) reason = "BUDGET_EXHAUSTED";
            boolean canClaim = reason == null;
            return new CouponCampaignView(
                    Long.toUnsignedString(row.id), row.name, Long.toUnsignedString(row.templateId),
                    amountOff, threshold, typeLabel, remaining, row.perUserLimit, claimed,
                    canClaim, reason,
                    OffsetDateTime.ofInstant(row.startAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(row.endAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                    row.status);
        });
    }

    @Override
    public CouponCampaignFactsView facts(String campaignId, com.petplatform.common.QueryContext context) {
        long id = id(campaignId);
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(id);
            if (row == null) return new CouponCampaignFactsView(false, null, null, false);
            LocalDateTime now = nowUtc();
            boolean templateUsable = "ACTIVE".equals(row.templateStatus)
                    && row.templateValidStartAt != null && row.templateValidEndAt != null
                    && !now.isBefore(row.templateValidStartAt) && !now.isAfter(row.templateValidEndAt);
            return new CouponCampaignFactsView(true, row.status, Long.toUnsignedString(row.templateId), templateUsable);
        });
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid("id is invalid");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            invalid("id is too large");
            return 0;
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound() {
        return new ApiException("COUPON_CAMPAIGN_NOT_FOUND", "券活动不存在");
    }
}
