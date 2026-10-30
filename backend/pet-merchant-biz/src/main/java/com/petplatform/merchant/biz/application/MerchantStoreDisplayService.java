package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreDisplayRowEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * STR-D6 consumer-display store projection (the fifth merchant query). Same eligibility policy and
 * same fail-closed behaviour as the owner-facing reads, but with no ownership precondition: the
 * caller identity never filters rows and never grants authority, and anonymous browsing passes a
 * linkage-only context.
 *
 * <p>Visibility is the approved three-condition conjunction. Every statement executes inside one
 * read-only repeatable-read snapshot; the page evaluates the policy once per distinct merchant's
 * facts (whole-page eligibility) and never degrades a facts failure into an empty page. A
 * positively missing or ineligible store is NOT_FOUND for {@code getDisplayStore} and plain
 * exclusion for the page; read failures, unknown statuses and compat integrity violations
 * (missing row or malformed city code on an otherwise eligible merchant) are
 * DEPENDENCY_UNAVAILABLE and must surface as a whole-page 503 — the two families are never
 * conflated.
 */
public final class MerchantStoreDisplayService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    /** Same lexical shape as the open-city catalog entries. */
    private static final Pattern CITY_CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final MerchantReadStore store;
    private final MerchantEligibilityFactsReader eligibilityFacts;
    private final boolean promotionProjection;

    public MerchantStoreDisplayService(
            MerchantReadStore store, MerchantEligibilityFactsReader eligibilityFacts) {
        this(store, eligibilityFacts, false);
    }

    public MerchantStoreDisplayService(MerchantReadStore store,
            MerchantEligibilityFactsReader eligibilityFacts, boolean promotionProjection) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.eligibilityFacts = Objects.requireNonNull(eligibilityFacts, "eligibilityFacts is required");
        this.promotionProjection = promotionProjection;
    }

    /** C store list: one snapshot, one facts read per distinct merchant, honest visible total. */
    public MerchantStoreDisplayPageDTO page(MerchantStoreDisplayPageQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        int page = query.page();
        int pageSize = query.pageSize();
        if (page < 1 || page > 10_000) invalid("page is invalid");
        if (pageSize < 1 || pageSize > 50) invalid("pageSize is invalid");
        Set<String> cities = Set.copyOf(query.cityCodes());
        // Same bound as the HTTP adapter (HTTP10 §3.3.2): 1..30 code points after trimming.
        String keyword = query.keyword() == null ? null : query.keyword().strip();
        if (keyword != null && (keyword.isEmpty()
                || keyword.codePointCount(0, keyword.length()) > 30)) invalid("keyword is invalid");
        return store.read(mapper -> {
            List<MerchantStoreDisplayRowEntity> rows = promotionProjection
                    ? mapper.selectDisplayStoreCandidates()
                    : mapper.selectDisplayStoreCandidatesPlain();
            Map<Long, MerchantEligibilityFactsReader.Facts> factsByMerchant = new HashMap<>();
            List<MerchantStoreDisplayRowEntity> visible = new ArrayList<>();
            for (MerchantStoreDisplayRowEntity row : rows) {
                requirePositive(row.getMerchantId(), "merchant id");
                requirePositive(row.getStoreId(), "store id");
                MerchantEligibilityFactsReader.Facts facts = factsByMerchant.computeIfAbsent(
                        row.getMerchantId(),
                        merchantId -> readEligibilityFacts(merchantId, row.getStoreId()));
                MerchantOrderEligibilityPolicy.Decision decision = MerchantOrderEligibilityPolicy
                        .evaluate(row.getMerchantStatus(), row.getStoreStatus(), facts);
                if (!decision.merchantEnabled() || !decision.storeEnabled()
                        || !decision.acceptsNewOrders()) {
                    continue; // Confirmed ineligible: hidden, never an error.
                }
                String city = cityFact(row);
                if (!cities.contains(city)) continue;
                // Contract 74 §3.2 category filter: resolved by the service domain, composed
                // here by AND with the store catalog's own visibility/city/keyword filters.
                if (query.storeIds() != null && !query.storeIds().contains(row.getStoreId())) {
                    continue;
                }
                // HTTP10 §3.3.2 search supplement (2026-10-19): case-insensitive substring
                // match on the two catalog names only; a blank keyword never filters.
                if (keyword != null && !keyword.isEmpty()
                        && !containsIgnoreCase(row.getStoreName(), keyword)
                        && !containsIgnoreCase(row.getMerchantName(), keyword)) {
                    continue;
                }
                boolean promoted = row.getPromoLevel() != null
                        || (row.getPromoTitle() != null && !row.getPromoTitle().isBlank())
                        || (row.getPromoText() != null && !row.getPromoText().isBlank());
                if (query.promotedOnly() && !promoted) continue;
                visible.add(row);
            }
            if (query.promotedOnly()) {
                visible.sort(java.util.Comparator
                        .comparingInt((MerchantStoreDisplayRowEntity row) ->
                                row.getPromoLevel() == null ? 99 : row.getPromoLevel())
                        .thenComparing(MerchantStoreDisplayRowEntity::getStoreId));
            }
            long total = visible.size();
            List<MerchantStoreDisplayDTO> items = new ArrayList<>(Math.min(pageSize, visible.size()));
            long from = (page - 1L) * pageSize;
            for (long index = from; index < from + pageSize && index < total; index++) {
                items.add(project(visible.get((int) index)));
            }
            return new MerchantStoreDisplayPageDTO(items, page, pageSize, total);
        });
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    /** C store detail: NOT_FOUND unless the three-condition conjunction holds; 404-indistinguishable. */
    public MerchantStoreDisplayDTO get(MerchantStoreDisplayQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        long storeId = targetId(query.storeId());
        return store.read(mapper -> {
            MerchantStoreDisplayRowEntity row = promotionProjection
                    ? mapper.selectDisplayStore(storeId)
                    : mapper.selectDisplayStorePlain(storeId);
            if (row == null) notFound();
            requirePositive(row.getMerchantId(), "merchant id");
            requirePositive(row.getStoreId(), "store id");
            MerchantOrderEligibilityPolicy.Decision decision = MerchantOrderEligibilityPolicy
                    .evaluate(row.getMerchantStatus(), row.getStoreStatus(),
                            readEligibilityFacts(row.getMerchantId(), storeId));
            if (!decision.merchantEnabled() || !decision.storeEnabled()
                    || !decision.acceptsNewOrders()) {
                notFound();
            }
            cityFact(row); // Integrity check also applies to the detail projection.
            return project(row);
        });
    }

    /**
     * City fact with the STR-D3 integrity rule: an eligible merchant must own a well-formed
     * compat row — a missing row or a malformed code is corrupted data (503), never a silent
     * hide or a bucket-0 assignment.
     */
    private static String cityFact(MerchantStoreDisplayRowEntity row) {
        String city = row.getCityCode();
        if (city == null || city.isBlank()) {
            unavailable("merchant profile compat row is missing");
        }
        if (!CITY_CODE.matcher(city).matches()) {
            unavailable("merchant profile city fact is invalid");
        }
        return city;
    }

    private MerchantEligibilityFactsReader.Facts readEligibilityFacts(long merchantId, long storeId) {
        try {
            return eligibilityFacts.read(merchantId, storeId);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException sourceFailure) {
            // Source-specific 4xx codes/messages are not caller errors and must not leak through.
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant eligibility facts unavailable");
        }
    }

    /** Approved MerchantStoreDTO projection validations, reused verbatim from the owner slice. */
    private static MerchantStoreDisplayDTO project(MerchantStoreDisplayRowEntity row) {
        return new MerchantStoreDisplayDTO(
                id(row.getMerchantId(), "merchant id"),
                id(row.getStoreId(), "store id"),
                MerchantQueryService.text(row.getMerchantName(), 128, "merchant name"),
                MerchantQueryService.text(row.getStoreName(), 128, "store name"),
                MerchantQueryService.text(row.getAddress(), 255, "store address"),
                MerchantQueryService.coordinate(row.getLongitude(), MIN_LONGITUDE, MAX_LONGITUDE, "longitude"),
                MerchantQueryService.coordinate(row.getLatitude(), MIN_LATITUDE, MAX_LATITUDE, "latitude"),
                MerchantQueryService.maskPhone(row.getPhone()),
                cityFact(row),
                promoLevel(row.getPromoLevel()),
                promoText(row.getPromoTitle(), "promoTitle"),
                promoText(row.getPromoText(), "promoText"));
    }

    private static Integer promoLevel(Integer value) {
        if (value == null) return null;
        if (value < 1 || value > 9) unavailable("store promotion level is invalid");
        return value;
    }

    private static String promoText(String value, String field) {
        if (value == null) return null;
        if (value.isBlank()) return null;
        if (value.codePointCount(0, value.length()) > 100) {
            unavailable("store " + field + " is invalid");
        }
        return value;
    }

    private static long targetId(String value) {
        if (value == null || value.isBlank()) invalid("storeId is required");
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) invalid("storeId is invalid");
            return id;
        } catch (RuntimeException malformed) {
            invalid("storeId is invalid");
            throw malformed;
        }
    }

    private static String id(Long value, String field) {
        requirePositive(value, field);
        return IDS.toApi(value);
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) unavailable(field + " is invalid");
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "store resource not found");
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
