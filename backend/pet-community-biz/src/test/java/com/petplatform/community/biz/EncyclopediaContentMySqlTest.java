package com.petplatform.community.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.community.api.dto.EncyclopediaBreedDetailView;
import com.petplatform.community.api.dto.EncyclopediaBreedPage;
import com.petplatform.community.api.dto.EncyclopediaCategoryView;
import com.petplatform.community.api.query.EncyclopediaBreedPageQuery;
import com.petplatform.community.biz.application.EncyclopediaContentService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract 62 encyclopedia read kernel over the real SQL62 seed: enabled dictionary order,
 * published-only visibility, name/alias keyword search with literal-wildcard escaping, exact
 * tag filtering, PRD bounds, related-row semantics and the BREED_NOT_FOUND anti-enumeration 404.
 */
class EncyclopediaContentMySqlTest {

    private EncyclopediaContentService service(MySqlCommunityTestDatabase db) {
        return new EncyclopediaContentService(db.dataSource());
    }

    private static EncyclopediaBreedPageQuery query(
            String categoryCode, String keyword, String tag, Integer page, Integer pageSize) {
        return new EncyclopediaBreedPageQuery(categoryCode, keyword, tag, page, pageSize);
    }

    @Test void enabledDictionaryListsPrdCategoriesInOrder() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            List<EncyclopediaCategoryView> items = service(db).listEnabledCategories();
            assertEquals(List.of("DOG", "CAT", "REPTILE", "BIRD", "INSECT"),
                    items.stream().map(EncyclopediaCategoryView::categoryCode).toList());
            assertEquals(List.of("狗狗", "猫咪", "爬宠", "鸟类", "昆虫"),
                    items.stream().map(EncyclopediaCategoryView::categoryName).toList());

            // A disabled category leaves the dictionary (PRD: 只展示后台启用分类).
            db.jdbc().update("UPDATE encyclopedia_category SET status='DISABLED' WHERE category_code='INSECT'");
            items = service(db).listEnabledCategories();
            assertEquals(4, items.size());
            // ...and a category-scoped read on it is rejected (contract 62: 停用分类 → 400).
            assertThrows(IllegalArgumentException.class,
                    () -> service(db).pageBreeds(query("INSECT", null, null, null, null)));
        }
    }

    @Test void seedListIsPublishedOnlyAndCuratedOrderHolds() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaBreedPage page = service(db).pageBreeds(query("DOG", null, null, 1, 20));
            assertEquals(8, page.total());
            assertEquals(List.of("贵宾犬", "金毛寻回犬", "比熊犬", "威尔士柯基犬", "拉布拉多寻回犬", "博美犬", "柴犬", "法国斗牛犬"),
                    page.items().stream().map(breed -> breed.name()).toList());
            assertEquals("狗狗", page.items().get(0).categoryName());
            assertEquals("泰迪", page.items().get(0).aliases().get(0));
            assertFalse(page.items().get(0).coverUrl().isBlank());

            // An OFFLINE breed disappears from the list on the next read.
            db.jdbc().update("UPDATE encyclopedia_breed SET status='OFFLINE' WHERE name='柴犬'");
            EncyclopediaBreedPage after = service(db).pageBreeds(query("DOG", null, null, null, null));
            assertEquals(7, after.total());
            assertTrue(after.items().stream().noneMatch(breed -> "柴犬".equals(breed.name())));
        }
    }

    @Test void keywordMatchesNamesAndAliasesWithLiteralWildcards() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaContentService service = service(db);

            // PRD: 别名参与搜索匹配.
            assertEquals(List.of("贵宾犬"), service.pageBreeds(query("DOG", "泰迪", null, null, null))
                    .items().stream().map(breed -> breed.name()).toList());
            // Case-insensitive name contains.
            assertEquals("贵宾犬", service.pageBreeds(query("DOG", "贵宾", null, null, null)).items().get(0).name());
            // Blank keyword means no filter.
            assertEquals(8, service.pageBreeds(query("DOG", "   ", null, null, null)).total());
            // LIKE wildcards in user input stay literals (never broaden the result set).
            assertEquals(0, service.pageBreeds(query("DOG", "%", null, null, null)).total());
            assertEquals(0, service.pageBreeds(query("DOG", "犬_", null, null, null)).total());
            // PRD cap: keyword over 40 codepoints is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> service.pageBreeds(query("DOG", "长".repeat(41), null, null, null)));
        }
    }

    @Test void tagFilterIsAnExactDictionaryMatch() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaContentService service = service(db);
            List<String> shedding = service.pageBreeds(query("DOG", null, "掉毛多", null, null))
                    .items().stream().map(breed -> breed.name()).toList();
            assertTrue(shedding.contains("金毛寻回犬"));
            assertTrue(shedding.contains("柴犬"));
            assertTrue(shedding.stream().noneMatch(name -> "贵宾犬".equals(name)));
            // Partial tag text is not a tag (exact match, PRD 平台标签字典).
            assertEquals(0, service.pageBreeds(query("DOG", null, "掉毛", null, null)).total());
            assertEquals(0, service.pageBreeds(query("DOG", null, "不存在的标签", null, null)).total());
        }
    }

    @Test void unknownCategoryAndBadBoundsAreRejected() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaContentService service = service(db);
            assertThrows(IllegalArgumentException.class, () -> service.pageBreeds(query("FISH", null, null, null, null)));
            assertThrows(IllegalArgumentException.class, () -> service.pageBreeds(query("DOG", null, null, 0, null)));
            assertThrows(IllegalArgumentException.class, () -> service.pageBreeds(query("DOG", null, null, null, 51)));
            assertThrows(IllegalArgumentException.class, () -> service.pageBreeds(query("DOG", null, "标".repeat(31), null, null)));
        }
    }

    @Test void paginationSlicesWithoutDuplicates() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaContentService service = service(db);
            EncyclopediaBreedPage first = service.pageBreeds(query("DOG", null, null, 1, 5));
            assertEquals(8, first.total());
            assertEquals(5, first.items().size());
            EncyclopediaBreedPage second = service.pageBreeds(query("DOG", null, null, 2, 5));
            assertEquals(3, second.items().size());
            List<String> names = new java.util.ArrayList<>(first.items().stream().map(breed -> breed.name()).toList());
            second.items().forEach(breed -> names.add(breed.name()));
            assertEquals(8, names.size());
            assertEquals(8, names.stream().distinct().count());
            // Beyond the last page: empty items, total stays the truth.
            assertEquals(0, service.pageBreeds(query("DOG", null, null, 9, 5)).items().size());
        }
    }

    @Test void detailCarriesFullProjectionAndRelatedExcludesSelf() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            EncyclopediaContentService service = service(db);
            String id = service.pageBreeds(query("DOG", "贵宾", null, null, null)).items().get(0).breedId();
            EncyclopediaBreedDetailView detail = service.getBreed(id);
            assertEquals("贵宾犬", detail.name());
            assertEquals("DOG", detail.categoryCode());
            assertEquals("PUBLISHED", detail.status());
            assertTrue(detail.aliases().contains("泰迪"));
            assertTrue(detail.tags().contains("聪明"));
            assertTrue(detail.tags().size() <= 10);
            assertEquals(3, detail.facts().size());
            assertEquals("原产地", detail.facts().get(0).get("label"));
            assertEquals(5, detail.radar().get("companion"));
            assertTrue(detail.descriptionHtml().contains("起源历史"));
            assertTrue(detail.careAdviceHtml() != null && !detail.careAdviceHtml().isBlank());
            assertTrue(detail.healthTipsHtml().contains("常见健康问题"));

            // Related: same category, published, self excluded, curated order, at most 3.
            assertEquals(3, detail.related().size());
            assertTrue(detail.related().stream().noneMatch(breed -> breed.breedId().equals(id)));
            assertEquals("金毛寻回犬", detail.related().get(0).name());

            // Offline detail is indistinguishable from missing: BREED_NOT_FOUND 404 semantics.
            db.jdbc().update("UPDATE encyclopedia_breed SET status='OFFLINE' WHERE id=" + Long.parseLong(id));
            ApiException offline = assertThrows(ApiException.class, () -> service.getBreed(id));
            assertEquals("BREED_NOT_FOUND", offline.code());
            ApiException missing = assertThrows(ApiException.class, () -> service.getBreed("999"));
            assertEquals("BREED_NOT_FOUND", missing.code());
            // Non-numeric / malformed ids are plain invalid arguments, never a 404.
            assertThrows(IllegalArgumentException.class, () -> service.getBreed("abc"));
            assertThrows(IllegalArgumentException.class, () -> service.getBreed("-1"));
        }
    }
}
