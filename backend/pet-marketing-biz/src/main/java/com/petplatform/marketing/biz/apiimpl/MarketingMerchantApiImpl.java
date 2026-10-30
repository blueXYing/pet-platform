package com.petplatform.marketing.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.marketing.api.MarketingMerchantApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.application.MarketingMerchantService;
import com.petplatform.marketing.biz.application.MarketingStoreFactsPort;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-71 merchant marketing/enrollment face. */
public final class MarketingMerchantApiImpl implements MarketingMerchantApi {

    private final MarketingMerchantService service;

    public MarketingMerchantApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            MarketingStoreFactsPort storeFacts, Clock clock) {
        this.service = new MarketingMerchantService(
                new MarketingStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(storeFacts, "storeFacts is required"),
                clock);
    }

    @Override
    public MarketingViews.Page<MarketingViews.MerchantCampaignView> listOpenCampaigns(
            MerchantCampaignListQuery query) {
        return service.listOpenCampaigns(query);
    }

    @Override
    public MarketingViews.Receipt enroll(EnrollCommand command) {
        return service.enroll(command);
    }

    @Override
    public MarketingViews.Page<MarketingViews.EnrollmentView> listMyEnrollments(
            MerchantEnrollmentListQuery query) {
        return service.listMyEnrollments(query);
    }

    @Override
    public MarketingViews.Receipt withdraw(WithdrawCommand command) {
        return service.withdraw(command);
    }

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listMyCampaigns(
            MerchantMyCampaignListQuery query) {
        return service.listMyCampaigns(query);
    }

    @Override
    public MarketingViews.Receipt createMyCampaign(MerchantCreateCampaignCommand command) {
        return service.createMyCampaign(command);
    }

    @Override
    public MarketingViews.Receipt updateMyCampaign(MerchantUpdateCampaignCommand command) {
        return service.updateMyCampaign(command);
    }

    @Override
    public MarketingViews.Receipt submitMyCampaign(MerchantCampaignStatusCommand command) {
        return service.submitMyCampaign(command);
    }

    @Override
    public MarketingViews.Receipt cancelMyCampaign(MerchantCampaignStatusCommand command) {
        return service.cancelMyCampaign(command);
    }
}
