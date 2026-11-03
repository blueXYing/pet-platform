package com.petplatform.marketing.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.application.MarketingAdminAuthorizationPort;
import com.petplatform.marketing.biz.application.MarketingAdminService;
import com.petplatform.marketing.biz.application.MarketingCityCatalogPort;
import com.petplatform.marketing.biz.application.MarketingCouponFactsPort;
import com.petplatform.marketing.biz.application.MarketingServiceCategoryPort;
import com.petplatform.marketing.biz.application.MarketingStoreFactsPort;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-71 platform-ops marketing face. */
public final class MarketingAdminApiImpl implements MarketingAdminApi {

    private final MarketingAdminService service;

    public MarketingAdminApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            MarketingAdminAuthorizationPort authorization, MarketingStoreFactsPort storeFacts,
            MarketingCouponFactsPort couponFacts, MarketingCityCatalogPort cityCatalog, Clock clock,
            boolean allowHttpAssets, MarketingAssetObjectStore assetObjectStore,
            MarketingServiceCategoryPort serviceCategories, boolean categoryValidationEnabled) {
        this.service = new MarketingAdminService(
                new MarketingStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(authorization, "authorization is required"),
                Objects.requireNonNull(storeFacts, "storeFacts is required"),
                Objects.requireNonNull(couponFacts, "couponFacts is required"),
                Objects.requireNonNull(cityCatalog, "cityCatalog is required"),
                clock, allowHttpAssets, Objects.requireNonNull(assetObjectStore, "assetObjectStore is required"),
                Objects.requireNonNull(serviceCategories, "serviceCategories is required"),
                categoryValidationEnabled);
    }

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(CampaignListQuery query) {
        return service.listCampaigns(query);
    }

    @Override
    public MarketingViews.CampaignDetail getCampaign(CampaignQuery query) {
        return service.getCampaign(query);
    }

    @Override
    public MarketingViews.Receipt createCampaign(CreateCampaignCommand command) {
        return service.createCampaign(command);
    }

    @Override
    public MarketingViews.Receipt updateCampaign(UpdateCampaignCommand command) {
        return service.updateCampaign(command);
    }

    @Override
    public MarketingViews.Receipt replaceStores(ReplaceStoresCommand command) {
        return service.replaceStores(command);
    }

    @Override
    public MarketingViews.Receipt savePage(SavePageCommand command) {
        return service.savePage(command);
    }

    @Override
    public MarketingViews.Receipt publishCampaign(CampaignStatusCommand command) {
        return service.publishCampaign(command);
    }

    @Override
    public MarketingViews.Receipt pauseCampaign(CampaignStatusCommand command) {
        return service.pauseCampaign(command);
    }

    @Override
    public MarketingViews.Receipt endCampaign(CampaignStatusCommand command) {
        return service.endCampaign(command);
    }

    @Override
    public MarketingViews.Receipt cancelCampaign(CampaignStatusCommand command) {
        return service.cancelCampaign(command);
    }

    @Override
    public MarketingViews.Receipt rejectCampaign(RejectCampaignCommand command) {
        return service.rejectCampaign(command);
    }

    @Override
    public MarketingViews.Receipt rollbackPage(RollbackPageCommand command) {
        return service.rollbackPage(command);
    }

    @Override
    public MarketingViews.PagePreview previewCampaign(CampaignQuery query) {
        return service.previewCampaign(query);
    }

    @Override
    public MarketingViews.AssetView uploadAsset(AssetUploadCommand command) {
        return service.uploadAsset(command);
    }

    @Override
    public MarketingViews.Page<MarketingViews.PlacementRow> listPlacements(PlacementListQuery query) {
        return service.listPlacements(query);
    }

    @Override
    public MarketingViews.Receipt createPlacement(CreatePlacementCommand command) {
        return service.createPlacement(command);
    }

    @Override
    public MarketingViews.Receipt updatePlacement(UpdatePlacementCommand command) {
        return service.updatePlacement(command);
    }

    @Override
    public MarketingViews.Receipt enablePlacement(PlacementStatusCommand command) {
        return service.enablePlacement(command);
    }

    @Override
    public MarketingViews.Receipt disablePlacement(PlacementStatusCommand command) {
        return service.disablePlacement(command);
    }

    @Override
    public MarketingViews.Page<MarketingViews.HomeBlockRow> listHomeBlocks(HomeBlockListQuery query) {
        return service.listHomeBlocks(query);
    }

    @Override
    public MarketingViews.Receipt createHomeBlock(CreateHomeBlockCommand command) {
        return service.createHomeBlock(command);
    }

    @Override
    public MarketingViews.Receipt updateHomeBlock(UpdateHomeBlockCommand command) {
        return service.updateHomeBlock(command);
    }

    @Override
    public MarketingViews.Receipt enableHomeBlock(HomeBlockStatusCommand command) {
        return service.enableHomeBlock(command);
    }

    @Override
    public MarketingViews.Receipt disableHomeBlock(HomeBlockStatusCommand command) {
        return service.disableHomeBlock(command);
    }

    @Override
    public MarketingViews.Page<MarketingViews.EnrollmentView> listEnrollments(EnrollmentListQuery query) {
        return service.listEnrollments(query);
    }

    @Override
    public MarketingViews.Receipt reviewEnrollment(ReviewEnrollmentCommand command) {
        return service.reviewEnrollment(command);
    }
}
