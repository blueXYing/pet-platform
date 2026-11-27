package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponBudgetRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.MerchantCouponUsageRow;
import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponAdminMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Merchant coupon budget ledger writer (contract 79 section 6.3). The ledger is separate from
 * coupon_ledger and every entry is fenced by its request id, so each movement is at most once.
 * All methods run inside the caller's transaction on the same DataSource: the caller passes the
 * mapper it already works with. Platform coupons never reach here.
 */
public final class MerchantCouponBudgetLedger {

    static final String RESERVE = "RESERVE";
    static final String USE = "USE";
    static final String UNUSE = "UNUSE";

    private final CouponAdminStore store;

    public MerchantCouponBudgetLedger(CouponAdminStore store) {
        this.store = store == null ? null : store;
    }

    /** Claim: commit the face value against the campaign budget (MCPB:RESERVE:{instanceId}). */
    public void reserve(CouponAdminMapper mapper, CampaignRow campaign, long instanceId,
            BigDecimal amount, LocalDateTime now) {
        String requestId = "MCPB:RESERVE:" + instanceId;
        MerchantCouponBudgetRow budget = lock(mapper, campaign.id);
        if (budget.issuedAmount.add(amount).compareTo(budget.budgetLimit) > 0) {
            throw new ApiException("COUPON_CAMPAIGN_SOLD_OUT", "预算已耗尽");
        }
        if (mapper.selectMerchantCouponBudgetLedgerId(requestId) != null) return;
        BigDecimal issuedAfter = budget.issuedAmount.add(amount);
        insert(mapper, campaign.id, merchantOf(budget, campaign), instanceId, null, RESERVE, amount,
                issuedAfter, budget.usedAmount, requestId, now);
        move(mapper, campaign.id, amount, BigDecimal.ZERO, budget, now);
    }

    /**
     * Payment success (OrderPaidEvent.v1): the consumed coupon becomes merchant spend
     * (MCPB:USE:{orderId}). Missing or inconsistent facts fail closed so the event is retried
     * and finally parked for operations instead of guessing.
     */
    public void consume(CouponAdminMapper mapper, MerchantCouponUsageRow usage, long orderId,
            BigDecimal amount, LocalDateTime now) {
        String requestId = "MCPB:USE:" + orderId;
        MerchantCouponBudgetRow budget = lock(mapper, usage.campaignId);
        if (mapper.selectMerchantCouponBudgetLedgerId(requestId) != null) return;
        if (budget.usedAmount.add(amount).compareTo(budget.issuedAmount) > 0) {
            throw unavailable("merchant coupon budget has no reservation for the used coupon");
        }
        BigDecimal usedAfter = budget.usedAmount.add(amount);
        insert(mapper, usage.campaignId, merchantOf(budget, null), usage.couponInstanceId, orderId,
                USE, amount, budget.issuedAmount, usedAfter, requestId, now);
        move(mapper, usage.campaignId, BigDecimal.ZERO, amount, budget, now);
    }

    /** Full-refund restore (COUPON_REFUND_RESTORE): MCPB:UNUSE:{refundOrderId}. */
    public void unuse(CouponAdminMapper mapper, long campaignId, long instanceId, long orderId,
            long refundOrderId, BigDecimal amount, LocalDateTime now) {
        String requestId = "MCPB:UNUSE:" + refundOrderId;
        MerchantCouponBudgetRow budget = lock(mapper, campaignId);
        if (mapper.selectMerchantCouponBudgetLedgerId(requestId) != null) return;
        if (budget.usedAmount.subtract(amount).signum() < 0) {
            throw unavailable("merchant coupon budget has no usage to reverse");
        }
        BigDecimal usedAfter = budget.usedAmount.subtract(amount);
        insert(mapper, campaignId, merchantOf(budget, null), instanceId, orderId, UNUSE, amount,
                budget.issuedAmount, usedAfter, requestId, now);
        move(mapper, campaignId, BigDecimal.ZERO, amount.negate(), budget, now);
    }

    private MerchantCouponBudgetRow lock(CouponAdminMapper mapper, long campaignId) {
        MerchantCouponBudgetRow budget = mapper.selectMerchantCouponBudgetForUpdate(campaignId);
        if (budget == null || budget.issuedAmount == null || budget.usedAmount == null
                || budget.budgetLimit == null || budget.version == null) {
            throw unavailable("merchant coupon budget account is missing");
        }
        return budget;
    }

    private static long merchantOf(MerchantCouponBudgetRow budget, CampaignRow campaign) {
        if (budget.merchantId != null) return budget.merchantId;
        if (campaign != null && campaign.ownerMerchantId != null) return campaign.ownerMerchantId;
        throw unavailable("merchant coupon budget has no merchant");
    }

    private void insert(CouponAdminMapper mapper, long campaignId, long merchantId, long instanceId,
            Long orderId, String entryType, BigDecimal amount, BigDecimal issuedAfter,
            BigDecimal usedAfter, String requestId, LocalDateTime now) {
        if (mapper.insertMerchantCouponBudgetLedger(store.nextId(), campaignId, merchantId,
                instanceId, orderId, entryType, amount, issuedAfter, usedAfter, requestId, now) != 1) {
            throw unavailable("merchant coupon budget ledger insert failed");
        }
    }

    private static void move(CouponAdminMapper mapper, long campaignId, BigDecimal issuedDelta,
            BigDecimal usedDelta, MerchantCouponBudgetRow budget, LocalDateTime now) {
        if (mapper.updateMerchantCouponBudgetAmounts(campaignId, issuedDelta, usedDelta,
                budget.version, now) != 1) {
            throw unavailable("merchant coupon budget compare-and-set failed");
        }
    }

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
