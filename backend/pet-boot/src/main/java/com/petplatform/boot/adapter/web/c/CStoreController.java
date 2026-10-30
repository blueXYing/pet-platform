package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.marketing.api.MarketingPublicApi;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreDisplayApiImpl;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryStores;
import com.petplatform.service.api.query.CatalogServiceCategoryStoresQuery;
import com.petplatform.service.api.query.ServiceCatalogCategoryApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/stores and GET /api/v1/c/stores/{storeId} (CCR-W2-API-001 store read, STR-D1..D8).
 * Visibility is the approved three-condition conjunction; hidden or missing stores answer 404
 * STORE_NOT_FOUND indistinguishably, facts failures and compat integrity violations answer a
 * whole-page 503. Session is optional per STR-D8 (PRD "all users browse"): anonymous GET passes
 * through the bearer filter, an invalid or expired carried bearer still 401s, and the optional
 * subject never changes visibility.
 *
 * <p>Contract 74 §3.2 adds the optional {@code categoryCode} filter: the service domain resolves
 * the exact enabled dictionary code to the stores that hold an ACTIVE service in it, and this
 * controller hands that id set to the store page, which composes it with city/keyword/promoted by
 * AND. The response projection is unchanged.
 */
@RestController
@ConditionalOnProperty(prefix = "pet.store.query", name = "enabled", havingValue = "true")
public class CStoreController {

    private static final java.util.regex.Pattern CITY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    /** Contract 74 §2.1 stable categoryCode lexical shape. */
    private static final java.util.regex.Pattern CATEGORY_CODE =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final MerchantStoreDisplayApiImpl stores;
    private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;
    private final ObjectProvider<MarketingPublicApi> marketing;
    private final ObjectProvider<ServiceCatalogCategoryApi> serviceCategories;

    public CStoreController(
            MerchantStoreDisplayApiImpl stores,
            ObjectProvider<MerchantApplicationCityCatalog> catalogs,
            ObjectProvider<MarketingPublicApi> marketing,
            ObjectProvider<ServiceCatalogCategoryApi> serviceCategories) {
        this.stores = Objects.requireNonNull(stores, "stores is required");
        this.catalogs = catalogs;
        this.marketing = marketing;
        this.serviceCategories = serviceCategories;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/stores")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String promoted,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        List<String> cityCodes = resolveCityCodes(city);
        Set<Long> categoryStores = resolveCategoryStores(categoryCode, context(req));
        MerchantStoreDisplayPageDTO value =
                stores.pageDisplayStores(
                        new MerchantStoreDisplayPageQuery(
                                cityCodes,
                                keyword(keyword),
                                parse(page, 1, 10_000, "page"),
                                parse(pageSize, 20, 50, "pageSize"),
                                context(req),
                                promoted(promoted),
                                categoryStores));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CStoreController::view).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/stores/{storeId}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String storeId, HttpServletRequest req) {
        rejectQuery(req);
        MerchantStoreDisplayDTO value;
        try {
            value =
                    stores.getDisplayStore(
                            new MerchantStoreDisplayQuery(storeId, context(req)));
        } catch (ApiException missing) {
            if (CommonApiCodes.NOT_FOUND.equals(missing.code())) {
                // STR-D5: anti-probing — same STORE_NOT_FOUND for absent and ineligible.
                throw new ApiException("STORE_NOT_FOUND", "门店不存在");
            }
            throw missing;
        }
        Map<String, Object> data = view(value);
        MarketingPublicApi api = marketing.getIfAvailable();
        if (api != null) {
            try {
                MarketingPublicApi.ActiveCampaignView campaign = api.activeCampaignForStore(storeId);
                if (campaign == null) {
                    data.put("campaign", null);
                } else {
                    Map<String, Object> wire = new LinkedHashMap<>();
                    wire.put("campaignId", campaign.campaignId());
                    wire.put("title", campaign.title());
                    wire.put("subtitle", campaign.subtitle());
                    wire.put("tagName", campaign.tagName());
                    wire.put("offerTag", campaign.offerTag());
                    wire.put("offerTitle", campaign.offerTitle());
                    wire.put("offerCopy", campaign.offerCopy());
                    data.put("campaign", wire);
                }
            } catch (RuntimeException ignored) {
                data.put("campaign", null);
            }
        } else {
            data.put("campaign", null);
        }
        return ApiResponse.success(data, trace(req));
    }

    /**
     * HTTP10 §3.3.2 search supplement (2026-10-19 ruling): optional keyword, 1..30 code points
     * after trimming; an absent parameter stays absent (no filter), a blank-only value is 400
     * (the caller clears the search instead of sending spaces).
     */
    private static String keyword(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 30) {
            throw invalid("keyword");
        }
        return trimmed;
    }

    /**
     * STR-D3: the server-controlled open-city directory decides the range. An explicit city must
     * be lexically valid and open (else 400); omission means the whole current open set. An
     * empty/unavailable catalog fails closed as 503, never an unfiltered full listing.
     */
    private List<String> resolveCityCodes(String city) {
        if (city != null && !CITY.matcher(city).matches()) throw invalid("city");
        MerchantApplicationCityCatalog catalog = catalogs.getIfAvailable();
        if (catalog == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
        }
        List<MerchantApplicationCityCatalog.City> open = catalog.list(); // 503 when not configured
        if (city != null) {
            return open.stream()
                    .map(MerchantApplicationCityCatalog.City::code)
                    .filter(city::equals)
                    .findFirst()
                    .map(List::of)
                    .orElseThrow(() -> invalid("city"));
        }
        return open.stream().map(MerchantApplicationCityCatalog.City::code).toList();
    }

    /**
     * Contract 74 §3.2 category filter: the service domain resolves the exact enabled dictionary
     * code (unknown/disabled 400) to the stores holding an ACTIVE service in it. An absent
     * parameter returns null (no filter); a legal category without supply returns an empty set,
     * which correctly yields an empty 200 page. The service slice is a hard dependency of the
     * filter; when it is not assembled the filter fails closed (503) instead of being ignored.
     */
    private Set<Long> resolveCategoryStores(String categoryCode, QueryContext context) {
        if (categoryCode == null) return null;
        if (!CATEGORY_CODE.matcher(categoryCode).matches()) throw invalid("categoryCode");
        ServiceCatalogCategoryApi api = serviceCategories.getIfAvailable();
        if (api == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务分类暂时不可用");
        }
        CatalogServiceCategoryStores resolved;
        try {
            resolved =
                    api.listCatalogCategoryStores(
                            new CatalogServiceCategoryStoresQuery(categoryCode, context));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务分类暂时不可用");
        }
        Set<Long> storeIds = new HashSet<>();
        for (String storeId : resolved.storeIds()) {
            try {
                storeIds.add(IDS.fromApi(storeId));
            } catch (RuntimeException malformed) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务分类门店事实无效");
            }
        }
        return Set.copyOf(storeIds);
    }

    /** STR-D8 optional session: anonymous stays anonymous; visibility never depends on it. */
    private static QueryContext context(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (view instanceof MiniSessionView session) {
            return new QueryContext(trace(req), OperatorType.USER, session.userId());
        }
        return new QueryContext(trace(req), null, null);
    }

    private static Map<String, Object> view(MerchantStoreDisplayDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("storeId", item.storeId());
        row.put("merchantId", item.merchantId());
        row.put("storeName", item.storeName());
        row.put("merchantName", item.merchantName());
        row.put("address", item.address());
        row.put("longitude", item.longitude());
        row.put("latitude", item.latitude());
        row.put("phoneMasked", item.phoneMasked());
        row.put("cityCode", item.cityCode());
        if (item.promoLevel() != null || item.promoTitle() != null || item.promoText() != null) {
            Map<String, Object> promotion = new LinkedHashMap<>();
            promotion.put("level", item.promoLevel());
            promotion.put("title", item.promoTitle());
            promotion.put("text", item.promoText());
            row.put("promotion", promotion);
        } else {
            row.put("promotion", null);
        }
        return row;
    }

    /** C-side rail filter: absent/false = ordinary directory; only an explicit true enables it. */
    private static boolean promoted(String raw) {
        if (raw == null || raw.isEmpty() || "false".equals(raw)) return false;
        if ("true".equals(raw)) return true;
        throw invalid("promoted");
    }

    private static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name :
                new String[] {"city", "keyword", "categoryCode", "promoted", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"city".equals(name) && !"keyword".equals(name)
                    && !"categoryCode".equals(name) && !"promoted".equals(name)
                    && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid(name);
            }
        }
    }

    private static void rejectQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
