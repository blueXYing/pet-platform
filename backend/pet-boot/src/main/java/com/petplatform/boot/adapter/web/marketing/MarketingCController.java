package com.petplatform.boot.adapter.web.marketing;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.query.CouponCampaignQueryApi;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.marketing.api.dto.MarketingViews;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract-71 anonymous C-side marketing reads, with boot-composed merchant/coupon facts. */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"marketing.enabled", "marketing.c.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class MarketingCController {
    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }


    private final MarketingPublicApi marketing;
    private final MerchantStoreDisplayApi stores;
    private final CouponCampaignQueryApi coupons;
    private final ObjectProvider<MerchantApplicationCityCatalog> cities;

    public MarketingCController(MarketingPublicApi marketing,
            ObjectProvider<MerchantStoreDisplayApi> stores,
            ObjectProvider<CouponCampaignQueryApi> coupons,
            ObjectProvider<MerchantApplicationCityCatalog> cities) {
        this.marketing = marketing;
        this.stores = stores.getIfAvailable();
        this.coupons = coupons.getIfAvailable();
        this.cities = cities;
    }

    @GetMapping(value = "/api/v1/c/marketing/placements", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> placements(
            @RequestParam String positionCode,
            @RequestParam(required = false) String cityCode,
            HttpServletRequest req) {
        onlyParameters(req, "positionCode", "cityCode");
        List<MarketingViews.PlacementCard> items = marketing.listPlacements(
                new MarketingPublicApi.PublicPlacementQuery(positionCode, cityCode));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.stream().map(MarketingCController::placement).toList());
        return ok(data, req);
    }

    @GetMapping(value = "/api/v1/c/marketing/campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> campaigns(
            @RequestParam(required = false) String tagCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "tagCode", "cityCode", "page", "pageSize");
        MarketingViews.Page<MarketingViews.CampaignCard> result = marketing.listCampaigns(
                new MarketingPublicApi.PublicCampaignListQuery(tagCode, cityCode, page(page), pageSize(pageSize)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(MarketingCController::campaign).toList());
        return ok(data, req);
    }

    @GetMapping(value = "/api/v1/c/marketing/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> tags(
            @RequestParam(required = false) String cityCode, HttpServletRequest req) {
        onlyParameters(req, "cityCode");
        List<MarketingViews.TagView> items = marketing.listTags(
                new MarketingPublicApi.PublicTagQuery(cityCode));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.stream().map(tag -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tagCode", tag.tagCode());
            row.put("tagName", tag.tagName());
            row.put("campaignCount", tag.campaignCount());
            return row;
        }).toList());
        return ok(data, req);
    }

    @GetMapping(value = "/api/v1/c/marketing/home-blocks", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> homeBlocks(
            @RequestParam(required = false) String cityCode,
            HttpServletRequest req) {
        onlyParameters(req, "cityCode");
        List<MarketingViews.HomeBlockCard> items = marketing.listHomeBlocks(
                new MarketingPublicApi.PublicHomeBlockQuery(cityCode));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.stream().map(MarketingCController::homeBlock).toList());
        return ok(data, req);
    }

    private static Map<String, Object> homeBlock(MarketingViews.HomeBlockCard block) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blockId", block.blockId());
        out.put("positionCode", block.positionCode());
        out.put("templateCode", block.templateCode());
        out.put("title", block.title());
        out.put("subtitle", block.subtitle());
        out.put("tagName", block.tagName());
        out.put("imageUrl", block.imageUrl());
        out.put("targetType", block.targetType());
        out.put("targetValue", block.targetValue());
        out.put("cityCode", block.cityCode());
        out.put("sponsorMerchantId", block.sponsorMerchantId());
        out.put("sponsorStoreId", block.sponsorStoreId());
        out.put("sortWeight", block.sortWeight());
        return out;
    }

    @GetMapping(value = "/api/v1/c/marketing/campaigns/{campaignId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> campaignDetail(@PathVariable String campaignId,
            HttpServletRequest req) {
        noQuery(req);
        MarketingViews.CampaignDetail detail;
        try {
            detail = marketing.getCampaign(new MarketingPublicApi.PublicCampaignQuery(id(campaignId)));
        } catch (ApiException failure) {
            if (CommonApiCodes.NOT_FOUND.equals(failure.code())) {
                throw new ApiException("CAMPAIGN_NOT_FOUND", "活动不存在");
            }
            throw failure;
        }
        String userId = sessionUserId(req);
        List<Map<String, Object>> storeCards = new ArrayList<>();
        if (stores != null) {
            for (MarketingViews.CampaignStoreLink link : detail.stores()) {
                Map<String, Object> card = storeCard(link, userId);
                if (card != null) storeCards.add(card);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("campaign", campaign(detail.campaign()));
        data.put("schema", jsonObject(detail.publishedPageSchemaJson()));
        data.put("storeCount", storeCards.size());
        data.put("stores", storeCards);
        data.put("couponCampaign", couponCard(detail.campaign().couponCampaignId(), userId));
        return ok(data, req);
    }

    @GetMapping(value = "/api/v1/c/marketing/merchant-deals",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> merchantDeals(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String limit,
            HttpServletRequest req) {
        onlyParameters(req, "cityCode", "limit");
        if (cityCode != null && !cityCode.matches("[a-z][a-z0-9_-]{0,31}")) throw invalid();
        if (limit != null && !limit.matches("[0-9]{1,3}")) throw invalid();
        int size = limit == null || limit.isBlank() ? 10 : Integer.parseInt(limit);
        if (size < 1 || size > 20) throw invalid();
        List<Map<String, Object>> merged = new ArrayList<>();
        Map<String, Map<String, Object>> byStore = new LinkedHashMap<>();
        if (stores != null) {
            try {
                List<String> cityCodes = cityCodes(cityCode);
                MerchantStoreDisplayPageDTO page = stores.pageDisplayStores(
                        new MerchantStoreDisplayPageQuery(cityCodes, null, 1, 50,
                                new QueryContext(trace(req), null, null), true));
                page.items().stream()
                        .sorted(Comparator.comparingInt((MerchantStoreDisplayDTO dto) ->
                                dto.promoLevel() == null ? 99 : dto.promoLevel())
                                .thenComparing(MerchantStoreDisplayDTO::storeId))
                        .forEach(dto -> byStore.put(dto.storeId(),
                                dealCard(merchantDeal(dto, null), null)));
            } catch (RuntimeException ignored) {
                // A facts failure hides the rail; it never blocks the services page.
            }
        }
        try {
            for (MarketingViews.PlacementCard placement : marketing.listPlacements(
                    new MarketingPublicApi.PublicPlacementQuery("STORE_LIST_TOP", cityCode))) {
                MarketingViews.CampaignDetail detail = marketing.getCampaign(
                        new MarketingPublicApi.PublicCampaignQuery(placement.campaignId()));
                for (MarketingViews.CampaignStoreLink link : detail.stores()) {
                    Map<String, Object> card = storeCard(link, null);
                    if (card == null) continue;
                    card.put("source", "CAMPAIGN");
                    card.put("campaignId", placement.campaignId());
                    byStore.put((String) card.get("storeId"),
                            dealCard(card, placement.coverUrl()));
                }
            }
        } catch (RuntimeException ignored) {
            // Campaign placement failure leaves merchant promotions in place.
        }
        merged.addAll(byStore.values());
        merged = merged.stream().limit(size).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", merged);
        return ok(data, req);
    }

    // ---------------------------------------------------------------- composition

    /**
     * Contract 71 v0.3 §3.4 / 74 §3.5 deal image ({@code imageUrl/imageUrlExpiresAt}): the
     * priority is store avatar → first store photo → campaign cover → null. The store-photo
     * half rides the private-asset signing pipeline (31号), which today has no anonymous
     * store-photo projection or STORE_PHOTO signing face to compose — until that cross-module
     * contract lands, this card serves the public campaign cover (marketing asset URL,
     * {@code imageUrlExpiresAt=null}) and stays null otherwise. No grant, object key, signing
     * key or credential ever enters this response.
     */
    private static Map<String, Object> dealCard(Map<String, Object> card, String campaignCoverUrl) {
        card.put("imageUrl", campaignCoverUrl == null || campaignCoverUrl.isBlank()
                ? null : campaignCoverUrl);
        card.put("imageUrlExpiresAt", null);
        return card;
    }

    private Map<String, Object> storeCard(MarketingViews.CampaignStoreLink link, String userId) {
        if (stores == null) return null;
        try {
            MerchantStoreDisplayDTO dto = stores.getDisplayStore(new MerchantStoreDisplayQuery(
                    link.storeId(), new QueryContext(traceOnly(), userId == null
                    ? null : OperatorType.USER, userId)));
            Map<String, Object> card = merchantDeal(dto, null);
            card.put("offerTag", link.offerTag());
            card.put("offerTitle", link.offerTitle());
            card.put("offerCopy", link.offerCopy());
            card.put("source", "CAMPAIGN");
            return card;
        } catch (RuntimeException hidden) {
            return null;
        }
    }

    private Map<String, Object> couponCard(String couponCampaignId, String userId) {
        if (couponCampaignId == null || coupons == null) return null;
        try {
            CouponCampaignQueryApi.CouponCampaignView view = coupons.getCampaign(
                    couponCampaignId, userId, new QueryContext(traceOnly(),
                            userId == null ? null : OperatorType.USER, userId));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("campaignId", view.campaignId());
            out.put("name", view.name());
            out.put("templateId", view.templateId());
            out.put("amountOff", view.amountOff() == null ? null : view.amountOff().toPlainString());
            out.put("thresholdAmount", view.thresholdAmount() == null ? null
                    : view.thresholdAmount().toPlainString());
            out.put("typeLabel", view.typeLabel());
            out.put("remaining", view.remaining());
            out.put("perUserLimit", view.perUserLimit());
            out.put("claimed", view.claimed());
            out.put("canClaim", view.canClaim());
            out.put("reason", view.reason());
            out.put("startAt", time(view.startAt()));
            out.put("endAt", time(view.endAt()));
            out.put("status", view.status());
            return out;
        } catch (RuntimeException hidden) {
            return null;
        }
    }

    private static Map<String, Object> merchantDeal(MerchantStoreDisplayDTO dto, String campaignId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", dto.storeId());
        out.put("storeName", dto.storeName());
        out.put("merchantName", dto.merchantName());
        out.put("cityCode", dto.cityCode());
        out.put("address", dto.address());
        out.put("source", "MERCHANT_PROMOTION");
        out.put("campaignId", campaignId);
        if (dto.promoLevel() != null || dto.promoTitle() != null || dto.promoText() != null) {
            Map<String, Object> promotion = new LinkedHashMap<>();
            promotion.put("level", dto.promoLevel());
            promotion.put("title", dto.promoTitle());
            promotion.put("text", dto.promoText());
            out.put("promotion", promotion);
            out.put("offerTag", dto.promoLevel() == null ? null : dto.promoLevel() + "折");
            out.put("offerTitle", dto.promoTitle());
            out.put("offerCopy", dto.promoText());
        } else {
            out.put("promotion", null);
            out.put("offerTag", null);
            out.put("offerTitle", null);
            out.put("offerCopy", null);
        }
        return out;
    }

    // ---------------------------------------------------------------- wire

    private static Map<String, Object> placement(MarketingViews.PlacementCard p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placementId", p.placementId());
        out.put("positionCode", p.positionCode());
        out.put("campaignId", p.campaignId());
        out.put("title", p.title());
        out.put("subtitle", p.subtitle());
        out.put("tagCode", p.tagCode());
        out.put("tagName", p.tagName());
        out.put("coverUrl", p.coverUrl());
        out.put("templateCode", p.templateCode());
        out.put("jumpType", p.jumpType());
        out.put("jumpParams", p.jumpParams());
        out.put("startAt", time(p.startAt()));
        out.put("endAt", time(p.endAt()));
        return out;
    }

    private static Map<String, Object> campaign(MarketingViews.CampaignCard c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", c.campaignId());
        out.put("title", c.title());
        out.put("subtitle", c.subtitle());
        out.put("tagCode", c.tagCode());
        out.put("tagName", c.tagName());
        out.put("coverUrl", c.coverUrl());
        out.put("startAt", time(c.startAt()));
        out.put("endAt", time(c.endAt()));
        out.put("storeCount", c.storeCount());
        return out;
    }

    private List<String> cityCodes(String cityCode) {
        if (cityCode != null && !cityCode.isBlank()) return List.of(cityCode);
        MerchantApplicationCityCatalog catalog = cities.getIfAvailable();
        if (catalog == null) throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "开放城市目录不可用");
        return catalog.list().stream().map(MerchantApplicationCityCatalog.City::code).toList();
    }

    private static String sessionUserId(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        return view instanceof MiniSessionView session ? session.userId() : null;
    }

    private static String traceOnly() {
        return "marketing";
    }

}
