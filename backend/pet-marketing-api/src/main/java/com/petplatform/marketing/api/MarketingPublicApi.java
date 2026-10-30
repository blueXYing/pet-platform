package com.petplatform.marketing.api;

import com.petplatform.marketing.api.dto.MarketingViews;
import java.util.List;

/**
 * Anonymous C-side marketing reads (contract 71 §3). The marketing kernel returns store links
 * only; boot hydrates merchant display facts and coupon campaign facts. Visibility is computed
 * from placement/campaign time windows and status; failures are caught by the HTTP adapter and
 * hide the module instead of blocking the page.
 */
public interface MarketingPublicApi {

    List<MarketingViews.PlacementCard> listPlacements(PublicPlacementQuery query);

    MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(PublicCampaignListQuery query);

    List<MarketingViews.TagView> listTags(PublicTagQuery query);

    MarketingViews.CampaignDetail getCampaign(PublicCampaignQuery query);

    /** CCR-MKT-003: active home content blocks (manual ops curation; no bidding in V1). */
    List<MarketingViews.HomeBlockCard> listHomeBlocks(PublicHomeBlockQuery query);

    /** First active campaign participation of one store, for the store-detail banner. */
    ActiveCampaignView activeCampaignForStore(String storeId);

    record PublicHomeBlockQuery(String cityCode) {}

    /** CCR-MKT-002 public artwork bytes; missing/retired ids are not enumerable (404). */
    AssetContent asset(String assetId);

    record AssetContent(byte[] content, String contentType, String sha256) {
        public AssetContent {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record ActiveCampaignView(
            String campaignId, String title, String subtitle, String tagName,
            String offerTag, String offerTitle, String offerCopy) {}

    record PublicPlacementQuery(String positionCode, String cityCode) {}

    record PublicCampaignListQuery(String tagCode, String cityCode, int page, int pageSize) {}

    record PublicTagQuery(String cityCode) {}

    record PublicCampaignQuery(String campaignId) {}
}
