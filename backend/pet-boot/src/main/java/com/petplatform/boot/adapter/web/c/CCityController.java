package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.MerchantApplicationCityCatalog;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/cities (HTTP10 §3.3.4, 2026-10-19 city-selection ruling): the anonymous,
 * server-controlled open-city directory for C catalog browsing. The same trusted startup
 * catalog that bounds every store/service city filter — the response only exposes code/name
 * pairs, never application data, so the read is anonymous per the STR-D8 catalog discipline.
 * An unconfigured or empty catalog fails closed with 503 (never an implicit "no cities").
 */
@RestController
@ConditionalOnProperty(prefix = "pet.store.query", name = "enabled", havingValue = "true")
public class CCityController {

    private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;

    public CCityController(ObjectProvider<MerchantApplicationCityCatalog> catalogs) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs is required");
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/cities")
    public ApiResponse<Map<String, Object>> list(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query is invalid");
        }
        MerchantApplicationCityCatalog catalog = catalogs.getIfAvailable();
        if (catalog == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
        }
        List<MerchantApplicationCityCatalog.City> open = catalog.list(); // 503 when not configured
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", open.stream()
                .map(city -> Map.of("cityCode", city.code(), "cityName", city.name()))
                .toList());
        return ApiResponse.success(data, trace(req));
    }
}
