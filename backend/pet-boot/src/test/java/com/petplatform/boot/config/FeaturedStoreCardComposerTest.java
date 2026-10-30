package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.FeaturedStoreCardComposer.Card;
import com.petplatform.boot.config.FeaturedStoreCardComposer.Page;
import com.petplatform.boot.config.MerchantApplicationCityCatalog.City;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsApi;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsQuery;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi.FeaturedStoreFact;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi;
import com.petplatform.review.api.query.ReviewPublicFactsApi;
import com.petplatform.service.api.dto.FeaturedStoreServiceTypes.FeaturedStoreServiceItem;
import com.petplatform.service.api.query.FeaturedActiveServiceStoresQuery;
import com.petplatform.service.api.query.FeaturedStoreCatalogApi;
import com.petplatform.service.api.query.FeaturedStoreServicesQuery;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * B-CARD-01/02/03 composition acceptance over faked domain ports: the confirmed card order
 * (rating DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC), the &lt;5 display rule, both
 * degradation families (rating {@code available=false}, sales null — never fabricated zeros), the
 * sold-ranked top-2 selection, the primary-category rule, honest paging, the fail-closed avatar
 * signer discipline and the no-PII surface shape.
 */
class FeaturedStoreCardComposerTest {
    private static final QueryContext CTX = new QueryContext("composer-test", null, null);

    // ---------------------------------------------------------------- sorting & ordering

    @Test
    void cardOrderFollowsRatingThenMonthlyThenStoreId() {
        long a = 300, b = 200, c = 100, d = 400;
        Harness h = new Harness()
                .candidates(a, b, c, d)
                // a: rated 4.9, 10 sold; b: rated 4.9, 20 sold -> b first (monthly tiebreak)
                .rating(a, 20, "4.9").monthly(a, 10L)
                .rating(b, 20, "4.9").monthly(b, 20L)
                // c: no rating fact at all (score null sorts last) but the highest monthly count
                .monthly(c, 99L)
                // d: rated higher than a/b but no sales -> still first on score
                .rating(d, 8, "5.0");
        Page page = h.compose();
        assertEquals(List.of("400", "200", "300", "100"),
                page.items().stream().map(Card::storeId).toList(),
                "score DESC NULLS LAST, monthlySold DESC NULLS LAST, storeId ASC");
        assertEquals(4, page.total());
    }

    @Test
    void salesTieBreaksOnStoreIdAscending() {
        long a = 300, b = 100;
        Harness h = new Harness().candidates(a, b).rating(a, 0, null).rating(b, 0, null);
        Page page = h.compose();
        assertEquals(List.of("100", "300"), page.items().stream().map(Card::storeId).toList(),
                "equal (null) ratings and equal (0) sales order by storeId ASC");
    }

    // ---------------------------------------------------------------- rating degradation

    @Test
    void ratingBelowFiveReviewsShowsAvailableWithNullScore() {
        Harness h = new Harness().candidates(100).rating(100, 3, null).monthly(100, 5L);
        Card card = h.compose().items().get(0);
        assertNotNull(card.rating());
        assertNull(card.rating().score(), "score stays null under five included reviews");
        assertEquals(3, card.rating().count());
        assertTrue(card.rating().available());
    }

    @Test
    void ratingFactFailureDegradesToAvailableFalseWithoutFakeZero() {
        Harness h = new Harness().candidates(100).monthly(100, 5L).ratingFailure();
        Card card = h.compose().items().get(0);
        assertEquals(new FeaturedStoreCardComposer.Rating(null, 0, false), card.rating(),
                "a failed rating read hides the rating block, never a fabricated 0");
    }

    @Test
    void unassembledReviewKernelDegradesRatingWithoutBreakingThePage() {
        Harness h = new Harness().candidates(100).monthly(100, 5L).withoutReviews();
        Page page = h.compose();
        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).rating().available());
        assertEquals(Integer.valueOf(5), page.items().get(0).monthlySold(),
                "an unassembled review kernel does not degrade the sales facts");
    }

    // ---------------------------------------------------------------- sales degradation

    @Test
    void salesFactFailureDegradesMonthlyAndSoldToNull() {
        Harness h = new Harness().candidates(100, 200)
                .service(100, 11L, "PET_GROOMING", "宠物美容", 10)
                .service(200, 22L, "DOG_WALKING", "遛狗陪护", 20)
                .salesFailure();
        Card card = h.compose().items().get(0);
        assertNull(card.monthlySold(), "monthlySold=null marks the unavailable aggregation");
        assertEquals(1, card.services().size());
        assertNull(card.services().get(0).soldCount(), "soldCount=null marks the unavailable aggregation");
    }

    @Test
    void unassembledOrderFaceDegradesSalesWithoutBreakingThePage() {
        Harness h = new Harness().candidates(100).service(100, 11L, "PET_GROOMING", "宠物美容", 10)
                .withoutSales();
        Card card = h.compose().items().get(0);
        assertNull(card.monthlySold());
        assertNull(card.services().get(0).soldCount());
        // Degraded sales leave storeId ASC as the effective order.
    }

    @Test
    void availableSalesAnswerZeroForStoresWithoutOrders() {
        Harness h = new Harness().candidates(100).rating(100, 6, "4.0");
        Card card = h.compose().items().get(0);
        assertEquals(Integer.valueOf(0), card.monthlySold(),
                "an available aggregation answers an honest 0 for a store without orders");
    }

    // ---------------------------------------------------------------- services

    @Test
    void topTwoServicesFollowSoldCountThenServiceId() {
        Harness h = new Harness().candidates(100)
                .service(100, 11L, "PET_GROOMING", "宠物美容", 10)
                .service(100, 12L, "PET_GROOMING", "宠物美容", 10)
                .service(100, 13L, "DOG_WALKING", "遛狗陪护", 20)
                .sold(11L, 5L).sold(12L, 9L); // 13 never sold
        Card card = h.compose().items().get(0);
        assertEquals(List.of("12", "11"), card.services().stream()
                        .map(FeaturedStoreCardComposer.ServiceLine::serviceId).toList(),
                "9 sold, then 5 sold; the unsold service stays off the card");
        assertEquals(2, card.services().size());
        assertEquals(Integer.valueOf(9), card.services().get(0).soldCount());
        assertEquals(Integer.valueOf(5), card.services().get(1).soldCount());
        // Primary category = sold leader's category.
        assertEquals("PET_GROOMING", card.categoryCode());
        assertEquals(List.of("宠物美容"), card.tags());
    }

    @Test
    void soldTieOrdersServiceLinesByIdAscending() {
        Harness h = new Harness().candidates(100)
                .service(100, 12L, "PET_GROOMING", "宠物美容", 10)
                .service(100, 11L, "DOG_WALKING", "遛狗陪护", 20)
                .sold(11L, 3L).sold(12L, 3L);
        Card card = h.compose().items().get(0);
        assertEquals(List.of("11", "12"), card.services().stream()
                .map(FeaturedStoreCardComposer.ServiceLine::serviceId).toList());
        // Primary category = the sold leader's; the tie leader is service 11.
        assertEquals("DOG_WALKING", card.categoryCode());
    }

    @Test
    void noSalesFallsBackToDictionaryOrderForPrimaryCategory() {
        Harness h = new Harness().candidates(100)
                .service(100, 11L, "PET_GROOMING", "宠物美容", 10)
                .service(100, 12L, "DOG_WALKING", "遛狗陪护", 20);
        Card card = h.compose().items().get(0);
        assertEquals("PET_GROOMING", card.categoryCode(),
                "no sale anywhere: the dictionary-order first service owns the primary category");
        assertEquals(List.of("11", "12"), card.services().stream()
                .map(FeaturedStoreCardComposer.ServiceLine::serviceId).toList());
    }

    // ---------------------------------------------------------------- paging

    @Test
    void pagingKeepsTheHonestTotalAndEmptyTailPage() {
        Harness h = new Harness().candidates(100, 200, 300);
        assertEquals(3, h.compose(1, 2).total());
        assertEquals(2, h.compose(1, 2).items().size());
        assertEquals(1, h.compose(2, 2).items().size());
        assertEquals(List.of("300"), h.compose(2, 2).items().stream().map(Card::storeId).toList());
        assertTrue(h.compose(3, 2).items().isEmpty(), "page beyond the end is an empty page");
        assertEquals(3, h.compose(3, 2).total(), "total stays the candidate-set size");
    }

    @Test
    void withoutCandidatesThePageIsEmpty() {
        assertTrue(new Harness().compose().items().isEmpty());
        assertEquals(0, new Harness().compose().total());
    }

    // ---------------------------------------------------------------- projection

    @Test
    void merchantPromotionProjectsAndAbsenceStaysNull() {
        Harness h = new Harness().candidates(100, 200).promo(100, 2, "全场美容5折", "持证美容师");
        Card promoted = h.compose().items().stream()
                .filter(card -> card.storeId().equals("100")).findFirst().orElseThrow();
        assertNotNull(promoted.promotion());
        assertEquals("MERCHANT_PROMOTION", promoted.promotion().source());
        assertEquals("全场美容5折", promoted.promotion().offerTitle());
        assertEquals("持证美容师", promoted.promotion().offerCopy());
        assertNull(promoted.promotion().offerTag(),
                "the merchant profile has no tag fact; none may be invented");
        assertNull(promoted.promotion().campaignId(),
                "the marketing public face has no batch campaign read: campaign stays null");
        Card plain = h.compose().items().stream()
                .filter(card -> card.storeId().equals("200")).findFirst().orElseThrow();
        assertNull(plain.promotion());
    }

    @Test
    void cardSurfaceCarriesNoPii() {
        Set<String> cardFields = components(Card.class);
        for (String forbidden : List.of("phone", "longitude", "latitude", "userId", "orderId")) {
            assertFalse(cardFields.stream().anyMatch(f -> f.toLowerCase().contains(forbidden)),
                    "the card must not carry " + forbidden);
        }
        assertEquals(
                Set.of("serviceId", "serviceName", "categoryCode", "categoryName", "salePrice",
                        "listPrice", "durationMinutes", "fulfillmentType", "soldCount"),
                components(FeaturedStoreCardComposer.ServiceLine.class));
        assertEquals(Set.of("score", "count", "available"),
                components(FeaturedStoreCardComposer.Rating.class));
        assertEquals(Set.of("source", "offerTag", "offerTitle", "offerCopy", "campaignId"),
                components(FeaturedStoreCardComposer.Promotion.class));
    }

    // ---------------------------------------------------------------- fail-closed

    @Test
    void avatarWithoutSignerFailsTheWholePageClosed() {
        Harness h = new Harness().candidates(100, 200).avatar(100, "710901").withoutPhotoSigning();
        ApiException failure = assertThrows(ApiException.class, h::compose);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code(),
                "a bound avatar without a real signer is 503, never an unsigned URL");
    }

    @Test
    void avatarSigningFailureFailsClosed() {
        Harness h = new Harness().candidates(100).avatar(100, "710901").signingFailure();
        ApiException failure = assertThrows(ApiException.class, h::compose);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    }

    @Test
    void missingOpenCityCatalogFailsClosed() {
        Harness h = new Harness().candidates(100).withoutCityCatalog();
        ApiException failure = assertThrows(ApiException.class, h::compose);
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    }

    @Test
    void closedExplicitCityIsRejectedAsInvalidArgument() {
        Harness h = new Harness().candidates(100);
        ApiException failure = assertThrows(ApiException.class, () -> h.compose("beijing"));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure.code());
    }

    // ---------------------------------------------------------------- harness

    private static Set<String> components(Class<?> recordType) {
        assertTrue(recordType.isRecord());
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
    }

    private static FeaturedStoreServiceItem newService(
            long storeId, long serviceId, String categoryCode, String categoryName, int sortNo) {
        return new FeaturedStoreServiceItem(
                Long.toString(serviceId), Long.toString(storeId), "1", "服务" + serviceId,
                Long.toString(1000 + sortNo), categoryCode, categoryName, sortNo,
                new BigDecimal("80.00"), new BigDecimal("100.00"), 60, "IN_STORE");
    }

    /** In-memory fakes for the five domain ports, plus the flags that drive degradations. */
    private static final class Harness {
        private final TreeMap<Long, Boolean> candidates = new TreeMap<>();
        private final Map<Long, List<FeaturedStoreServiceItem>> services = new TreeMap<>();
        private final Map<Long, FeaturedStoreFact> facts = new TreeMap<>();
        private final Map<String, ReviewPublicFactsApi.StoreRatingFact> ratings = new HashMap<>();
        private final Map<String, Long> monthly = new HashMap<>();
        private final Map<String, Long> sold = new HashMap<>();
        private boolean ratingFailure;
        private boolean salesFailure;
        private boolean withReviews = true;
        private boolean withSales = true;
        private boolean withPhotoSigning = true;
        private boolean signingFailure;
        private boolean withCityCatalog = true;

        Harness candidates(long... ids) {
            for (long id : ids) {
                candidates.put(id, true);
                facts.put(id, new FeaturedStoreFact(Long.toString(id), "1",
                        "门店" + id, "商家1", "测试地址" + id, "chengdu",
                        null, null, null, null, null));
            }
            return this;
        }

        Harness service(long storeId, long serviceId, String code, String name, int sortNo) {
            services.computeIfAbsent(storeId, ignored -> new ArrayList<>())
                    .add(newService(storeId, serviceId, code, name, sortNo));
            return this;
        }

        Harness rating(long storeId, long count, String avg) {
            ratings.put(Long.toString(storeId),
                    new ReviewPublicFactsApi.StoreRatingFact(Long.toString(storeId), count, avg));
            return this;
        }

        Harness monthly(long storeId, long orders) {
            monthly.put(Long.toString(storeId), orders);
            return this;
        }

        Harness sold(long serviceId, long orders) {
            sold.put(Long.toString(serviceId), orders);
            return this;
        }

        Harness promo(long storeId, int level, String title, String text) {
            FeaturedStoreFact before = facts.get(storeId);
            facts.put(storeId, new FeaturedStoreFact(before.storeId(), before.merchantId(),
                    before.storeName(), before.merchantName(), before.address(), before.cityCode(),
                    before.intro(), before.avatarAssetId(), level, title, text));
            return this;
        }

        Harness avatar(long storeId, String assetId) {
            FeaturedStoreFact before = facts.get(storeId);
            facts.put(storeId, new FeaturedStoreFact(before.storeId(), before.merchantId(),
                    before.storeName(), before.merchantName(), before.address(), before.cityCode(),
                    before.intro(), assetId, before.promoLevel(), before.promoTitle(),
                    before.promoText()));
            return this;
        }

        Harness ratingFailure() { this.ratingFailure = true; return this; }
        Harness salesFailure() { this.salesFailure = true; return this; }
        Harness withoutReviews() { this.withReviews = false; return this; }
        Harness withoutSales() { this.withSales = false; return this; }
        Harness withoutPhotoSigning() { this.withPhotoSigning = false; return this; }
        Harness signingFailure() { this.signingFailure = true; return this; }
        Harness withoutCityCatalog() { this.withCityCatalog = false; return this; }

        Page compose() { return compose(1, 10); }

        Page compose(int page, int pageSize) { return compose(null, page, pageSize); }

        Page compose(String cityCode) { return compose(cityCode, 1, 10); }

        Page compose(String cityCode, int page, int pageSize) {
            org.springframework.transaction.support.TransactionTemplate merchantReads =
                    new org.springframework.transaction.support.TransactionTemplate() {
                        @Override
                        public <T> T execute(
                                org.springframework.transaction.support.TransactionCallback<T> action) {
                            // Fakes carry no real DataSource; run the callback bare.
                            return action.doInTransaction(
                                    new org.springframework.transaction.support.SimpleTransactionStatus());
                        }
                    };
            FeaturedStoreCardComposer composer = new FeaturedStoreCardComposer(
                    withCityCatalog
                            ? provider(new MerchantApplicationCityCatalog(List.of(
                                    new City("chengdu", "成都"), new City("shanghai", "上海"))))
                            : provider(null),
                    (MerchantDisplayStoreIdsApi) query -> {
                        List<String> ids = new ArrayList<>();
                        candidates.keySet().forEach(id -> ids.add(Long.toString(id)));
                        return ids;
                    },
                    new FakeCatalog(),
                    new FakeFacts(),
                    withReviews ? provider(new FakeReviews()) : provider(null),
                    withSales ? provider(new FakeSales()) : provider(null),
                    withPhotoSigning ? provider(new FakeSigning()) : provider(null),
                    merchantReads);
            return composer.compose(cityCode, null, page, pageSize, CTX);
        }

        private final class FakeCatalog implements FeaturedStoreCatalogApi {
            @Override
            public List<String> listActiveServiceStoreIds(FeaturedActiveServiceStoresQuery query) {
                List<String> ids = new ArrayList<>();
                candidates.keySet().forEach(id -> ids.add(Long.toString(id)));
                return ids;
            }

            @Override
            public List<FeaturedStoreServiceItem> listStoreFeaturedServices(
                    FeaturedStoreServicesQuery query) {
                List<FeaturedStoreServiceItem> rows = new ArrayList<>();
                for (String storeId : query.storeIds()) {
                    rows.addAll(services.getOrDefault(Long.parseLong(storeId), List.of()));
                }
                return rows;
            }
        }

        private final class FakeFacts implements MerchantFeaturedStoreFactsApi {
            @Override
            public List<FeaturedStoreFact> listFeaturedStoreFacts(
                    MerchantFeaturedStoreFactsApi.FeaturedStoreFactsQuery query) {
                List<FeaturedStoreFact> rows = new ArrayList<>();
                for (String storeId : query.storeIds()) {
                    FeaturedStoreFact fact = facts.get(Long.parseLong(storeId));
                    if (fact != null) rows.add(fact);
                }
                return rows;
            }
        }

        private final class FakeReviews implements ReviewPublicFactsApi {
            @Override
            public List<StoreRatingFact> getStoreRatings(StoreRatingQuery query) {
                if (ratingFailure) throw new IllegalStateException("review facts source down");
                List<StoreRatingFact> rows = new ArrayList<>();
                for (String storeId : query.storeIds()) {
                    if (ratings.containsKey(storeId)) rows.add(ratings.get(storeId));
                }
                return rows;
            }
        }

        private final class FakeSales implements ServiceCatalogSalesFactsApi {
            @Override
            public List<com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySold>
                    storeMonthlySold(com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySoldQuery query) {
                if (salesFailure) throw new IllegalStateException("sales facts source down");
                List<com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySold> rows =
                        new ArrayList<>();
                for (String storeId : query.storeIds()) {
                    if (monthly.containsKey(storeId)) {
                        rows.add(new com.petplatform.order.api.query.ServiceCatalogSalesFactsApi
                                .StoreMonthlySold(storeId, monthly.get(storeId)));
                    }
                }
                return rows;
            }

            @Override
            public List<com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSold>
                    serviceSold(com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSoldQuery query) {
                if (salesFailure) throw new IllegalStateException("sales facts source down");
                List<com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSold> rows =
                        new ArrayList<>();
                for (String serviceId : query.serviceIds()) {
                    if (sold.containsKey(serviceId)) {
                        rows.add(new com.petplatform.order.api.query.ServiceCatalogSalesFactsApi
                                .ServiceSold(serviceId, sold.get(serviceId)));
                    }
                }
                return rows;
            }
        }

        private final class FakeSigning implements StorePhotoSigningApi {
            @Override
            public SignedStorePhoto signStorePhoto(String assetId, QueryContext context) {
                if (signingFailure) throw new IllegalStateException("signer down");
                return new SignedStorePhoto(
                        assetId, "https://cdn.example.invalid/signed/" + assetId,
                        Instant.parse("2026-11-30T12:00:00Z"));
            }
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() { return value; }

            @Override
            public T getObject(Object... args) { return value; }

            @Override
            public T getIfAvailable() { return value; }

            @Override
            public T getIfUnique() { return value; }
        };
    }
}
