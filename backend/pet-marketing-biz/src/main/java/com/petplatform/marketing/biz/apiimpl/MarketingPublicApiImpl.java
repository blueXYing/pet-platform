package com.petplatform.marketing.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.application.MarketingPublicService;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of the contract-71 anonymous C-side marketing reads. */
public final class MarketingPublicApiImpl implements MarketingPublicApi {

    private final MarketingPublicService service;

    public MarketingPublicApiImpl(DataSource dataSource, SnowflakeIdGenerator ids, Clock clock,
            MarketingAssetObjectStore assetObjectStore) {
        this.service = new MarketingPublicService(
                new MarketingStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                clock, Objects.requireNonNull(assetObjectStore, "assetObjectStore is required"));
    }

    @Override
    public List<MarketingViews.PlacementCard> listPlacements(PublicPlacementQuery query) {
        return service.listPlacements(query);
    }

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(PublicCampaignListQuery query) {
        return service.listCampaigns(query);
    }

    @Override
    public List<MarketingViews.TagView> listTags(PublicTagQuery query) {
        return service.listTags(query);
    }

    @Override
    public MarketingViews.CampaignDetail getCampaign(PublicCampaignQuery query) {
        return service.getCampaign(query);
    }

    @Override
    public AssetContent asset(String assetId) {
        return service.asset(assetId);
    }

    @Override
    public List<MarketingViews.HomeBlockCard> listHomeBlocks(PublicHomeBlockQuery query) {
        return service.listHomeBlocks(query);
    }

    @Override
    public ActiveCampaignView activeCampaignForStore(String storeId) {
        return service.activeCampaignForStore(storeId);
    }
}
