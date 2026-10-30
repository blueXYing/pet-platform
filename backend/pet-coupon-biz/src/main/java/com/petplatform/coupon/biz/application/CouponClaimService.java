package com.petplatform.coupon.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.coupon.api.command.CouponClaimApi;
import com.petplatform.coupon.biz.domain.service.CouponRule;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponAdminEntities.AdminTemplateEntity;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.CampaignRow;
import com.petplatform.coupon.biz.infrastructure.persistence.entity.CouponCampaignEntities.ClaimRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * C-side active claim kernel (contract 72 §5.2). One transaction locks the campaign and the
 * template, re-checks the window/limits/audience, draws down campaign stock/budget, inserts the
 * AVAILABLE instance + ISSUE ledger + claim row, and draws down template stock. The unique
 * (campaign_id,user_id) key is the per-user concurrency guard; request_id is the replay guard.
 */
public final class CouponClaimService implements CouponClaimApi {

    private final CouponAdminStore store;
    private final NewUserEligibilityPort newUserEligibility;
    private final Clock clock;

    public CouponClaimService(CouponAdminStore store, NewUserEligibilityPort newUserEligibility,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.newUserEligibility = newUserEligibility;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ClaimReceipt claim(ClaimCommand command) {
        Objects.requireNonNull(command, "command is required");
        long campaignId = id(command.campaignId());
        long userId = id(command.userId());
        String requestId = requestId(command.requestId());
        ClaimRow existing = store.read(mapper -> mapper.selectClaimByRequestId(requestId));
        if (existing != null) {
            if (!Objects.equals(existing.campaignId, campaignId) || !Objects.equals(existing.userId, userId)) {
                throw conflict(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, "领取请求已绑定其他活动/用户");
            }
            return receipt(existing);
        }
        try {
            return store.execute(mapper -> {
                CampaignRow campaign = mapper.selectCouponCampaignForUpdate(campaignId);
                if (campaign == null) throw notFound("券活动不存在");
                // Re-check the request key under the campaign lock: two concurrent replays of the
                // same terminal request must both return the first receipt, not COUPON_CLAIMED.
                ClaimRow replay = mapper.selectClaimByRequestId(requestId);
                if (replay != null) {
                    if (!Objects.equals(replay.campaignId, campaignId)
                            || !Objects.equals(replay.userId, userId)) {
                        throw conflict(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                                "领取请求已绑定其他活动/用户");
                    }
                    return receipt(replay);
                }
                LocalDateTime now = nowUtc();
                if (!"ACTIVE".equals(campaign.status) && !"SCHEDULED".equals(campaign.status)) {
                    throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "券活动当前不可领取");
                }
                if (campaign.startAt.isAfter(now) || !campaign.endAt.isAfter(now)) {
                    throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "不在发券期内");
                }
                AdminTemplateEntity template = mapper.selectTemplateForUpdate(campaign.templateId);
                if (template == null || !"ACTIVE".equals(template.getStatus())) {
                    throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "券模板不可用");
                }
                if (now.isBefore(template.getValidStartAt()) || now.isAfter(template.getValidEndAt())) {
                    throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "券模板不在有效期内");
                }
                if (mapper.countUserClaims(campaignId, userId) > 0
                        || mapper.selectClaimByCampaignUser(campaignId, userId) != null) {
                    throw conflict("COUPON_CLAIMED", "您已领取过该活动券");
                }
                if ("NEW_USER".equals(campaign.audienceType)) {
                    if (newUserEligibility == null) unavailable("新用户资格校验不可用");
                    boolean newUser;
                    try {
                        newUser = newUserEligibility.isNewUser(userId, command.traceId());
                    } catch (RuntimeException failure) {
                        unavailable("新用户资格校验不可用");
                        return null;
                    }
                    if (!newUser) throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "仅新用户可领取");
                }
                CouponRule rule = CouponRule.parse(template.getRuleJson());
                if (rule == null) throw conflict("COUPON_CAMPAIGN_NOT_CLAIMABLE", "券规则不可用");
                if (mapper.incrementCouponCampaignIssue(campaignId, rule.amountOff(), now) != 1) {
                    throw conflict("COUPON_CAMPAIGN_SOLD_OUT", "券已抢完或预算已耗尽");
                }
                long instanceId = store.nextId();
                if (mapper.insertInstance(instanceId, campaign.templateId, userId,
                        template.getValidEndAt(), template.getValidEndAt(), now) != 1) {
                    unavailable("coupon instance insert failed");
                }
                if (mapper.insertIssueLedger(store.nextId(), instanceId, userId,
                        "CPN:CAMPAIGN:" + instanceId,
                        "source=CAMPAIGN:" + campaignId, now) != 1) {
                    unavailable("coupon issue ledger insert failed");
                }
                if (mapper.incrementIssuedCount(campaign.templateId, now) != 1) {
                    throw conflict("COUPON_CAMPAIGN_SOLD_OUT", "券模板库存已用尽");
                }
                long claimId = store.nextId();
                if (mapper.insertClaim(claimId, campaignId, userId, instanceId, requestId, now) != 1) {
                    unavailable("coupon claim insert failed");
                }
                return new ClaimReceipt(Long.toUnsignedString(campaignId), Long.toUnsignedString(claimId),
                        Long.toUnsignedString(instanceId), rule.amountOff(),
                        OffsetDateTime.ofInstant(template.getValidEndAt().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            ClaimRow replay = store.read(mapper -> mapper.selectClaimByRequestId(requestId));
            if (replay != null && Objects.equals(replay.campaignId, campaignId)
                    && Objects.equals(replay.userId, userId)) {
                return receipt(replay);
            }
            throw conflict("COUPON_CLAIMED", "您已领取过该活动券");
        }
    }

    private ClaimReceipt receipt(ClaimRow row) {
        return store.read(mapper -> {
            CampaignRow campaign = mapper.selectCouponCampaignById(row.campaignId);
            if (campaign == null) throw notFound("券活动不存在");
            AdminTemplateEntity template = mapper.selectTemplateById(campaign.templateId);
            CouponRule rule = template == null ? null : CouponRule.parse(template.getRuleJson());
            return new ClaimReceipt(
                    Long.toUnsignedString(row.campaignId), Long.toUnsignedString(row.id),
                    Long.toUnsignedString(row.couponInstanceId),
                    rule == null ? BigDecimal.ZERO : rule.amountOff(),
                    template == null ? null : OffsetDateTime.ofInstant(
                            template.getValidEndAt().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(row.claimedAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
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

    private static String requestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,191}")) invalid("requestId is invalid");
        return value;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException("COUPON_CAMPAIGN_NOT_FOUND", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(code, message);
    }

    private static ApiException unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
