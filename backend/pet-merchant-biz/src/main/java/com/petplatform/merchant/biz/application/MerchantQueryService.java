package com.petplatform.merchant.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantOrderEligibilityDTO;
import com.petplatform.merchant.api.dto.MerchantStaffDTO;
import com.petplatform.merchant.api.dto.MerchantStoreDTO;
import com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery;
import com.petplatform.merchant.api.query.MerchantStaffQuery;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantReadStore;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantEligibilityBaseEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStaffReadEntity;
import com.petplatform.merchant.biz.infrastructure.persistence.entity.MerchantStoreReadEntity;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Owner-scoped S2 read slice. QueryContext is accepted only after a trusted authentication adapter
 * has constructed it; request parameters themselves never establish resource ownership.
 */
public final class MerchantQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> MERCHANT_STATUSES =
            Set.of("APPLYING", "ACTIVE", "OFFLINE", "FROZEN", "CANCELED");
    private static final Set<String> STORE_STATUSES = Set.of("ACTIVE", "OFFLINE", "FROZEN");
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");

    private final MerchantReadStore store;
    private final MerchantEligibilityFactsReader eligibilityFacts;

    public MerchantQueryService(MerchantReadStore store, MerchantEligibilityFactsReader eligibilityFacts) {
        this.store = java.util.Objects.requireNonNull(store, "store is required");
        this.eligibilityFacts = java.util.Objects.requireNonNull(eligibilityFacts, "eligibilityFacts is required");
    }

    public MerchantStoreDTO getStore(StoreIdQuery query) {
        if (query == null) invalid("query is required");
        long storeId = targetId(query.storeId(), "storeId");
        long ownerUserId = ownerUserId(query.context());
        return store.read(mapper -> {
            MerchantStoreReadEntity row = mapper.selectOwnedStore(storeId, ownerUserId);
            if (row == null) notFound();
            validateStoreRow(row, ownerUserId);
            return new MerchantStoreDTO(
                    id(row.getMerchantId(), "merchant id"),
                    id(row.getStoreId(), "store id"),
                    text(row.getMerchantName(), 128, "merchant name"),
                    text(row.getStoreName(), 128, "store name"),
                    text(row.getAddress(), 255, "store address"),
                    coordinate(row.getLongitude(), MIN_LONGITUDE, MAX_LONGITUDE, "longitude"),
                    coordinate(row.getLatitude(), MIN_LATITUDE, MAX_LATITUDE, "latitude"),
                    maskPhone(row.getPhone()),
                    row.getMerchantStatus(),
                    row.getStoreStatus(),
                    version(row.getStoreVersion(), "store version")
            );
        });
    }

    public MerchantStaffDTO getStaff(MerchantStaffQuery query) {
        if (query == null) invalid("query is required");
        long merchantId = targetId(query.merchantId(), "merchantId");
        long storeId = targetId(query.storeId(), "storeId");
        long staffId = targetId(query.staffId(), "staffId");
        long ownerUserId = ownerUserId(query.context());
        return store.read(mapper -> {
            MerchantStaffReadEntity row = mapper.selectOwnedStaff(merchantId, storeId, staffId, ownerUserId);
            if (row == null) notFound();
            validateStaffRow(row, merchantId, storeId, staffId, ownerUserId);
            return new MerchantStaffDTO(
                    id(row.getMerchantId(), "merchant id"),
                    id(row.getStoreId(), "store id"),
                    id(row.getStaffId(), "staff id"),
                    text(row.getStaffName(), 64, "staff name"),
                    maskPhone(row.getPhone()),
                    row.getEmploymentStatus(),
                    row.getServiceEnabled() == 1,
                    version(row.getStaffVersion(), "staff version")
            );
        });
    }

    public MerchantOrderEligibilityDTO checkOrderEligibility(MerchantOrderEligibilityQuery query) {
        if (query == null) invalid("query is required");
        long merchantId = targetId(query.merchantId(), "merchantId");
        long storeId = targetId(query.storeId(), "storeId");
        long ownerUserId = ownerUserId(query.context());
        return store.read(mapper -> {
            MerchantEligibilityBaseEntity row =
                    mapper.selectOwnedEligibilityBase(merchantId, storeId, ownerUserId);
            if (row == null) notFound();
            validateEligibilityBase(row, merchantId, storeId, ownerUserId);
            MerchantOrderEligibilityPolicy.Decision decision = MerchantOrderEligibilityPolicy.evaluate(
                    row.getMerchantStatus(), row.getStoreStatus(), readEligibilityFacts(merchantId, storeId));
            return new MerchantOrderEligibilityDTO(
                    id(row.getMerchantId(), "merchant id"),
                    id(row.getStoreId(), "store id"),
                    decision.merchantEnabled(),
                    decision.storeEnabled(),
                    decision.acceptsNewOrders()
            );
        });
    }

    private MerchantEligibilityFactsReader.Facts readEligibilityFacts(long merchantId, long storeId) {
        try {
            return eligibilityFacts.read(merchantId, storeId);
        } catch (RuntimeException sourceFailure) {
            // Source-specific 4xx codes/messages are not caller errors and must not leak through.
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant eligibility facts unavailable");
        }
    }

    private static void validateStoreRow(MerchantStoreReadEntity row, long ownerUserId) {
        requirePositive(row.getMerchantId(), "merchant id");
        requirePositive(row.getStoreId(), "store id");
        requireEqual(row.getOwnerUserId(), ownerUserId, "merchant owner");
        requireKnown(MERCHANT_STATUSES, row.getMerchantStatus(), "merchant status");
        requireKnown(STORE_STATUSES, row.getStoreStatus(), "store status");
    }

    private static void validateStaffRow(
            MerchantStaffReadEntity row,
            long merchantId,
            long storeId,
            long staffId,
            long ownerUserId
    ) {
        requireEqual(row.getMerchantId(), merchantId, "merchant id");
        requireEqual(row.getStoreId(), storeId, "store id");
        requireEqual(row.getStaffId(), staffId, "staff id");
        requireEqual(row.getOwnerUserId(), ownerUserId, "merchant owner");
        requireKnown(MERCHANT_STATUSES, row.getMerchantStatus(), "merchant status");
        requireKnown(STORE_STATUSES, row.getStoreStatus(), "store status");
        requireKnown(EMPLOYMENT_STATUSES, row.getEmploymentStatus(), "employment status");
        if (row.getServiceEnabled() == null
                || (row.getServiceEnabled() != 0 && row.getServiceEnabled() != 1)) {
            unavailable("service enabled fact is invalid");
        }
        if ("INACTIVE".equals(row.getEmploymentStatus()) && row.getServiceEnabled() == 1) {
            unavailable("inactive staff cannot be service enabled");
        }
        if (row.getPhone() != null && !row.getPhone().matches("1[0-9]{10}")) {
            unavailable("staff phone fact is invalid");
        }
    }

    private static void validateEligibilityBase(
            MerchantEligibilityBaseEntity row,
            long merchantId,
            long storeId,
            long ownerUserId
    ) {
        requireEqual(row.getMerchantId(), merchantId, "merchant id");
        requireEqual(row.getStoreId(), storeId, "store id");
        requireEqual(row.getOwnerUserId(), ownerUserId, "merchant owner");
        requireKnown(MERCHANT_STATUSES, row.getMerchantStatus(), "merchant status");
        requireKnown(STORE_STATUSES, row.getStoreStatus(), "store status");
    }

    private static long ownerUserId(QueryContext context) {
        if (context == null || context.operatorType() == null
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "valid authenticated query context is required");
        }
        if (context.operatorType() != OperatorType.USER) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "this slice requires a merchant owner user");
        }
        try {
            return IDS.fromApi(context.operatorId());
        } catch (IllegalArgumentException invalidPrincipal) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authenticated user id is invalid");
        }
    }

    private static long targetId(String value, String field) {
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException failure) {
            invalid(field + " must be a positive Long decimal String without leading zeroes");
            return 0;
        }
    }

    private static String id(Long value, String field) {
        requirePositive(value, field);
        return IDS.toApi(value);
    }

    private static String version(Long value, String field) {
        if (value == null || value < 0) unavailable(field + " is invalid");
        return Long.toString(value);
    }

    // Package-private statics below: the STR-D6 display service reuses the identical approved
    // supplement-27 masking and projection validations so owner and display can never drift.
    static String text(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > maxCodePoints) {
            unavailable(field + " is invalid");
        }
        return value;
    }

    static String coordinate(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String field) {
        if (value == null) return null;
        if (value.scale() > 7 || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            unavailable(field + " is invalid");
        }
        return value.stripTrailingZeros().toPlainString();
    }

    static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.isBlank()) unavailable("phone is invalid");
        if (phone.length() <= 7) return "****";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static void requireKnown(Set<String> allowed, String value, String field) {
        if (value == null || !allowed.contains(value)) unavailable("unknown " + field);
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) unavailable(field + " is invalid");
    }

    private static void requireEqual(Long value, long expected, String field) {
        if (value == null || value != expected) unavailable(field + " relationship is inconsistent");
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
