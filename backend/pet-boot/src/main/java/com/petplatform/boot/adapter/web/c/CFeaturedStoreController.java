package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.FeaturedStoreCardComposer;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/stores/featured (contract 74 §3.4, B-CARD-01/02/03): the service-tab featured
 * merchant cards, composed in boot from the merchant, service, review and order domain read ports
 * ({@link FeaturedStoreCardComposer}); no cross-module SQL and no query per store or service.
 * Session is optional per the STR-D8 browse discipline (PRD "all users browse"): anonymous GET
 * passes, an invalid carried bearer still 401s, and the optional subject never changes visibility.
 * Contract 74 uses {@code cityCode} (the marketing-read naming); the legacy {@code city} parameter
 * of /c/stores is deliberately not accepted here (400). No PII is projected: the card carries no
 * phone, coordinate, user id or order id.
 */
@RestController
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class CFeaturedStoreController {

    /** Contract 74 §2.1 stable categoryCode lexical shape. */
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    /** Contract 74 §3.4 cityCode lexical shape (same as the open-city catalog entries). */
    private static final Pattern CITY_CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final FeaturedStoreCardComposer composer;

    public CFeaturedStoreController(FeaturedStoreCardComposer composer) {
        this.composer = composer;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/stores/featured")
    public ApiResponse<Map<String, Object>> featured(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        if (cityCode != null && !CITY_CODE.matcher(cityCode).matches()) throw invalid("cityCode");
        if (categoryCode != null && !CATEGORY_CODE.matcher(categoryCode).matches()) {
            throw invalid("categoryCode");
        }
        FeaturedStoreCardComposer.Page value =
                composer.compose(
                        cityCode,
                        categoryCode,
                        parse(page, 1, 10_000, "page"),
                        parse(pageSize, 10, 20, "pageSize"),
                        context(req));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CFeaturedStoreController::view).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    /** Contract 74 §3.4 wire projection, field order per the contract example. */
    private static Map<String, Object> view(FeaturedStoreCardComposer.Card card) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("storeId", card.storeId());
        row.put("merchantId", card.merchantId());
        row.put("storeName", card.storeName());
        row.put("merchantName", card.merchantName());
        row.put("cityCode", card.cityCode());
        row.put("address", card.address());
        row.put("avatarUrl", card.avatarUrl());
        row.put("avatarUrlExpiresAt", card.avatarUrlExpiresAt());
        row.put("intro", card.intro());
        row.put("categoryCode", card.categoryCode());
        row.put("categoryName", card.categoryName());
        Map<String, Object> rating = new LinkedHashMap<>();
        rating.put("score", card.rating().score());
        rating.put("count", card.rating().count());
        rating.put("available", card.rating().available());
        row.put("rating", rating);
        row.put("monthlySold", card.monthlySold());
        row.put("tags", card.tags());
        List<Map<String, Object>> services = new java.util.ArrayList<>(card.services().size());
        for (FeaturedStoreCardComposer.ServiceLine service : card.services()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("serviceId", service.serviceId());
            line.put("serviceName", service.serviceName());
            line.put("categoryCode", service.categoryCode());
            line.put("categoryName", service.categoryName());
            line.put("salePrice", service.salePrice().toPlainString());
            line.put("listPrice", service.listPrice() == null ? null : service.listPrice().toPlainString());
            line.put("durationMinutes", service.durationMinutes());
            line.put("fulfillmentType", service.fulfillmentType());
            line.put("soldCount", service.soldCount());
            services.add(line);
        }
        row.put("services", services);
        if (card.promotion() != null) {
            Map<String, Object> promotion = new LinkedHashMap<>();
            promotion.put("source", card.promotion().source());
            promotion.put("offerTag", card.promotion().offerTag());
            promotion.put("offerTitle", card.promotion().offerTitle());
            promotion.put("offerCopy", card.promotion().offerCopy());
            promotion.put("campaignId", card.promotion().campaignId());
            row.put("promotion", promotion);
        } else {
            row.put("promotion", null);
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

    /** Contract 74 §3.4: only cityCode/categoryCode/page/pageSize; everything else is 400. */
    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name :
                new String[] {"cityCode", "categoryCode", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"cityCode".equals(name) && !"categoryCode".equals(name)
                    && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid(name);
            }
        }
    }

    private static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
