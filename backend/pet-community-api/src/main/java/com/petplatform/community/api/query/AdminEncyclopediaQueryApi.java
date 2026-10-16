package com.petplatform.community.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only platform encyclopedia face for the single operator (contract 65 §3, PRD 6.10,
 * A-005): all-status category dictionary with breed counters, all-status breed page and the
 * full breed detail (OFFLINE rows answer, unlike the C-end 62 read face). The caller is always
 * the ADMIN_WEB operator carried by {@link QueryContext}; {@code authorization} re-proves the
 * current session, action code (encyclopedia.read / breed.read) and the ALL data scope inside
 * the community kernel on every call. No write path lives here — maintenance moves belong to
 * {@code AdminEncyclopediaCommandApi}.
 */
public interface AdminEncyclopediaQueryApi {

    /** One page of all-status categories, fixed order sort_no ASC, id ASC. */
    PageResult<AdminCategoryRow> listCategories(AdminCategoryListQuery query);

    /** One page of all-status breeds, fixed order sort_no ASC, id ASC. */
    PageResult<AdminBreedCard> listBreeds(AdminBreedListQuery query);

    /** Full breed detail in any status; unknown id answers COMMON_NOT_FOUND (anti-enumeration). */
    AdminBreedDetail getBreed(AdminBreedDetailQuery query);

    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /** All-status category row with read-side impact counters (PRD 6.10-1 统计只读). */
    record AdminCategoryRow(
            String categoryCode, String categoryName, String status, int sortNo,
            long breedCount, long publishedBreedCount, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** All-status breed card; identity/content fields mirror the 62 C-end card plus facts. */
    record AdminBreedCard(
            String breedId, String categoryCode, String categoryName, String name,
            List<String> aliases, List<String> tags, String summary, String coverUrl,
            String status, int sortNo, long version, OffsetDateTime updatedAt) {}

    record AdminBreedDetail(
            String breedId, String categoryCode, String categoryName, String name,
            List<String> aliases, List<String> tags, String summary, String coverUrl,
            String bodyType, String descriptionHtml, String careAdviceHtml,
            String healthTipsHtml, String status, int sortNo, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    record AdminCategoryListQuery(int page, int pageSize, QueryContext context,
            AdminAuthorization authorization) {}

    /**
     * @param status PUBLISHED/OFFLINE or null (no filter)
     * @param categoryCode enabled-or-disabled dictionary code or null (no filter)
     * @param keyword 2..40 codepoints, matches name/aliases contains (escaped literals)
     */
    record AdminBreedListQuery(String status, String categoryCode, String keyword,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminBreedDetailQuery(String breedId, QueryContext context,
            AdminAuthorization authorization) {}
}
