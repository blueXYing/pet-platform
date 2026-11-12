package com.petplatform.verification.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.api.query.MerchantStaffNamingApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.StoreVerificationListQuery;
import com.petplatform.verification.api.query.MerchantVerificationReadApi.VerificationRow;
import com.petplatform.verification.biz.infrastructure.persistence.MerchantVerificationReadStore;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.MerchantVerificationReadMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merchant verification-record read model (CCR-M-VERIFY-RECORD-001, approved 2026-11-12;
 * Figma 12:7426). Read-only and OWNER-only: every query re-proves the schedule store guard and
 * the OWNER coordinate through {@link MerchantOrderAuthorityApi#requireOwnerRead} exactly like
 * the contract-10 §4.1 order list, so a foreign, absent or non-owned store answers the same
 * COMMON_FORBIDDEN (anti-enumeration) and cross-store rows never appear. The fixed sort is
 * verified_at DESC then id DESC. The operator renders per the CCR §9 ruling only — STAFF rows
 * batch-resolve this store's merchant_staff display names, OWNER rows carry the fixed
 * 「店长」 label — and the raw operatorId never leaves this kernel. Credential cipher columns
 * are never selected.
 */
public final class MerchantVerificationReadService {

    /** CCR §3: same pagination family as the contract-63 face (OpenAPI 11 bounds). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    /** CCR §3: verified range span cap, same query discipline as the contract-63 face. */
    static final int MAX_RANGE_DAYS = 366;
    static final String OWNER_LABEL = "店长";

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final MerchantVerificationReadStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final MerchantStaffNamingApi naming;

    public MerchantVerificationReadService(
            MerchantVerificationReadStore store,
            ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority,
            MerchantStaffNamingApi naming) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.naming = Objects.requireNonNull(naming, "naming is required");
    }

    public PageResult<VerificationRow> listStoreVerifications(StoreVerificationListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = subject(query.context());
        coordinate(query.merchantId()); // shape validation before the guard runs
        long storeKey = coordinate(query.storeId());
        Long orderId = optionalId(query.orderId());
        Range range = range(query.verifiedFrom(), query.verifiedTo());
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        // Same store guard + OWNER re-proof discipline as the contract-10 §4.1 order list: the
        // schedule guard acquire needs this module's writable READ_COMMITTED transaction, and
        // the coordinate is validated under the stable SCH row lock on every read.
        Rows page0 = store.read(mapper -> {
            guard.acquire(List.of(query.storeId()),
                    new QueryContext(context.traceId(), OperatorType.SYSTEM, null));
            authority.requireOwnerRead(query.merchantId(), query.storeId(), context);
            long total = mapper.countStoreVerifications(storeKey, orderId, range.from(), range.to());
            if (total == 0) return new Rows(total, List.of());
            return new Rows(total, mapper.selectStoreVerificationPage(storeKey, orderId,
                    range.from(), range.to(), pageSize, (long) (page - 1) * pageSize));
        });
        if (page0.total() == 0) {
            return new PageResult<>(List.of(), page0.total(), page, pageSize);
        }
        return new PageResult<>(
                displayNames(page0.rows(), query.storeId(), context), page0.total(), page, pageSize);
    }

    /** Count plus the page rows read under the guard-held transaction. */
    private record Rows(long total, List<MerchantVerificationReadMapper.VerificationRow> rows) {}

    /**
     * CCR §9 display pair: OWNER rows read the fixed 「店长」 label; STAFF rows batch-resolve
     * this store's merchant_staff real names (one call for the page's distinct staff ids). A
     * missing profile row degrades to null — never fabricated, never a cross-store name.
     */
    private List<VerificationRow> displayNames(
            List<MerchantVerificationReadMapper.VerificationRow> rows,
            String storeId, QueryContext context) {
        if (rows.isEmpty()) return List.of();
        Set<String> staffIds = new LinkedHashSet<>();
        for (MerchantVerificationReadMapper.VerificationRow row : rows) {
            if ("STAFF".equals(row.getMembershipKind()) && row.getOperatorStaffId() != null) {
                staffIds.add(IDS.toApi(row.getOperatorStaffId()));
            }
        }
        Map<String, String> names = staffIds.isEmpty()
                ? Map.of()
                : naming.staffNames(storeId, staffIds, context);
        List<VerificationRow> items = new ArrayList<>(rows.size());
        for (MerchantVerificationReadMapper.VerificationRow row : rows) {
            boolean owner = "OWNER".equals(row.getMembershipKind());
            String staffApiId = row.getOperatorStaffId() == null
                    ? null : IDS.toApi(row.getOperatorStaffId());
            items.add(new VerificationRow(
                    IDS.toApi(row.getId()), IDS.toApi(row.getOrderId()), IDS.toApi(row.getStoreId()),
                    row.getOperatorType(), row.getMembershipKind(), staffApiId,
                    owner ? OWNER_LABEL : names.get(staffApiId),
                    row.getVerifyMethod(), instant(row.getVerifiedAt())));
        }
        return List.copyOf(items);
    }

    private static QueryContext subject(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        return context;
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    private static Long optionalId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "orderId is invalid");
        }
    }

    record Range(LocalDateTime from, LocalDateTime to) {}

    /** Contract-63 query discipline: paired bounds, ordered, span ≤366 days. */
    private static Range range(OffsetDateTime verifiedFrom, OffsetDateTime verifiedTo) {
        LocalDateTime from = utc(verifiedFrom, "verifiedFrom");
        LocalDateTime to = utc(verifiedTo, "verifiedTo");
        if ((from == null) != (to == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verified range must be paired");
        }
        if (from != null && (to.isBefore(from) || Duration.between(from, to).toDays() > MAX_RANGE_DAYS)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "verified range is invalid");
        }
        return new Range(from, to);
    }

    private static LocalDateTime utc(OffsetDateTime value, String field) {
        if (value == null) return null;
        try {
            return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static OffsetDateTime instant(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
