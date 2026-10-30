package com.petplatform.boot.config;

import com.petplatform.boot.config.MerchantApplicationCityCatalog.City;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Contract 74 §3.4/§6 featured-card composition (B-CARD-01/02/03). Boot composes the four domain
 * read ports — merchant (visibility + card facts), service (candidates + services), review
 * (ratings) and order (sales) — without ever reading another module's tables, and without a query
 * per store or per service:
 *
 * <ol>
 *   <li>ONE service-domain grouped read yields the stores holding ACTIVE services (optionally in
 *       the exact enabled category);</li>
 *   <li>ONE merchant-domain read yields the C-visible store ids of the open-city set; the
 *       ascending intersection is the eligible candidate set;</li>
 *   <li>ONE batched rating read and ONE batched monthly-sold read (chunked by the order port's
 *       200-id cap) rank the whole candidate set {@code rating.score DESC NULLS LAST,
 *       monthlySold DESC NULLS LAST, storeId ASC};</li>
 *   <li>the page slice (1..20 stores) is hydrated with ONE batched merchant facts read and ONE
 *       window-partitioned service read plus batched per-service sold counts.</li>
 * </ol>
 *
 * <p>Degradation follows contract 74 §6: a rating fact failure or an unassembled review kernel
 * renders {@code rating.available=false} (never a fake 0); a sales fact failure or an unassembled
 * order face renders {@code monthlySold}/{@code soldCount}=null. Store/service/category read
 * failures and damaged stored facts fail the whole page closed (503), as does a bound avatar whose
 * signer is missing or failing (the cover-signing precedent) — never an unsigned URL. The
 * marketing campaign projection is NOT composed: the marketing public face only offers a
 * single-store campaign read, and looping it per card would be the forbidden N+1, so the card
 * promotion is the merchant promotion projection and {@code campaignId} stays null.
 */
public final class FeaturedStoreCardComposer {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    /** The order-domain sales port accepts at most 200 canonical ids per batch call. */
    private static final int FACT_CHUNK = 200;

    /** A store rating is only displayable from five included reviews on (contract 74 §6.1). */
    private static final int MIN_RATED_COUNT = 5;

    private final ObjectProvider<MerchantApplicationCityCatalog> catalogs;
    private final MerchantDisplayStoreIdsApi displayStoreIds;
    private final FeaturedStoreCatalogApi featuredCatalog;
    private final MerchantFeaturedStoreFactsApi merchantFacts;
    private final ObjectProvider<ReviewPublicFactsApi> reviews;
    private final ObjectProvider<ServiceCatalogSalesFactsApi> sales;
    private final ObjectProvider<StorePhotoSigningApi> photoSigning;
    /**
     * Read-only snapshot on the shared DataSource that both merchant faces join: their facts
     * readers require the caller's active merchant transaction (the same joining discipline as the
     * service domain's read snapshot). The review/order faces manage their own transactions and
     * are deliberately called outside this one.
     */
    private final org.springframework.transaction.support.TransactionTemplate merchantReads;

    public FeaturedStoreCardComposer(
            ObjectProvider<MerchantApplicationCityCatalog> catalogs,
            MerchantDisplayStoreIdsApi displayStoreIds,
            FeaturedStoreCatalogApi featuredCatalog,
            MerchantFeaturedStoreFactsApi merchantFacts,
            ObjectProvider<ReviewPublicFactsApi> reviews,
            ObjectProvider<ServiceCatalogSalesFactsApi> sales,
            ObjectProvider<StorePhotoSigningApi> photoSigning,
            org.springframework.transaction.support.TransactionTemplate merchantReads) {
        this.catalogs = catalogs;
        this.displayStoreIds = Objects.requireNonNull(displayStoreIds, "displayStoreIds is required");
        this.featuredCatalog = Objects.requireNonNull(featuredCatalog, "featuredCatalog is required");
        this.merchantFacts = Objects.requireNonNull(merchantFacts, "merchantFacts is required");
        this.reviews = reviews;
        this.sales = sales;
        this.photoSigning = photoSigning;
        this.merchantReads = Objects.requireNonNull(merchantReads, "merchantReads is required");
    }

    // ---------------------------------------------------------------- wire views

    /** One featured card; money keeps the DECIMAL(18,2) wire string, ids stay decimal strings. */
    public record Card(
            String storeId,
            String merchantId,
            String storeName,
            String merchantName,
            String cityCode,
            String address,
            String avatarUrl,
            String avatarUrlExpiresAt,
            String intro,
            String categoryCode,
            String categoryName,
            Rating rating,
            Integer monthlySold,
            List<String> tags,
            List<ServiceLine> services,
            Promotion promotion) {}

    /** score is null when the store has fewer than five included reviews or no rating fact. */
    public record Rating(String score, int count, boolean available) {}

    /** One of the up-to-two service lines; soldCount is null when the sales facts are unavailable. */
    public record ServiceLine(
            String serviceId,
            String serviceName,
            String categoryCode,
            String categoryName,
            BigDecimal salePrice,
            BigDecimal listPrice,
            int durationMinutes,
            String fulfillmentType,
            Integer soldCount) {}

    /** V1 source is always MERCHANT_PROMOTION; campaign composition waits for a batch face. */
    public record Promotion(
            String source, String offerTag, String offerTitle, String offerCopy, String campaignId) {}

    /** total is the honest eligible-store count of the whole candidate set, not the page slice. */
    public record Page(List<Card> items, int page, int pageSize, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    // ---------------------------------------------------------------- composition

    public Page compose(
            String cityCode,
            String categoryCode,
            int page,
            int pageSize,
            QueryContext context) {
        List<String> cityCodes = resolveCityCodes(cityCode);
        // Step 1+2: two grouped reads bound the candidate set; 400 category errors propagate.
        Set<Long> withServices = idSet(featuredCatalog.listActiveServiceStoreIds(
                new FeaturedActiveServiceStoresQuery(categoryCode, context)),
                "featured service store facts are invalid");
        Set<Long> visible = idSet(merchantReads.execute(status ->
                        displayStoreIds.listDisplayStoreIds(
                                new MerchantDisplayStoreIdsQuery(cityCodes, context))),
                "merchant display store facts are invalid");
        // Ascending numeric intersection: the eligible candidates for the honest total.
        List<Long> candidates = new ArrayList<>(withServices);
        candidates.removeIf(id -> !visible.contains(id));
        candidates.sort(Long::compare);
        if (candidates.isEmpty()) return new Page(List.of(), page, pageSize, 0);

        // Step 3: whole-set rating and monthly-sold facts, batched; failures degrade, never 503.
        // The facts reads only fill maps, so they run before the confirmed sort; the page slice's
        // id list is derived AFTER the sort so the wire order is the confirmed card order.
        ReviewPublicFactsApi reviewApi = reviews.getIfAvailable();
        Map<String, ReviewPublicFactsApi.StoreRatingFact> ratings = new HashMap<>();
        boolean ratingAvailable = reviewApi != null;
        if (reviewApi != null) {
            try {
                for (ReviewPublicFactsApi.StoreRatingFact fact : chunked(apiIds(candidates),
                        ids -> reviewApi.getStoreRatings(new ReviewPublicFactsApi.StoreRatingQuery(ids)))) {
                    ratings.put(fact.storeId(), fact);
                }
            } catch (RuntimeException degraded) {
                ratingAvailable = false; // Contract 74 §6.1: hide the rating, never a fake 0.
            }
        }
        ServiceCatalogSalesFactsApi salesApi = sales.getIfAvailable();
        Map<String, Long> monthlyByStore = new HashMap<>();
        boolean salesAvailable = salesApi != null;
        if (salesApi != null) {
            try {
                for (ServiceCatalogSalesFactsApi.StoreMonthlySold fact : chunked(apiIds(candidates),
                        ids -> salesApi.storeMonthlySold(new ServiceCatalogSalesFactsApi.StoreMonthlySoldQuery(ids)))) {
                    monthlyByStore.put(fact.storeId(), fact.orderCount());
                }
            } catch (RuntimeException degraded) {
                salesAvailable = false; // Contract 74 §6.2: null, never a fabricated count.
                monthlyByStore.clear();
            }
        }
        final boolean salesRanked = salesAvailable;
        final boolean ratingRanked = ratingAvailable;
        candidates.sort((a, b) -> compareCandidates(
                a, b, ratings, monthlyByStore, ratingRanked, salesRanked));
        long total = candidates.size();
        List<String> candidateIds = apiIds(candidates);

        // Step 4: hydrate only the page slice.
        long from = (page - 1L) * pageSize;
        if (from >= total) return new Page(List.of(), page, pageSize, total);
        List<String> slice = candidateIds.subList((int) from, (int) Math.min(from + pageSize, total));
        Map<String, FeaturedStoreFact> facts = new LinkedHashMap<>();
        for (FeaturedStoreFact fact : merchantReads.execute(status ->
                merchantFacts.listFeaturedStoreFacts(
                        new MerchantFeaturedStoreFactsApi.FeaturedStoreFactsQuery(slice, context)))) {
            facts.put(fact.storeId(), fact);
        }
        List<FeaturedStoreServiceItem> services = featuredCatalog.listStoreFeaturedServices(
                new FeaturedStoreServicesQuery(slice, context));
        Map<String, Long> soldByService = new HashMap<>();
        if (salesApi != null && salesAvailable && !services.isEmpty()) {
            List<String> serviceIds = services.stream().map(FeaturedStoreServiceItem::serviceId).toList();
            try {
                for (ServiceCatalogSalesFactsApi.ServiceSold fact : chunked(serviceIds,
                        ids -> salesApi.serviceSold(new ServiceCatalogSalesFactsApi.ServiceSoldQuery(ids)))) {
                    soldByService.put(fact.serviceId(), fact.orderCount());
                }
            } catch (RuntimeException degraded) {
                salesAvailable = false; // Contract 74 §6.2: per-service sold degrades to null.
                soldByService.clear();
            }
        }
        Map<String, List<FeaturedStoreServiceItem>> servicesByStore = new TreeMap<>();
        for (FeaturedStoreServiceItem service : services) {
            servicesByStore.computeIfAbsent(service.storeId(), ignored -> new ArrayList<>()).add(service);
        }
        List<Card> cards = new ArrayList<>(slice.size());
        for (String storeId : slice) {
            FeaturedStoreFact fact = facts.get(storeId);
            if (fact == null) continue; // Vanished or turned ineligible between reads: drop.
            cards.add(card(storeId, fact, servicesByStore.get(storeId),
                    ratings.get(storeId), monthlyByStore.get(storeId),
                    ratingAvailable, salesAvailable, soldByService));
        }
        return new Page(cards, page, pageSize, total);
    }

    // ---------------------------------------------------------------- card assembly

    private Card card(
            String storeId,
            FeaturedStoreFact fact,
            List<FeaturedStoreServiceItem> storeServices,
            ReviewPublicFactsApi.StoreRatingFact rating,
            Long monthlySold,
            boolean ratingAvailable,
            boolean salesAvailable,
            Map<String, Long> soldByService) {
        List<FeaturedStoreServiceItem> top2 = topServices(storeServices, soldByService);
        FeaturedStoreServiceItem primary = primaryCategory(storeServices, soldByService);
        String avatarUrl = null;
        String avatarExpiresAt = null;
        if (fact.avatarAssetId() != null) {
            StorePhotoSigningApi signing = photoSigning.getIfAvailable();
            if (signing == null) unavailable("store avatar signer is unavailable");
            StorePhotoSigningApi.SignedStorePhoto signed;
            try {
                signed = signing.signStorePhoto(fact.avatarAssetId(),
                        new QueryContext(
                                java.util.UUID.randomUUID().toString(),
                                com.petplatform.common.OperatorType.SYSTEM,
                                "featured-store-avatar"));
            } catch (ApiException known) {
                throw known;
            } catch (RuntimeException failure) {
                unavailable("store avatar signer is unavailable");
                return null;
            }
            if (signed == null || signed.signedUrl() == null || signed.signedUrl().isBlank()
                    || signed.expiresAt() == null) {
                unavailable("store avatar signature is invalid");
            }
            avatarUrl = signed.signedUrl();
            avatarExpiresAt = signed.expiresAt().toString();
        }
        return new Card(
                fact.storeId(),
                fact.merchantId(),
                fact.storeName(),
                fact.merchantName(),
                fact.cityCode(),
                fact.address(),
                avatarUrl,
                avatarExpiresAt,
                fact.intro(),
                primary == null ? null : primary.categoryCode(),
                primary == null ? null : primary.categoryName(),
                ratingView(rating, ratingAvailable),
                // An available sales aggregation answers 0 for a store without orders; null is
                // reserved for the degraded/unavailable case (contract 74 §6.2).
                salesAvailable ? sellable(monthlySold == null ? 0L : monthlySold) : null,
                primary == null ? List.of() : List.of(primary.categoryName()),
                top2.stream().map(service -> serviceLine(service, salesAvailable, soldByService)).toList(),
                promotion(fact));
    }

    /** The confirmed top-2 selection: soldCount DESC (absent counts as zero), then serviceId ASC. */
    static List<FeaturedStoreServiceItem> topServices(
            List<FeaturedStoreServiceItem> storeServices, Map<String, Long> soldByService) {
        if (storeServices == null || storeServices.isEmpty()) return List.of();
        List<FeaturedStoreServiceItem> ranked = new ArrayList<>(storeServices);
        ranked.sort((a, b) -> {
            long soldA = soldByService.getOrDefault(a.serviceId(), 0L);
            long soldB = soldByService.getOrDefault(b.serviceId(), 0L);
            if (soldA != soldB) return soldA > soldB ? -1 : 1;
            return Long.compare(serviceId(a.serviceId()), serviceId(b.serviceId()));
        });
        return List.copyOf(ranked.subList(0, Math.min(2, ranked.size())));
    }

    /**
     * Primary category (contract 74 §6.4): the sold leader's category — which is always the head
     * of the top-2 ranking — and when the store never sold anything, the first ACTIVE service in
     * dictionary order (category sortNo, then category id, then service id). Evaluated over the
     * store's full ACTIVE service list, never just the two displayed lines.
     */
    static FeaturedStoreServiceItem primaryCategory(
            List<FeaturedStoreServiceItem> storeServices, Map<String, Long> soldByService) {
        if (storeServices == null || storeServices.isEmpty()) return null;
        long maxSold = 0;
        for (FeaturedStoreServiceItem service : storeServices) {
            maxSold = Math.max(maxSold, soldByService.getOrDefault(service.serviceId(), 0L));
        }
        if (maxSold > 0) return topServices(storeServices, soldByService).get(0);
        FeaturedStoreServiceItem first = storeServices.get(0);
        for (FeaturedStoreServiceItem service : storeServices) {
            if (dictionaryOrder(service, first) < 0) first = service;
        }
        return first;
    }

    private static int dictionaryOrder(FeaturedStoreServiceItem a, FeaturedStoreServiceItem b) {
        int comparison = Integer.compare(
                a.categorySortNo() == null ? 0 : a.categorySortNo(),
                b.categorySortNo() == null ? 0 : b.categorySortNo());
        if (comparison != 0) return comparison;
        comparison = Long.compare(serviceId(a.categoryId()), serviceId(b.categoryId()));
        if (comparison != 0) return comparison;
        return Long.compare(serviceId(a.serviceId()), serviceId(b.serviceId()));
    }

    private static Rating ratingView(
            ReviewPublicFactsApi.StoreRatingFact rating, boolean ratingAvailable) {
        if (!ratingAvailable || rating == null) {
            // Aggregation unavailable / review kernel not assembled: hide, never a fake 0.
            return new Rating(null, 0, false);
        }
        if (rating.reviewCount() < 0) unavailable("store rating facts are invalid");
        String score = null;
        if (rating.reviewCount() >= MIN_RATED_COUNT && rating.avgScore() != null) {
            // Validate the one-decimal wire shape by parsing; the value itself is the fact's.
            score = decimal(rating.avgScore(), "store rating facts are invalid").toPlainString();
        }
        return new Rating(score, (int) Math.min(rating.reviewCount(), Integer.MAX_VALUE), true);
    }

    private static ServiceLine serviceLine(
            FeaturedStoreServiceItem service, boolean salesAvailable, Map<String, Long> soldByService) {
        Integer soldCount = salesAvailable ? sellable(soldByService.getOrDefault(service.serviceId(), 0L)) : null;
        return new ServiceLine(
                service.serviceId(),
                service.serviceName(),
                service.categoryCode(),
                service.categoryName(),
                service.salePrice(),
                service.listPrice(),
                service.durationMinutes(),
                service.fulfillmentType(),
                soldCount);
    }

    /**
     * Merchant promotion projection (contract 74 §6.3): the merchant promotion fields are the only
     * V1 source — the marketing public face has no batch campaign read, and looping its
     * single-store read per card is the forbidden N+1, so the campaign source (and campaignId)
     * stays null and the gap is disclosed.
     */
    private static Promotion promotion(FeaturedStoreFact fact) {
        if (fact.promoLevel() == null && fact.promoTitle() == null && fact.promoText() == null) {
            return null;
        }
        return new Promotion("MERCHANT_PROMOTION", null, fact.promoTitle(), fact.promoText(), null);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * STR-D3 city range: the server-controlled open-city directory decides. An explicit code must
     * be lexically valid and open (else 400); omission means the whole current open set; a
     * missing/empty catalog fails closed as 503.
     */
    private List<String> resolveCityCodes(String cityCode) {
        MerchantApplicationCityCatalog catalog = catalogs.getIfAvailable();
        if (catalog == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "开放城市目录暂时不可用");
        }
        List<City> open = catalog.list(); // 503 when not configured
        if (cityCode != null) {
            return open.stream()
                    .map(City::code)
                    .filter(cityCode::equals)
                    .findFirst()
                    .map(List::of)
                    .orElseThrow(() -> new ApiException(
                            CommonApiCodes.INVALID_ARGUMENT, "cityCode is invalid"));
        }
        return open.stream().map(City::code).toList();
    }

    /**
     * The confirmed card order: rating.score DESC (null last), monthlySold DESC (null last),
     * storeId ASC. A malformed score inside an available fact set is damaged data and fails the
     * page closed rather than silently reordering.
     */
    private static int compareCandidates(
            long a,
            long b,
            Map<String, ReviewPublicFactsApi.StoreRatingFact> ratings,
            Map<String, Long> monthlyByStore,
            boolean ratingAvailable,
            boolean salesAvailable) {
        BigDecimal scoreA = score(a, ratings, ratingAvailable);
        BigDecimal scoreB = score(b, ratings, ratingAvailable);
        int comparison = compareNullsLastDesc(scoreA, scoreB);
        if (comparison != 0) return comparison;
        Long soldA = salesAvailable ? monthlyByStore.get(IDS.toApi(a)) : null;
        Long soldB = salesAvailable ? monthlyByStore.get(IDS.toApi(b)) : null;
        comparison = compareNullsLastDesc(soldA, soldB);
        if (comparison != 0) return comparison;
        return Long.compare(a, b);
    }

    /** Displayable score: the one-decimal average from MIN_RATED_COUNT reviews on, else null. */
    private static BigDecimal score(
            long storeId,
            Map<String, ReviewPublicFactsApi.StoreRatingFact> ratings,
            boolean ratingAvailable) {
        if (!ratingAvailable) return null;
        ReviewPublicFactsApi.StoreRatingFact rating = ratings.get(IDS.toApi(storeId));
        if (rating == null) return null;
        if (rating.reviewCount() < MIN_RATED_COUNT || rating.avgScore() == null) return null;
        return decimal(rating.avgScore(), "store rating facts are invalid");
    }

    static <T extends Comparable<T>> int compareNullsLastDesc(T a, T b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    private static Set<Long> idSet(List<String> ids, String damageMessage) {
        if (ids == null) unavailable(damageMessage);
        Set<Long> values = new LinkedHashSet<>();
        for (String id : ids) {
            try {
                long value = IDS.fromApi(id);
                if (value <= 0) throw new NumberFormatException(id);
                values.add(value);
            } catch (RuntimeException malformed) {
                unavailable(damageMessage);
            }
        }
        return values;
    }

    private static List<String> apiIds(List<Long> ids) {
        List<String> values = new ArrayList<>(ids.size());
        for (long id : ids) values.add(IDS.toApi(id));
        return values;
    }

    /** Fixed 200-id batches: chunked batch calls, never a call per id. */
    private static <T> List<T> chunked(List<String> ids, Function<List<String>, List<T>> call) {
        List<T> results = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += FACT_CHUNK) {
            List<T> part =
                    call.apply(ids.subList(from, Math.min(ids.size(), from + FACT_CHUNK)));
            if (part != null) results.addAll(part);
        }
        return results;
    }

    private static long serviceId(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            unavailable("featured service facts are invalid");
            return 0;
        }
    }

    private static Integer sellable(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) unavailable("sales facts are invalid");
        return (int) value;
    }

    private static BigDecimal decimal(String value, String damageMessage) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException malformed) {
            unavailable(damageMessage);
            return null;
        }
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
