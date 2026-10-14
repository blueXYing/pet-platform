package com.petplatform.community.biz.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.community.api.dto.EncyclopediaBreedCardView;
import com.petplatform.community.api.dto.EncyclopediaBreedDetailView;
import com.petplatform.community.api.dto.EncyclopediaBreedPage;
import com.petplatform.community.api.dto.EncyclopediaCategoryView;
import com.petplatform.community.api.query.EncyclopediaBreedPageQuery;
import com.petplatform.community.api.query.EncyclopediaQueryApi;
import com.petplatform.community.biz.infrastructure.persistence.EncyclopediaStore;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaBreedJoinEntity;
import com.petplatform.community.biz.infrastructure.persistence.entity.EncyclopediaCategoryEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Encyclopedia public read kernel (contract 62): PRD 5.1.4 semantics — enabled dictionary
 * categories only, published-and-not-offline breeds only, keyword matches name/aliases,
 * tags filter exactly, same-category related row, offline/missing detail answers 404
 * BREED_NOT_FOUND. Read-only: no writes, no idempotency, no outbox (content production
 * lives with the operations governance slice A-005).
 */
public final class EncyclopediaContentService implements EncyclopediaQueryApi {

    /** PRD 5.1.4: search keyword ≤40 codepoints after trimming. */
    static final int KEYWORD_LIMIT = 40;
    static final int TAG_LIMIT = 30;
    static final int RELATED_LIMIT = 3;
    static final String DEFAULT_CATEGORY = "DOG";

    private static final ObjectMapper CODEC = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, String>>> FACTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Integer>> RADAR = new TypeReference<>() {};

    private final EncyclopediaStore store;

    public EncyclopediaContentService(DataSource dataSource) {
        this.store = new EncyclopediaStore(Objects.requireNonNull(dataSource, "dataSource is required"));
    }

    @Override
    public List<EncyclopediaCategoryView> listEnabledCategories() {
        return store.listEnabledCategories().stream()
                .map(EncyclopediaContentService::toCategoryView).toList();
    }

    @Override
    public EncyclopediaBreedPage pageBreeds(EncyclopediaBreedPageQuery query) {
        String keyword = normalizeText(query.keyword(), KEYWORD_LIMIT, "keyword");
        String tag = normalizeText(query.tag(), TAG_LIMIT, "tag");
        String categoryCode = query.categoryCode() == null || query.categoryCode().isBlank()
                ? DEFAULT_CATEGORY : query.categoryCode().trim();
        EncyclopediaCategoryEntity category = store.findEnabledCategoryByCode(categoryCode);
        if (category == null) {
            throw new IllegalArgumentException("categoryCode must name an ENABLED dictionary entry");
        }
        int page = query.page() == null ? 1 : query.page();
        int pageSize = query.pageSize() == null ? 20 : query.pageSize();
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("page/pageSize are outside the approved bounds");
        }
        String keywordPattern = keyword == null ? null : likeContains(keyword);
        long total = store.countPublishedBreeds(category.getId(), keywordPattern, tag);
        List<EncyclopediaBreedCardView> items = total == 0 ? List.of()
                : store.pagePublishedBreeds(category.getId(), keywordPattern, tag, pageSize, (page - 1) * pageSize)
                        .stream().map(row -> toCard(row, category)).toList();
        return new EncyclopediaBreedPage(items, page, pageSize, total);
    }

    @Override
    public EncyclopediaBreedDetailView getBreed(String breedId) {
        long id = parseBreedId(breedId);
        EncyclopediaBreedJoinEntity row = store.findPublishedBreedById(id);
        if (row == null) {
            // Anti-enumeration per contract 62: missing, not-yet-published and offline are alike.
            throw new ApiException("BREED_NOT_FOUND", "内容不存在或已下线");
        }
        List<EncyclopediaBreedCardView> related = store
                .listRelatedPublishedBreeds(row.getCategoryId(), id, RELATED_LIMIT)
                .stream().map(item -> toCard(item, categoryOf(row))).toList();
        return new EncyclopediaBreedDetailView(
                Long.toUnsignedString(row.getId()), row.getCategoryCode(), row.getCategoryName(),
                row.getName(), decodeStrings(row.getAliases()), decodeStrings(row.getTags()),
                row.getSummary(), row.getCoverUrl(), row.getBodyType(), decodeFacts(row.getFacts()),
                decodeRadar(row.getRadar()), row.getDescriptionHtml(), row.getCareAdviceHtml(),
                row.getHealthTipsHtml(), "PUBLISHED", related);
    }

    // ------------------------------------------------------------------ helpers

    private static EncyclopediaCategoryEntity categoryOf(EncyclopediaBreedJoinEntity row) {
        if (row.getCategoryCode() == null || row.getCategoryName() == null) {
            throw new IllegalStateException("Category projection missing for breed " + row.getId());
        }
        EncyclopediaCategoryEntity category = new EncyclopediaCategoryEntity();
        category.setId(row.getCategoryId());
        category.setCategoryCode(row.getCategoryCode());
        category.setCategoryName(row.getCategoryName());
        return category;
    }

    private static EncyclopediaCategoryView toCategoryView(EncyclopediaCategoryEntity row) {
        return new EncyclopediaCategoryView(row.getCategoryCode(), row.getCategoryName(),
                row.getSortNo() == null ? 0 : row.getSortNo());
    }
    private static EncyclopediaBreedCardView toCard(EncyclopediaBreedEntity row, EncyclopediaCategoryEntity category) {
        return new EncyclopediaBreedCardView(
                Long.toUnsignedString(row.getId()),
                category.getCategoryCode(), category.getCategoryName(),
                row.getName(), decodeStrings(row.getAliases()), decodeStrings(row.getTags()),
                row.getSummary(), row.getCoverUrl());
    }

    /** Blank query text means "no filter"; otherwise PRD codepoint limits apply. */
    private static String normalizeText(String raw, int limit, String field) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException(field + " exceeds the " + limit + "-codepoint limit");
        }
        return value;
    }

    /** Literal contains-match: LIKE wildcards in user input are escaped, not honoured. */
    private static String likeContains(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped.toLowerCase() + "%";
    }

    private static long parseBreedId(String breedId) {
        Objects.requireNonNull(breedId, "breedId is required");
        if (!breedId.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("breedId must be a decimal Snowflake String");
        }
        long id = Long.parseLong(breedId);
        if (id <= 0) throw new IllegalArgumentException("breedId must be positive");
        return id;
    }

    private static List<String> decodeStrings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(CODEC.readValue(json, STRINGS));
        } catch (Exception failure) {
            throw new IllegalStateException("Stored encyclopedia JSON array is not readable", failure);
        }
    }

    private static List<Map<String, String>> decodeFacts(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(CODEC.readValue(json, FACTS));
        } catch (Exception failure) {
            throw new IllegalStateException("Stored encyclopedia facts JSON is not readable", failure);
        }
    }

    private static Map<String, Integer> decodeRadar(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Integer> radar = CODEC.readValue(json, RADAR);
            return new LinkedHashMap<>(radar);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored encyclopedia radar JSON is not readable", failure);
        }
    }
}
