package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreDisplayApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 */
@RestController
@ConditionalOnProperty(prefix = "pet.store.query", name = "enabled", havingValue = "true")
public class CStoreController {

    private static final java.util.regex.Pattern CITY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final MerchantStoreDisplayApiImpl stores;
    private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;

    public CStoreController(
            MerchantStoreDisplayApiImpl stores,
            ObjectProvider<MerchantApplicationCityCatalog> catalogs) {
        this.stores = Objects.requireNonNull(stores, "stores is required");
        this.catalogs = catalogs;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/stores")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        List<String> cityCodes = resolveCityCodes(city);
        MerchantStoreDisplayPageDTO value =
                stores.pageDisplayStores(
                        new MerchantStoreDisplayPageQuery(
                                cityCodes,
                                parse(page, 1, 10_000, "page"),
                                parse(pageSize, 20, 50, "pageSize"),
                                context(req)));
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
        return ApiResponse.success(view(value), trace(req));
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
        return row;
    }

    private static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"city", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"city".equals(name) && !"page".equals(name) && !"pageSize".equals(name)) {
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
