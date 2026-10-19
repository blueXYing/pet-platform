package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.query.CatalogServiceSearchQuery;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/stores/{storeId}/services, GET /api/v1/c/services/{serviceId} and
 * GET /api/v1/c/services (keyword search; CCR-W2-API-001 service domain, HTTP10 3.3.1/3.3.3).
 * Visibility = the approved four-condition conjunction; hidden or missing resources answer 404
 * indistinguishably, facts failures 503. Session is optional per the store-read STR-D8 ruling
 * (PRD "all users browse"): anonymous GET passes, an invalid carried bearer still 401s, and the
 * optional subject never changes visibility.
 */
@RestController
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class CServiceController {

    private final ServiceQueryApiImpl services;

    public CServiceController(ServiceQueryApiImpl services) {
        this.services = services;
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
     * HTTP10 §3.3.3 C catalog keyword search (2026-10-19 ruling): same projection as the
     * per-store page, matched on serviceName/categoryName by the service domain; the caller
     * narrows results with the keyword (1..30 code points after trimming) and pages over the
     * honest visible total.
     */
    @GetMapping("/api/v1/c/services")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req, "keyword");
        ServiceSnapshotPageDTO value =
                services.searchVisibleServices(
                        new CatalogServiceSearchQuery(
                                requiredKeyword(keyword),
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

    /** §3.3.3 keyword: required on this route, 1..30 code points after trimming. */
    private static String requiredKeyword(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 30) {
            throw invalid("keyword");
        }
        return trimmed;
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
