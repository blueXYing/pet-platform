package com.petplatform.marketing.biz.application;

import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.marketing.biz.application.port.MarketingAssetObjectStore;
import com.petplatform.marketing.biz.infrastructure.persistence.MarketingStore;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.CampaignRow;
import com.petplatform.marketing.biz.infrastructure.persistence.entity.MarketingEntities.PlacementRow;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Anonymous C-side reads (contract 71 §3); boot hydrates merchant/coupon facts. */
public final class MarketingPublicService extends MarketingSupport implements MarketingPublicApi {

    private static final java.util.Set<String> POSITIONS =
            java.util.Set.of("HOME_BANNER", "HOME_ACTIVITY", "STORE_LIST_TOP", "HOT_SERVICE_TOP");

    private final MarketingAssetObjectStore assetObjectStore;

    public MarketingPublicService(MarketingStore store, Clock clock,
            MarketingAssetObjectStore assetObjectStore) {
        super(store, null, null, null, clock);
        this.assetObjectStore = Objects.requireNonNull(assetObjectStore);
    }

    @Override
    public List<MarketingViews.PlacementCard> listPlacements(PublicPlacementQuery query) {
        Objects.requireNonNull(query, "query is required");
        if (query.positionCode() == null || !POSITIONS.contains(query.positionCode())) {
            invalid("positionCode is invalid");
        }
        String city = query.cityCode() == null ? null : query.cityCode().strip();
        if (city != null && !city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
        return store.read(mapper -> {
            List<PlacementRow> rows = mapper.selectActivePlacements(query.positionCode(), nowUtc());
            // Contract 71 v0.3 §3.1: HOME_BANNER at most 3 items; the platform-wide single
            // HOT_SERVICE_TOP banner answers 0 or 1 item after the city-scope filter
            // (placement cityCode null = every open city, a concrete city = that city only).
            int cap = "HOME_BANNER".equals(query.positionCode()) ? 3
                    : "HOT_SERVICE_TOP".equals(query.positionCode()) ? 1 : Integer.MAX_VALUE;
            List<MarketingViews.PlacementCard> items = new ArrayList<>();
            for (PlacementRow row : rows) {
                if (city != null && row.cityCode != null && !city.equals(row.cityCode)) continue;
                CampaignRow campaign = mapper.selectCampaignById(row.campaignId);
                if (campaign == null) continue;
                items.add(placementCard(row, campaign));
                if (items.size() >= cap) break;
            }
            return List.copyOf(items);
        });
    }

    @Override
    public MarketingViews.Page<MarketingViews.CampaignCard> listCampaigns(PublicCampaignListQuery query) {
        Objects.requireNonNull(query, "query is required");
        int page = query.page() < 1 ? 1 : Math.min(query.page(), 10_000);
        int pageSize = query.pageSize() < 1 ? 20 : Math.min(query.pageSize(), 50);
        String tag = query.tagCode() == null || query.tagCode().isBlank() ? null : query.tagCode().strip();
        String city = query.cityCode() == null || query.cityCode().isBlank() ? null : query.cityCode().strip();
        if (city != null && !city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
        return store.read(mapper -> {
            List<CampaignRow> visible = mapper.selectPublicCampaigns(nowUtc()).stream()
                    .filter(row -> tag == null || tag.equals(row.tagCode))
                    .filter(row -> city == null || cityAllowed(row, city))
                    .sorted(Comparator.comparing((CampaignRow row) -> row.sortWeight == null ? 0 : row.sortWeight)
                            .thenComparing(row -> row.startAt)
                            .thenComparing(row -> row.id))
                    .toList();
            long total = visible.size();
            long from = (long) (page - 1) * pageSize;
            List<MarketingViews.CampaignCard> items = new ArrayList<>();
            for (long index = from; index < from + pageSize && index < total; index++) {
                CampaignRow row = visible.get((int) index);
                items.add(campaignCard(row, (int) mapper.countCampaignStores(row.id)));
            }
            return new MarketingViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public List<MarketingViews.TagView> listTags(PublicTagQuery query) {
        Objects.requireNonNull(query, "query is required");
        String city = query.cityCode() == null || query.cityCode().isBlank() ? null : query.cityCode().strip();
        if (city != null && !city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
        return store.read(mapper -> {
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (CampaignRow row : mapper.selectPublicCampaigns(nowUtc())) {
                if (row.tagCode == null || row.tagCode.isBlank()) continue;
                if (city != null && !cityAllowed(row, city)) continue;
                names.putIfAbsent(row.tagCode, row.tagName == null ? row.tagCode : row.tagName);
                counts.merge(row.tagCode, 1, Integer::sum);
            }
            return counts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new MarketingViews.TagView(
                            entry.getKey(), names.get(entry.getKey()), entry.getValue()))
                    .toList();
        });
    }

    @Override
    public MarketingViews.CampaignDetail getCampaign(PublicCampaignQuery query) {
        Objects.requireNonNull(query, "query is required");
        long campaignId = id(query.campaignId());
        return store.read(mapper -> {
            CampaignRow row = mapper.selectCampaignById(campaignId);
            if (row == null || !SetHolder.ONLINE_STATUSES.contains(row.status)
                    || row.startAt == null || row.endAt == null
                    || row.startAt.isAfter(nowUtc()) || !row.endAt.isAfter(nowUtc())) {
                notFound("CAMPAIGN_NOT_FOUND");
            }
            List<MarketingViews.CampaignStoreLink> stores = mapper.selectCampaignStores(campaignId)
                    .stream().filter(store -> "ACTIVE".equals(store.status))
                    .map(MarketingSupport::storeLink).toList();
            String schema = MarketingPageSchema.publishedOrDefault(row);
            return new MarketingViews.CampaignDetail(
                    campaignCard(row, stores.size()), stores, row.pageSchemaJson,
                    schema, List.of());
        });
    }

    @Override
    public AssetContent asset(String assetId) {
        long id = id(assetId);
        MarketingEntities.AssetRow row = store.read(mapper -> mapper.selectAssetById(id));
        if (row == null || !"ACTIVE".equals(row.status)) notFound("素材不存在");
        byte[] content = row.content;
        if (content == null && row.objectKey != null && assetObjectStore.enabled()) {
            try {
                content = assetObjectStore.get(row.objectKey);
            } catch (RuntimeException failure) {
                unavailable("素材对象存储不可用");
            }
        }
        if (content == null || content.length == 0) notFound("素材不存在");
        return new AssetContent(content, row.contentType, row.sha256);
    }

    @Override
    public List<MarketingViews.HomeBlockCard> listHomeBlocks(PublicHomeBlockQuery query) {
        Objects.requireNonNull(query, "query is required");
        String city = query.cityCode() == null || query.cityCode().isBlank()
                ? null : query.cityCode().strip();
        if (city != null && !city.matches("[a-z][a-z0-9_-]{0,31}")) invalid("cityCode is invalid");
        return store.read(mapper -> mapper.selectActiveHomeBlocks(city, nowUtc()).stream()
                .map(MarketingSupport::homeBlockCard).toList());
    }

    @Override
    public ActiveCampaignView activeCampaignForStore(String storeId) {
        long id = id(storeId);
        return store.read(mapper -> {
            List<MarketingEntities.CampaignStoreRow> links = mapper.selectActiveStoreCampaigns(id, nowUtc());
            for (MarketingEntities.CampaignStoreRow link : links) {
                CampaignRow campaign = mapper.selectCampaignById(link.campaignId);
                if (campaign == null) continue;
                return new ActiveCampaignView(
                        Long.toUnsignedString(campaign.id), campaign.title, campaign.subtitle,
                        campaign.tagName, link.offerTag, link.offerTitle, link.offerCopy);
            }
            return null;
        });
    }

    private static boolean cityAllowed(CampaignRow row, String city) {
        List<String> cities = parseJsonList(row.cityCodes);
        return cities.isEmpty() || cities.contains(city);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ONLINE_STATUSES =
                java.util.Set.of("SCHEDULED", "ONLINE");
    }
}
