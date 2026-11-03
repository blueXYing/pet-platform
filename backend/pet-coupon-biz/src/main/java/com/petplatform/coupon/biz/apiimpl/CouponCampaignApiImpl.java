package com.petplatform.coupon.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponCampaignApi;
import com.petplatform.coupon.api.command.CouponClaimApi;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.AdminCouponCampaignService;
import com.petplatform.coupon.biz.application.CouponCampaignQueryService;
import com.petplatform.coupon.biz.application.CouponClaimService;
import com.petplatform.coupon.biz.application.NewUserEligibilityPort;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Local implementation of the contract-72 coupon campaign, C detail and active claim faces.
 * Boot supplies only the module-local authorization/new-user ports; the MyBatis store stays
 * inside the coupon module (ARCH-002).
 */
public final class CouponCampaignApiImpl
        implements AdminCouponCampaignApi, CouponCampaignQueryApi, CouponClaimApi {

    private final AdminCouponCampaignService admin;
    private final CouponCampaignQueryService query;
    private final CouponClaimService claims;

    public CouponCampaignApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            AdminCouponAuthorizationPort.CouponPort authorization,
            NewUserEligibilityPort newUserEligibility, Clock clock) {
        CouponAdminStore store = new CouponAdminStore(
                Objects.requireNonNull(dataSource, "dataSource is required"),
                Objects.requireNonNull(ids, "ids are required"));
        Clock resolvedClock = clock == null ? Clock.systemUTC() : clock;
        this.admin = new AdminCouponCampaignService(store,
                Objects.requireNonNull(authorization, "authorization is required"), resolvedClock);
        this.query = new CouponCampaignQueryService(store, resolvedClock);
        this.claims = new CouponClaimService(store,
                Objects.requireNonNull(newUserEligibility, "newUserEligibility is required"),
                resolvedClock);
    }

    @Override
    public Page<CampaignView> listCampaigns(ListQuery query) {
        return admin.listCampaigns(query);
    }

    @Override
    public CampaignDetail getCampaign(DetailQuery query) {
        return admin.getCampaign(query);
    }

    @Override
    public Receipt createCampaign(CreateCommand command) {
        return admin.createCampaign(command);
    }

    @Override
    public Receipt updateCampaign(UpdateCommand command) {
        return admin.updateCampaign(command);
    }

    @Override
    public Receipt publishCampaign(StatusCommand command) {
        return admin.publishCampaign(command);
    }

    @Override
    public Receipt pauseCampaign(StatusCommand command) {
        return admin.pauseCampaign(command);
    }

    @Override
    public Receipt endCampaign(StatusCommand command) {
        return admin.endCampaign(command);
    }

    @Override
    public Page<ClaimView> listClaims(ClaimsQuery query) {
        return admin.listClaims(query);
    }

    @Override
    public CouponCampaignView getCampaign(String campaignId, String userId,
            com.petplatform.common.QueryContext context) {
        return query.getCampaign(campaignId, userId, context);
    }

    @Override
    public CouponCampaignFactsView facts(String campaignId,
            com.petplatform.common.QueryContext context) {
        return query.facts(campaignId, context);
    }

    @Override
    public ClaimReceipt claim(ClaimCommand command) {
        return claims.claim(command);
    }
}
