package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryItem;
import com.petplatform.service.api.dto.CatalogServiceCategoryTypes.CatalogServiceCategoryPage;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.query.CatalogServiceCategoryQuery;
import com.petplatform.service.api.query.CatalogServiceSearchQuery;
import com.petplatform.service.api.query.ServiceCatalogCategoryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/stores/{storeId}/services, GET /api/v1/c/services/{serviceId},
 * GET /api/v1/c/services (keyword/category search; CCR-W2-API-001 service domain, HTTP10
 * 3.3.1/3.3.3) and GET /api/v1/c/service-categories (contract 74 §3.1, B-SVC-03).
 * Visibility = the approved four-condition conjunction; hidden or missing resources answer 404
 * indistinguishably, facts failures 503. Session is optional per the store-read STR-D8 ruling
 * (PRD "all users browse"): anonymous GET passes, an invalid carried bearer still 401s, and the
 * optional subject never changes visibility.
 */
@RestController
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class CServiceController {

    /** Contract 74 §2.1 stable categoryCode lexical shape. */
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    /** Contract 74 §3.1 cityCode lexical shape (same as the open-city catalog entries). */
    private static final Pattern CITY_CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final ServiceQueryApiImpl services;
    private final ServiceCatalogCategoryApi categories;
    private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;

    public CServiceController(
            ServiceQueryApiImpl services,
            ServiceCatalogCategoryApi categories,
            ObjectProvider<MerchantApplicationCityCatalog> catalogs) {
        this.services = services;
        this.categories = categories;
        this.catalogs = catalogs;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/stores/{storeId}/services")
    public ApiResponse<Map<String, Object>> storeServices(
            @PathVariable String storeId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        QueryContext context = context(req);
        ServiceSnapshotPageDTO value =
                services.getStoreServiceSnapshots(
                        new StoreServiceSnapshotQuery(
                                storeId,
                                parse(page, 1, 10_000, "page"),
                                parse(pageSize, 20, 50, "pageSize"),
                                context));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CServiceController::summary).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    /**
     * Contract 74 §3.1 C category list (B-SVC-03): enabled categories with at least one C-visible
     * store holding an ACTIVE service, optionally restricted to one open city. Anonymous per the
     * same STR-D8 browse discipline; the city directory is the server-controlled range (unknown or
     * closed cityCode 400, unavailable/empty directory 503).
     */
    @GetMapping("/api/v1/c/service-categories")
    public ApiResponse<Map<String, Object>> categoryList(
            @RequestParam(required = false) String cityCode,
            HttpServletRequest req) {
        rejectUnknownCategoryParameters(req);
        List<String> cityCodes = resolveCityCodes(cityCode);
        CatalogServiceCategoryPage value =
                categories.listCatalogCategories(
                        new CatalogServiceCategoryQuery(cityCodes, context(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CServiceController::categoryView).toList());
        return ApiResponse.success(data, trace(req));
    }

    /**
     * Contract 74 §3.3 C catalog search: keyword and/or exact categoryCode, at least one present,
     * AND when both. Same projection as the per-store page; the service domain applies the
     * visibility conjunction and pages over the honest visible total. Anonymous per STR-D8.
     */
    @GetMapping("/api/v1/c/services")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req, "keyword", "categoryCode");
        ServiceSnapshotPageDTO value =
                services.searchVisibleServices(
                        new CatalogServiceSearchQuery(
                                optionalKeyword(keyword),
                                optionalCategoryCode(categoryCode),
                                parse(page, 1, 10_000, "page"),
                                parse(pageSize, 20, 50, "pageSize"),
                                context(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CServiceController::summary).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/services/{serviceId}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String serviceId, HttpServletRequest req) {
        rejectQuery(req);
        ServiceSnapshotDTO value =
                services.getVisibleService(
                        new ServiceSnapshotQuery(serviceId, context(req)));
        return ApiResponse.success(body(value), trace(req));
    }

    private static Map<String, Object> body(ServiceSnapshotDTO item) {
        Map<String, Object> row = summary(item);
        // Merchant drafts/submissions permit an omitted description. The C text projection
        // remains a string so valid services without optional copy still render.
        row.put("description", item.description() == null ? "" : item.description());
        return row;
    }

    private static Map<String, Object> summary(ServiceSnapshotDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", item.serviceId());
        row.put("merchantId", item.merchantId());
        row.put("storeId", item.storeId());
        row.put("serviceName", item.serviceName());
        row.put("categoryId", item.categoryId());
        row.put("categoryName", item.categoryName());
        row.put("salePrice", item.salePrice().toPlainString());
        row.put("durationMinutes", item.durationMinutes());
        row.put("fulfillmentType", item.fulfillmentType().name());
        // Cover display (2026-09-22 ruling #3): only consumer-visible reads carry the presigned
        // URL; hidden rows never reach here (404), rows without a cover binding keep cover null.
        if (item.coverAssetId() != null) {
            Map<String, Object> cover = new LinkedHashMap<>();
            cover.put("coverAssetId", item.coverAssetId());
            cover.put("coverUrl", item.coverUrl());
            cover.put("coverUrlExpiresAt", item.coverUrlExpiresAt());
            row.put("cover", cover);
        } else {
            row.put("cover", null);
        }
        return row;
    }

    /** STR-D8 optional session: anonymous stays anonymous; visibility never depends on it. */
    private static QueryContext context(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (view instanceof MiniSessionView session) {
            return new QueryContext(trace(req), OperatorType.USER, session.userId());
        }
        return new QueryContext(trace(req), null, null);
    }

    /**
     * Contract 74 §3.3 keyword: optional, but when present 1..30 code points after trimming; a
     * blank-only value is 400 (the caller omits the parameter instead of sending spaces).
     */
    private static String optionalKeyword(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 30) {
            throw invalid("keyword");
        }
        return trimmed;
    }

    /** Contract 74 §2.1/§3.3 categoryCode: optional, exact, stable-code lexical shape. */
    private static String optionalCategoryCode(String raw) {
        if (raw == null) return null;
        if (!CATEGORY_CODE.matcher(raw).matches()) throw invalid("categoryCode");
        return raw;
    }

    /**
     * Contract 74 §3.1 cityCode range: the server-controlled open-city directory decides. An
     * explicit code must be lexically valid and open (else 400); omission means the whole current
     * open set. A missing/empty catalog fails closed as 503, never an unfiltered full listing.
     */
    private List<String> resolveCityCodes(String cityCode) {
        if (cityCode != null && !CITY_CODE.matcher(cityCode).matches()) throw invalid("cityCode");
        MerchantApplicationCityCatalog catalog = catalogs.getIfAvailable();
        if (catalog == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
        }
        List<MerchantApplicationCityCatalog.City> open = catalog.list(); // 503 when not configured
        if (cityCode != null) {
            return open.stream()
                    .map(MerchantApplicationCityCatalog.City::code)
                    .filter(cityCode::equals)
                    .findFirst()
                    .map(List::of)
                    .orElseThrow(() -> invalid("cityCode"));
        }
        return open.stream().map(MerchantApplicationCityCatalog.City::code).toList();
    }

    /** Contract 74 §3.1 data projection (no status/version/audit facts leak to C). */
    private static Map<String, Object> categoryView(CatalogServiceCategoryItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("categoryCode", item.categoryCode());
        row.put("categoryName", item.categoryName());
        row.put("parentCode", item.parentCode());
        row.put("iconUrl", item.iconUrl());
        row.put("description", item.description());
        row.put("sortNo", item.sortNo());
        row.put("storeCount", item.storeCount());
        row.put("serviceCount", item.serviceCount());
        return row;
    }

    /** Category route accepts only cityCode (no paging): every other parameter is 400. */
    private static void rejectUnknownCategoryParameters(HttpServletRequest req) {
        String[] values = req.getParameterValues("cityCode");
        if (values != null && values.length > 1) throw invalid("cityCode");
        for (String name : req.getParameterMap().keySet()) {
            if (!"cityCode".equals(name)) throw invalid(name);
        }
    }

    private static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req, String... extra) {
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Set.of("page", "pageSize"));
        known.addAll(java.util.Arrays.asList(extra));
        for (String name : known) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!known.contains(name)) throw invalid(name);
        }
    }

    private static void rejectQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
