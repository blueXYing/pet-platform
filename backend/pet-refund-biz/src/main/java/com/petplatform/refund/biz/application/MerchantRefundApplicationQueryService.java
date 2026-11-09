package com.petplatform.refund.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationListQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.TimelineEntry;
import com.petplatform.refund.biz.application.MerchantRefundApplicationLabels.Labels;
import com.petplatform.refund.biz.infrastructure.persistence.RefundApplicationQueryStore;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationQueryMapper.Row;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.UserQueryApi;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Merchant refund-application read model (contract 57, HTTP10 §4.4 supplement). Read-only:
 * every query is scoped to the OWNER merchant coordinate (merchantId+storeId) re-proven per
 * call inside the store-guard transaction via the owner-read port (ACTIVE/OFFLINE/FROZEN
 * existing-material semantics; FROZEN stays readable while decisions fail closed), then
 * filtered by both keys so a foreign, absent or non-owned store answers the same
 * COMMON_FORBIDDEN (anti-enumeration) and cross-store rows never appear. The list is fixed
 * to the {@code PENDING_MERCHANT} backlog with the fixed sort created_at DESC, id DESC; the
 * single read accepts any status of the owned store. The buyer's sealed reason text never
 * enters this projection (contract 49 cipher; CCR-M-REFUND-VIEW-001 §3.2 not approved) and the
 * buyer id is only an internal join key. CCR-M-REFUND-VIEW-001 §3.1/§3.3 (approved 2026-11-06):
 * after the OWNER proof and inside the same transaction, the proven rows get their nullable
 * nickname / server-masked phone / order-time service name (batched foreign reads, see
 * {@link MerchantRefundApplicationLabels}) and a timeline derived only from persisted
 * timestamps of this module (refund_application created_at / merchant_deadline / decided_at and
 * the bound refund_order created_at).
 */
public final class MerchantRefundApplicationQueryService {

    /** OpenAPI 11 Page/PageSize parameter bounds (same as the §4.1 merchant order list). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES =
        Set.of("PENDING_MERCHANT", "APPROVED", "AUTO_APPROVED", "REJECTED");
    /** Contract 57 timeline stages in their lifecycle order (the tie-break for equal instants). */
    static final List<String> STAGES =
        List.of("APPLIED", "MERCHANT_DEADLINE", "DECIDED", "REFUND_ORDER_CREATED");

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final RefundApplicationQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final RefundApplicationPorts.OwnerReadAuthority ownerRead;
    private final MerchantRefundApplicationLabels labels;

    /**
     * {@code source} must be the same DataSource the store transaction runs on: the ORDER
     * display-label port re-checks the caller transaction and the held store guard on it.
     * {@code orders} may answer null when the ORDER after-sale facts port is not assembled.
     */
    public MerchantRefundApplicationQueryService(RefundApplicationQueryStore store, DataSource source,
            ScheduleCapacityGuardApi guard, RefundApplicationPorts.OwnerReadAuthority ownerRead,
            UserQueryApi users, Supplier<OrderAfterSaleFactsApi> orders) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.ownerRead = Objects.requireNonNull(ownerRead, "ownerRead is required");
        this.labels = new MerchantRefundApplicationLabels(source, users, orders);
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
                List<Row> rows = mapper.selectPendingPage(merchant, storeKey,
                                pageSize, (long) (page - 1) * pageSize);
                rows.forEach(MerchantRefundApplicationQueryService::validate);
                // Display facts only for rows of the already-proven OWNER store read.
                Map<Long, Labels> display = labels.read(rows, query.storeId(), query.context().traceId());
                items = rows.stream()
                        .map(row -> project(row, display.getOrDefault(row.id, Labels.NONE)))
                        .toList();
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
            // Unknown and foreign application ids read exactly like non-owned stores (403),
            // before any foreign display read: the refusal never carries a customer fact.
            if (row == null) throw forbidden();
            validate(row);
            Map<Long, Labels> display = labels.read(List.of(row), query.storeId(), query.context().traceId());
            return project(row, display.getOrDefault(row.id, Labels.NONE));
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
    private static Summary project(Row row, Labels display) {
        return new Summary(IDS.toApi(row.id), IDS.toApi(row.applicationNo), IDS.toApi(row.orderId),
            row.status, Long.toString(row.version), row.reasonCode, money(row.requestedAmount),
            instant(row.createdAt), instant(row.merchantDeadline), instant(row.decidedAt),
            nullable(row.decisionId), nullable(row.refundOrderId),
            display.customerNickname(), display.customerPhoneMasked(), display.serviceName(),
            timeline(row));
    }

    /** Structural facts of this module's own rows; a damaged row is a projection outage. */
    static void validate(Row row) {
        if (row.id == null || row.applicationNo == null || row.orderId == null
                || !STATUSES.contains(row.status) || row.version == null || row.version < 0
                || row.reasonCode == null || row.reasonCode.isBlank()
                || row.requestedAmount == null || row.requestedAmount.signum() <= 0
                || row.createdAt == null || row.merchantDeadline == null
                || row.applicantUserId == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "退款申请投影不可用");
        }
        // 49号 chk_refund_application_state: decided_at exists exactly for decided rows, so the
        // DECIDED stage can never appear on a pending application.
        boolean pending = "PENDING_MERCHANT".equals(row.status);
        if (pending == (row.decidedAt != null)) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "退款申请投影不可用");
        }
        // The bound refund_order row exists exactly when an approved application points at one
        // (bindRefund), and its own created_at is the REFUND_ORDER_CREATED fact.
        if ((row.refundOrderId == null) != (row.refundCreatedAt == null)
                || row.refundOrderId != null && !"APPROVED".equals(row.status)
                    && !"AUTO_APPROVED".equals(row.status)) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "退款申请投影不可用");
        }
    }

    /**
     * CCR-M-REFUND-VIEW-001 §3.3: each entry exists only because its persisted timestamp
     * exists (refund_application.created_at / merchant_deadline / decided_at and the bound
     * refund_order.created_at), ascending by occurredAt with the lifecycle order as the
     * tie-break (a pre-service automatic approval is decided at its creation instant). No entry
     * is ever inferred and DisplayOrderStatus is never derived here. Call after {@link #validate}.
     */
    static List<TimelineEntry> timeline(Row row) {
        List<TimelineEntry> entries = new ArrayList<>(4);
        entries.add(new TimelineEntry("APPLIED", instant(row.createdAt)));
        entries.add(new TimelineEntry("MERCHANT_DEADLINE", instant(row.merchantDeadline)));
        if (row.decidedAt != null) entries.add(new TimelineEntry("DECIDED", instant(row.decidedAt)));
        if (row.refundOrderId != null && row.refundCreatedAt != null) {
            entries.add(new TimelineEntry("REFUND_ORDER_CREATED", instant(row.refundCreatedAt)));
        }
        entries.sort(Comparator
            .comparing(TimelineEntry::occurredAt)
            .thenComparingInt(entry -> STAGES.indexOf(entry.stage())));
        return List.copyOf(entries);
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
