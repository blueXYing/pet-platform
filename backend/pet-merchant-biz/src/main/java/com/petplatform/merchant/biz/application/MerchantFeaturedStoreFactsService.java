package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi.FeaturedStoreFact;
import com.petplatform.merchant.api.query.MerchantFeaturedStoreFactsApi.FeaturedStoreFactsQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantFeaturedStoreFactRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantFeaturedStoreFactsMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Contract 74 §3.4 featured-card store facts (B-CARD-02, MerchantDisplayStoreIdsApi pattern):
 * batched display facts for one card page's stores. Visibility is re-proven with the same
 * approved three-condition conjunction and compat-city integrity rule as the STR-D6 display page,
 * evaluated once per distinct merchant — never per store. Statements execute through a joining
 * template so a caller's active Spring transaction observes merchant, store, profile and signing
 * facts in one repeatable-read snapshot.
 *
 * <p>Error contract (same split as SVC-D5, never conflated): a positively missing or ineligible
 * store is simply absent from the answer (the composing read drops the row); read failures,
 * facts-source failures, unknown status values and profile-compat integrity violations are
 * DEPENDENCY_UNAVAILABLE (whole-page 503). A blank optional profile text is an unset value (null),
 * never a facade.
 */
public final class MerchantFeaturedStoreFactsService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    /** Same lexical shape as the open-city catalog entries (STR-D3 integrity rule). */
    private static final Pattern CITY_CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final SqlSessionTemplate template;
    private final MerchantEligibilityFactsReader eligibilityFacts;

    public MerchantFeaturedStoreFactsService(
            SqlSessionTemplate template, MerchantEligibilityFactsReader eligibilityFacts) {
        this.template = Objects.requireNonNull(template, "template is required");
        this.eligibilityFacts = Objects.requireNonNull(eligibilityFacts, "eligibilityFacts is required");
    }

    public List<FeaturedStoreFact> listFeaturedStoreFacts(FeaturedStoreFactsQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        if (query.storeIds().size() > FeaturedStoreFactsQuery.MAX_STORE_IDS) {
            invalid("storeIds is invalid");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String storeId : query.storeIds()) {
            long value = targetId(storeId, "storeId");
            ids.add(value);
        }
        if (ids.isEmpty()) return List.of();
        try {
            MerchantFeaturedStoreFactsMapper mapper =
                    template.getMapper(MerchantFeaturedStoreFactsMapper.class);
            List<MerchantFeaturedStoreFactRowEntity> rows =
                    mapper.selectFeaturedStoreFactRows(List.copyOf(ids));
            Map<Long, MerchantEligibilityFactsReader.Facts> factsByMerchant = new HashMap<>();
            List<FeaturedStoreFact> facts = new ArrayList<>(rows.size());
            for (MerchantFeaturedStoreFactRowEntity row : rows) {
                requirePositive(row.getMerchantId(), "merchant id");
                requirePositive(row.getStoreId(), "store id");
                MerchantEligibilityFactsReader.Facts merchantFacts =
                        factsByMerchant.computeIfAbsent(
                                row.getMerchantId(),
                                merchantId -> readEligibilityFacts(merchantId, row.getStoreId()));
                MerchantOrderEligibilityPolicy.Decision decision = MerchantOrderEligibilityPolicy
                        .evaluate(row.getMerchantStatus(), row.getStoreStatus(), merchantFacts);
                if (!decision.merchantEnabled() || !decision.storeEnabled()
                        || !decision.acceptsNewOrders()) {
                    continue; // Confirmed ineligible (or vanished since the page was computed).
                }
                facts.add(project(row));
            }
            return List.copyOf(facts);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant featured store facts unavailable");
        }
    }

    /** Approved display projection validations, aligned with the STR-D6 store display face. */
    private static FeaturedStoreFact project(MerchantFeaturedStoreFactRowEntity row) {
        String city = row.getCityCode();
        if (city == null || city.isBlank()) {
            unavailable("merchant profile compat row is missing");
        }
        if (!CITY_CODE.matcher(city).matches()) {
            unavailable("merchant profile city fact is invalid");
        }
        return new FeaturedStoreFact(
                IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getMerchantId()),
                MerchantQueryService.text(row.getStoreName(), 128, "store name"),
                MerchantQueryService.text(row.getMerchantName(), 128, "merchant name"),
                MerchantQueryService.text(row.getAddress(), 255, "store address"),
                city,
                optionalText(row.getIntro(), 500, "store intro"),
                optionalAvatarId(row.getAvatarAssetId()),
                promoLevel(row.getPromoLevel()),
                optionalText(row.getPromoTitle(), 100, "promoTitle"),
                optionalText(row.getPromoText(), 100, "promoText"));
    }

    /** Optional profile text: null stays null, blank is an unset value, overflow is damaged facts. */
    private static String optionalText(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank()) return null;
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            unavailable("store " + field + " is invalid");
        }
        return value;
    }

    private static String optionalAvatarId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value.trim());
            if (id <= 0) unavailable("store avatar fact is invalid");
            return IDS.toApi(id);
        } catch (NumberFormatException malformed) {
            unavailable("store avatar fact is invalid");
            return null;
        }
    }

    private static Integer promoLevel(Integer value) {
        if (value == null) return null;
        if (value < 1 || value > 9) unavailable("store promotion level is invalid");
        return value;
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

    private static long targetId(String value, String field) {
        if (value == null || value.isBlank()) invalid(field + " is required");
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) invalid(field + " is invalid");
            return id;
        } catch (RuntimeException malformed) {
            invalid(field + " is invalid");
            throw malformed;
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) unavailable(field + " fact is invalid");
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
