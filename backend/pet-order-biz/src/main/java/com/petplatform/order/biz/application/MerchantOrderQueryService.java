package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi.StoreOrderListQuery;
import com.petplatform.order.api.query.MerchantOrderQueryApi.Summary;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.OrderQueryViewEntity;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Merchant order list read model (HTTP contract 10 §4.1 supplement, the slice after contract
 * 45's manual decisions). Read-only: every query is scoped to the OWNER merchant coordinate
 * (merchantId+storeId) re-proven per call inside the store guard transaction via
 * {@link MerchantOrderAuthorityApi#requireOwnerRead} (ACTIVE/OFFLINE/FROZEN existing-order
 * family, same as the contract-51 store read), then filtered by both keys so a foreign,
 * absent or non-owned store answers the same COMMON_FORBIDDEN (anti-enumeration) and
 * cross-store rows never appear. The fixed sort is created_at DESC then id DESC; the optional
 * displayStatus filter reuses the same {@link OrderDisplayStatus} truth table the C-end §3.7
 * list filters with. No page derivation beyond the wire bounds lives here.
 */
public final class MerchantOrderQueryService {

    /** OpenAPI 11 Page/PageSize parameter bounds (contract 10 §4.1 supplement, same as §3.7). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;

    public MerchantOrderQueryService(OrderQueryStore store, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
    }

    public PageResult<Summary> listStoreOrders(StoreOrderListQuery query) {
        Objects.requireNonNull(query, "query is required");
        if (query.context() == null || query.context().operatorType() != OperatorType.USER
                || query.context().operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        long merchant = coordinate(query.merchantId());
        long storeKey = coordinate(query.storeId());
        String filterStatus = query.displayStatus();
        if (filterStatus != null && !OrderDisplayStatus.VALUES.contains(filterStatus)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "displayStatus is invalid");
        }
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        return store.read(mapper -> {
            // Same store guard + OWNER re-proof discipline as the contract-45 commands: the
            // coordinate is validated under the stable SCH row lock on every read.
            guard.acquire(List.of(query.storeId()),
                    new QueryContext(query.context().traceId(), OperatorType.SYSTEM, null));
            authority.requireOwnerRead(query.merchantId(), query.storeId(), query.context());
            long total = mapper.countForStore(merchant, storeKey, filterStatus);
            List<Summary> items;
            if (total == 0 || pageSize == 0) {
                items = List.of();
            } else {
                items = mapper.selectForStorePage(merchant, storeKey, filterStatus,
                                pageSize, (long) (page - 1) * pageSize)
                        .stream().map(MerchantOrderQueryService::project).toList();
            }
            return new PageResult<>(items, total, page, pageSize);
        });
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    /** Wire-neutral minimal merchant summary; the HTTP adapter renders ids/amounts/instants. */
    private static Summary project(OrderQueryViewEntity row) {
        String display;
        try {
            display = OrderDisplayStatus.compute(new OrderDisplayStatus.Facts(row.getOrderStage(),
                    row.getRefundOrderId(), row.getRefundedAmount(), row.getPayAmount(),
                    row.getRefundApplicationStatus(), row.getAftersaleStatus()));
        } catch (RuntimeException unreadable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "订单状态投影不可用");
        }
        return new Summary(IDS.toApi(row.getId()), IDS.toApi(row.getOrderNo()), display,
                money(row.getPayAmount()), instant(row.getAppointmentStartAt()),
                instant(row.getAppointmentEndAt()), instant(row.getPaidAt()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static java.time.OffsetDateTime instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
