package com.petplatform.refund.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationListQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import com.petplatform.refund.biz.infrastructure.persistence.RefundApplicationQueryStore;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper.Row;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Merchant refund-application read model (contract 57, HTTP10 §4.4 supplement). Read-only:
 * every query is scoped to the OWNER merchant coordinate (merchantId+storeId) re-proven per
 * call inside the store-guard transaction via the owner-read port (ACTIVE/OFFLINE/FROZEN
 * existing-material semantics; FROZEN stays readable while decisions fail closed), then
 * filtered by both keys so a foreign, absent or non-owned store answers the same
 * COMMON_FORBIDDEN (anti-enumeration) and cross-store rows never appear. The list is fixed
 * to the {@code PENDING_MERCHANT} backlog with the fixed sort created_at DESC, id DESC; the
 * single read accepts any status of the owned store. The buyer's sealed reason text and any
 * buyer identity field never enter this projection (contract 49 cipher).
 */
public final class MerchantRefundApplicationQueryService {

    /** OpenAPI 11 Page/PageSize parameter bounds (same as the §4.1 merchant order list). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES =
        Set.of("PENDING_MERCHANT", "APPROVED", "AUTO_APPROVED", "REJECTED");

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final RefundApplicationQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final RefundApplicationPorts.OwnerReadAuthority ownerRead;

    public MerchantRefundApplicationQueryService(RefundApplicationQueryStore store,
            ScheduleCapacityGuardApi guard, RefundApplicationPorts.OwnerReadAuthority ownerRead) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.ownerRead = Objects.requireNonNull(ownerRead, "ownerRead is required");
    }

    public PageResult<Summary> listStoreApplications(StoreApplicationListQuery query) {
        Objects.requireNonNull(query, "query is required");
        requireSubject(query.context());
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
            : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        return store.read(mapper -> {
            guardAndReprove(query.merchantId(), query.storeId(), query.context());
            long total = mapper.countPending(merchant, storeKey);
            List<Summary> items;
            if (total == 0 || pageSize == 0) {
                items = List.of();
            } else {
                items = mapper.selectPendingPage(merchant, storeKey,
                                pageSize, (long) (page - 1) * pageSize)
                        .stream().map(MerchantRefundApplicationQueryService::project).toList();
            }
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    public Summary readStoreApplication(StoreApplicationQuery query) {
        Objects.requireNonNull(query, "query is required");
        requireSubject(query.context());
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        long application = coordinate(query.applicationId());
        return store.read(mapper -> {
            guardAndReprove(query.merchantId(), query.storeId(), query.context());
            Row row = mapper.selectStoreApplication(merchant, storeKey, application);
            // Unknown and foreign application ids read exactly like non-owned stores (403).
            if (row == null) throw forbidden();
            return project(row);
        });
    }

    /** Same store-guard + OWNER read re-proof discipline as the §4.1 merchant order list. */
    private void guardAndReprove(String merchantId, String storeId, QueryContext context) {
        guard.acquire(List.of(storeId), new QueryContext(context.traceId(), OperatorType.SYSTEM, null));
        ownerRead.requireOwnerRead(context, merchantId, storeId);
    }

    private static void requireSubject(QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    private static ApiException forbidden() {
        return new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
    }

    /** Wire-neutral minimal merchant summary; the HTTP adapter renders ids/amounts/instants. */
    private static Summary project(Row row) {
        if (row.id == null || row.applicationNo == null || row.orderId == null
                || !STATUSES.contains(row.status) || row.version == null || row.version < 0
                || row.reasonCode == null || row.reasonCode.isBlank()
                || row.requestedAmount == null || row.requestedAmount.signum() <= 0
                || row.createdAt == null || row.merchantDeadline == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "退款申请投影不可用");
        }
        return new Summary(IDS.toApi(row.id), IDS.toApi(row.applicationNo), IDS.toApi(row.orderId),
            row.status, Long.toString(row.version), row.reasonCode, money(row.requestedAmount),
            instant(row.createdAt), instant(row.merchantDeadline), instant(row.decidedAt),
            nullable(row.decisionId), nullable(row.refundOrderId));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static java.time.OffsetDateTime instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String nullable(Long value) {
        return value == null ? null : IDS.toApi(value);
    }
}
