package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantDisplayEligibilityDTO;
import com.petplatform.merchant.api.query.MerchantDisplayEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantDisplayStoreIdsQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantEligibilityBaseEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreDisplayRowEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.mapper.MerchantReadMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * SVC-D5 consumer-display eligibility. Same policy and same fail-closed behaviour as the
 * owner-facing {@link MerchantQueryService#checkOrderEligibility}, but with no ownership
 * precondition: the caller identity never filters rows and never grants authority. A positively
 * missing merchant/store pair is NOT_FOUND (caller hides); read failures and unknown facts are
 * DEPENDENCY_UNAVAILABLE (caller surfaces 503). The two are never conflated.
 *
 * <p>Statements execute through a joining template so a caller's active Spring transaction (for
 * example the service-domain read snapshot) observes merchant, store, application and signing
 * facts in one repeatable-read snapshot; without an active transaction each read stands alone.
 */
public final class MerchantDisplayEligibilityService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    /** Same lexical shape as the open-city catalog entries (STR-D3 integrity rule). */
    private static final Pattern CITY_CODE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final SqlSessionTemplate template;
    private final MerchantEligibilityFactsReader eligibilityFacts;

    public MerchantDisplayEligibilityService(
            SqlSessionTemplate template, MerchantEligibilityFactsReader eligibilityFacts) {
        this.template = Objects.requireNonNull(template, "template is required");
        this.eligibilityFacts = Objects.requireNonNull(eligibilityFacts, "eligibilityFacts is required");
    }

    public MerchantDisplayEligibilityDTO check(MerchantDisplayEligibilityQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        long merchantId = targetId(query.merchantId(), "merchantId");
        long storeId = targetId(query.storeId(), "storeId");
        try {
            MerchantReadMapper mapper = template.getMapper(MerchantReadMapper.class);
            MerchantEligibilityBaseEntity row =
                    mapper.selectDisplayEligibilityBase(merchantId, storeId);
            if (row == null) notFound();
            requirePositive(row.getMerchantId(), "merchant id");
            requirePositive(row.getStoreId(), "store id");
            MerchantOrderEligibilityPolicy.Decision decision = MerchantOrderEligibilityPolicy.evaluate(
                    row.getMerchantStatus(), row.getStoreStatus(), readEligibilityFacts(merchantId, storeId));
            return new MerchantDisplayEligibilityDTO(
                    IDS.toApi(row.getMerchantId()),
                    IDS.toApi(row.getStoreId()),
                    decision.merchantEnabled(),
                    decision.storeEnabled(),
                    decision.acceptsNewOrders()
            );
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant display eligibility unavailable");
        }
    }

    /**
     * Contract 74 §3.1 supplement: display-scope store ids for the requested open cities, in
     * ascending numeric order. One candidate read plus one facts read per distinct merchant —
     * never a query per category or per store. The three-condition policy and the compat-city
     * integrity rule are identical to the STR-D6 display page; a facts failure fails closed as
     * DEPENDENCY_UNAVAILABLE instead of silently shrinking the answer.
     */
    public List<String> listDisplayStoreIds(MerchantDisplayStoreIdsQuery query) {
        if (query == null) invalid("query is required");
        if (query.context() == null) invalid("query context is required");
        Set<String> cities = Set.copyOf(query.cityCodes());
        if (cities.isEmpty()) return List.of();
        try {
            MerchantReadMapper mapper = template.getMapper(MerchantReadMapper.class);
            List<MerchantStoreDisplayRowEntity> rows = mapper.selectDisplayStoreCandidatesPlain();
            Map<Long, MerchantEligibilityFactsReader.Facts> factsByMerchant = new HashMap<>();
            Set<Long> visible = new TreeSet<>();
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
                if (cities.contains(cityFact(row))) visible.add(row.getStoreId());
            }
            List<String> ids = new ArrayList<>(visible.size());
            for (Long storeId : visible) ids.add(IDS.toApi(storeId));
            return List.copyOf(ids);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant display store facts unavailable");
        }
    }

    /**
     * City fact with the STR-D3 integrity rule: an eligible merchant must own a well-formed compat
     * row — a missing row or a malformed code is corrupted data (503), never a silent hide.
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

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "merchant resource not found");
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
