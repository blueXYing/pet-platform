package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * C-side coupon campaign detail/claimability projection (contract 72 §5.1) and the merchant
 * coupon listing (contract 79 §5). Merchant names resolve through the merchant api; when that
 * port is absent the name is simply null — a read never fails because of a display label.
 */
public final class CouponCampaignQueryService implements CouponCampaignQueryApi {

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final CouponAdminStore store;
    // The public store display read (the one behind GET /c/stores/{storeId}): the C faces are
    // anonymous-browsable, so the owner-scoped MerchantQueryApi would answer 401/404 to a shopper.
    private final MerchantStoreDisplayApi merchants;
    private final Clock clock;

    public CouponCampaignQueryService(CouponAdminStore store, MerchantStoreDisplayApi merchants, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.merchants = merchants;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public CouponCampaignView getCampaign(String campaignId, String userId, QueryContext context) {
        long id = id(campaignId);
        Long user = userId == null || userId.isBlank() ? null : id(userId);
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCouponCampaignById(id);
            if (row == null) throw notFound();
            LocalDateTime now = nowUtc();
            if (!"ACTIVE".equals(row.status) && !"SCHEDULED".equals(row.status)) throw notFound();
            if (row.startAt.isAfter(now) || !row.endAt.isAfter(now)) throw notFound();
            long claimedCount = user == null ? 0 : mapper.countUserClaims(id, user);
            return view(row, now, claimedCount, context);
        });
    }

    @Override
    public List<CouponCampaignView> listMerchantCampaigns(String storeId, String userId,
            QueryContext context) {
        long storeKey = id(storeId);
        Long user = userId == null || userId.isBlank() ? null : id(userId);
        MerchantStoreDisplayDTO facts = merchants == null ? null : merchants.getDisplayStore(
                new MerchantStoreDisplayQuery(IDS.toApi(storeKey), context));
        if (facts == null || facts.merchantId() == null) throw notFound();
        long merchantId = id(facts.merchantId());
        return store.read(mapper -> {
            LocalDateTime now = nowUtc();
            List<CouponCampaignView> out = new ArrayList<>();
            for (CampaignRow row : mapper.selectClaimableMerchantCouponCampaigns(merchantId, now)) {
                CouponRule rule = CouponRule.parse(row.ruleJson);
                if (rule == null || row.issuedAmount == null || row.budgetLimit == null) continue;
                // Budget headroom: the claim itself re-checks it under the campaign row lock.
                if (row.issuedAmount.add(rule.amountOff()).compareTo(row.budgetLimit) > 0) continue;
                long claimedCount = user == null ? 0 : mapper.countUserClaims(row.id, user);
                out.add(view(row, now, claimedCount, facts.merchantName()));
            }
            return out;
        });
    }

    @Override
    public CouponCampaignFactsView facts(String campaignId, QueryContext context) {
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

    private CouponCampaignView view(CampaignRow row, LocalDateTime now, long claimedCount,
            String merchantName) {
        CouponRule rule = CouponRule.parse(row.ruleJson);
        BigDecimal amountOff = rule == null ? BigDecimal.ZERO : rule.amountOff();
        BigDecimal threshold = rule == null ? null : rule.thresholdAmount();
        String typeLabel = CouponRule.typeLabel(row.ruleJson);
        if (typeLabel == null) typeLabel = threshold == null ? "无门槛券" : "满减券";
        int remaining = Math.max(0, row.totalLimit - (row.issuedCount == null ? 0 : row.issuedCount));
        boolean claimed = claimedCount > 0;
        int perUserLimit = row.perUserLimit == null || row.perUserLimit < 1 ? 1 : row.perUserLimit;
        boolean templateUsable = "ACTIVE".equals(row.templateStatus)
                && row.templateValidStartAt != null && row.templateValidEndAt != null
                && !now.isBefore(row.templateValidStartAt) && !now.isAfter(row.templateValidEndAt);
        boolean budgetOk = row.issuedAmount == null
                || row.issuedAmount.add(amountOff).compareTo(row.budgetLimit) <= 0;
        String reason = null;
        if (claimedCount >= perUserLimit) reason = "CLAIMED";
        else if (!templateUsable || rule == null) reason = "TEMPLATE_UNAVAILABLE";
        else if (remaining <= 0) reason = "SOLD_OUT";
        else if (!budgetOk) reason = "BUDGET_EXHAUSTED";
        boolean merchantOwned = row.merchantOwned();
        return new CouponCampaignView(
                Long.toUnsignedString(row.id), row.name, Long.toUnsignedString(row.templateId),
                amountOff, threshold, typeLabel, remaining, perUserLimit, claimed,
                reason == null, reason,
                OffsetDateTime.ofInstant(row.startAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(row.endAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                row.status,
                merchantOwned ? "MERCHANT" : "PLATFORM",
                merchantOwned && row.ownerMerchantId != null
                        ? Long.toUnsignedString(row.ownerMerchantId) : null,
                merchantOwned ? merchantName : null);
    }

    private CouponCampaignView view(CampaignRow row, LocalDateTime now, long claimedCount,
            QueryContext context) {
        String merchantName = null;
        if (row.merchantOwned() && merchants != null && row.ownerStoreId != null) {
            try {
                MerchantStoreDisplayDTO facts = merchants.getDisplayStore(
                        new MerchantStoreDisplayQuery(IDS.toApi(row.ownerStoreId), context));
                merchantName = facts == null ? null : facts.merchantName();
            } catch (RuntimeException unreadable) {
                merchantName = null; // display label only: never fail the read
            }
        }
        return view(row, now, claimedCount, merchantName);
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
