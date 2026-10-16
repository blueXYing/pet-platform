package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.common.ApiResponse;
import com.petplatform.community.api.dto.EncyclopediaBreedDetailView;
import com.petplatform.community.api.dto.EncyclopediaBreedPage;
import com.petplatform.community.api.dto.EncyclopediaCategoryView;
import com.petplatform.community.api.query.EncyclopediaBreedPageQuery;
import com.petplatform.community.biz.application.EncyclopediaContentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Encyclopedia public read face (contract 62, PRD 5.1.4): enabled category dictionary,
 * published-breed page list (category/keyword/tag) and breed detail with the same-category
 * related row. Anonymous GET per the STR-D8 browse ruling — the optional-session filter
 * passes requests without a bearer and still 401s a carried invalid one; visibility never
 * depends on a subject. This adapter only maps query strings and rejects unknown parameters.
 */
@RestController
@ConditionalOnProperty(prefix = "pet.encyclopedia.query", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CEncyclopediaController {

    private final EncyclopediaContentService encyclopedia;

    public CEncyclopediaController(EncyclopediaContentService encyclopedia) {
        this.encyclopedia = encyclopedia;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/api/v1/c/encyclopedia/categories")
    public ApiResponse<Map<String, Object>> categories(HttpServletRequest req) {
        rejectUnknownParameters(req);
        List<EncyclopediaCategoryView> items = encyclopedia.listEnabledCategories();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.stream().map(CEncyclopediaController::view).toList());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/encyclopedia/breeds")
    public ApiResponse<Map<String, Object>> breeds(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        EncyclopediaBreedPage value = encyclopedia.pageBreeds(new EncyclopediaBreedPageQuery(
                categoryCode,
                keyword,
                tag,
                parse(page, 1, 10_000, "page"),
                parse(pageSize, 20, 50, "pageSize")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CEncyclopediaController::view).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/encyclopedia/breeds/{breedId}")
    public ApiResponse<Map<String, Object>> breed(@PathVariable String breedId, HttpServletRequest req) {
        rejectQuery(req);
        return ApiResponse.success(view(encyclopedia.getBreed(breedId)), trace(req));
    }

    // ------------------------------------------------------------------ mapping

    private static Map<String, Object> view(EncyclopediaCategoryView category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCode", category.categoryCode());
        body.put("categoryName", category.categoryName());
        body.put("sortOrder", category.sortOrder());
        return body;
    }

    private static Map<String, Object> view(com.petplatform.community.api.dto.EncyclopediaBreedCardView breed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breedId", breed.breedId());
        body.put("categoryCode", breed.categoryCode());
        body.put("categoryName", breed.categoryName());
        body.put("name", breed.name());
        body.put("aliases", breed.aliases());
        body.put("tags", breed.tags());
        body.put("summary", breed.summary());
        body.put("coverUrl", breed.coverUrl());
        return body;
    }

    private static Map<String, Object> view(EncyclopediaBreedDetailView breed) {
        Map<String, Object> body = view((com.petplatform.community.api.dto.EncyclopediaBreedCardView) cardOf(breed));
        body.put("bodyType", breed.bodyType());
        body.put("facts", breed.facts());
        body.put("radar", breed.radar());
        body.put("descriptionHtml", breed.descriptionHtml());
        body.put("careAdviceHtml", breed.careAdviceHtml());
        body.put("healthTipsHtml", breed.healthTipsHtml());
        body.put("status", breed.status());
        body.put("related", breed.related().stream().map(CEncyclopediaController::view).toList());
        return body;
    }

    private static com.petplatform.community.api.dto.EncyclopediaBreedCardView cardOf(EncyclopediaBreedDetailView breed) {
        return new com.petplatform.community.api.dto.EncyclopediaBreedCardView(
                breed.breedId(), breed.categoryCode(), breed.categoryName(), breed.name(),
                breed.aliases(), breed.tags(), breed.summary(), breed.coverUrl());
    }

    private static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"categoryCode", "keyword", "tag", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"categoryCode".equals(name) && !"keyword".equals(name) && !"tag".equals(name)
                    && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid(name);
            }
        }
    }

    private static void rejectQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
    }

    private static com.petplatform.common.ApiException invalid(String field) {
        return new com.petplatform.common.ApiException(
                com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
