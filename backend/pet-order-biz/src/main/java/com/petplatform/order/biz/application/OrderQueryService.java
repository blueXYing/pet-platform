package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSnapshotDTO;
import com.petplatform.order.api.query.OrderQueryApi.MyOrderListQuery;
import com.petplatform.order.api.query.OrderQueryApi.OrderIdQuery;
import com.petplatform.order.biz.application.OrderDisplayStatus.Facts;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.OrderQueryViewEntity;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * C-end order read model (HTTP contract 10 §3.7, C-004 read slice). Read-only: every query is
 * scoped to the QueryContext subject, the fixed sort is created_at DESC then id DESC, and the
 * optional displayStatus filter reuses the same {@link OrderDisplayStatus} truth table the
 * projection computes. A foreign or absent order reads as the same COMMON_NOT_FOUND so ids
 * cannot be enumerated.
 */
public final class OrderQueryService {

    /** OpenAPI 11 Page/PageSize parameter bounds (contract 10 §3.7). */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final OrderQueryStore store;
    private final java.time.Clock clock;

    public OrderQueryService(OrderQueryStore store) {
        this(store, java.time.Clock.systemUTC());
    }

    public OrderQueryService(OrderQueryStore store, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public PageResult<OrderSnapshotDTO> listMyOrders(MyOrderListQuery query) {
        Objects.requireNonNull(query, "query is required");
        long user = subject(query.context());
        String filterStatus = query.displayStatus();
        if (filterStatus != null && !OrderDisplayStatus.VALUES.contains(filterStatus)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "displayStatus is invalid");
        }
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(query.pageSize(), MAX_PAGE_SIZE);
        String predicate = OrderDisplayStatus.sqlPredicate(filterStatus);
        long total = store.read(mapper -> mapper.countMine(user, predicate));
        List<OrderSnapshotDTO> items;
        if (total == 0 || pageSize == 0) {
            items = List.of();
        } else {
            java.time.OffsetDateTime now = clock.instant().atOffset(java.time.ZoneOffset.UTC);
            items = store.read(mapper -> mapper
                            .selectMinePage(user, predicate, pageSize, (long) (page - 1) * pageSize))
                    .stream().map(row -> project(row, now)).toList();
        }
        return new PageResult<>(items, total, page, pageSize);
    }

    public OrderSnapshotDTO getOrder(OrderIdQuery query) {
        Objects.requireNonNull(query, "query is required");
        long id = parse(query.orderId());
        OrderQueryViewEntity row = store.read(mapper -> mapper.selectMineById(id, subject(query.context())));
        if (row == null) {
            // Anti-enumeration: absent and foreign ids share one indistinguishable answer.
            throw new ApiException(CommonApiCodes.NOT_FOUND, "订单不存在");
        }
        return project(row, clock.instant().atOffset(java.time.ZoneOffset.UTC));
    }

    private static long subject(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        return IDS.fromApi(context.operatorId());
    }

    private static long parse(String orderId) {
        try {
            return IDS.fromApi(orderId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.NOT_FOUND, "订单不存在");
        }
    }

    /** Wire-neutral projection; the HTTP adapter renders ids/amounts/instants per contract. */
    private static OrderSnapshotDTO project(OrderQueryViewEntity row, java.time.OffsetDateTime now) {
        String display;
        try {
            display = OrderDisplayStatus.compute(new Facts(row.getOrderStage(), row.getRefundOrderId(),
                    row.getRefundedAmount(), row.getPayAmount(), row.getRefundApplicationStatus(),
                    row.getAftersaleStatus()));
        } catch (RuntimeException unreadable) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "订单状态投影不可用");
        }
        return new OrderSnapshotDTO(
                IDS.toApi(row.getId()),
                IDS.toApi(row.getOrderNo()),
                IDS.toApi(row.getUserId()),
                IDS.toApi(row.getMerchantId()),
                IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getServiceId()),
                IDS.toApi(row.getReservationId()),
                row.getOrderStage(),
                row.getPaymentStatus(),
                row.getRefundApplicationStatus(),
                refundStatus(row),
                row.getAftersaleStatus(),
                row.getVerificationStatus(),
                display,
                money(row.getOriginalAmount()),
                money(row.getDiscountAmount()),
                money(row.getPayAmount()),
                money(row.getRefundedAmount()),
                instant(row.getAppointmentStartAt()),
                instant(row.getAppointmentEndAt()),
                instant(row.getPaidAt()),
                instant(row.getConfirmedAt()),
                instant(row.getVerifiedAt()),
                row.getRescheduleCount() == null ? 0 : row.getRescheduleCount(),
                row.getVersion() == null ? 0L : row.getVersion(),
                OrderActionAvailability.evaluate(new OrderActionAvailability.Facts(
                        row.getOrderStage(), row.getPaymentStatus(), row.getVerificationStatus(),
                        row.getRefundOrderId(), row.getRefundedAmount(), row.getPayAmount(),
                        row.getRefundApplicationStatus(), row.getAftersaleStatus(),
                        row.getRescheduleCount(), row.getPaymentExpireAt(), row.getCanceledAt(),
                        row.getCancelReason(), row.getConfirmedAt(), row.getAppointmentStartAt(),
                        row.getVerifiedAt()), now));
    }

    /**
     * Order-domain refund vocabulary on its own projections: null without a refund order,
     * SUCCESS once the real channel success is projected (refunded_amount &gt; 0), CREATED from
     * refund order creation until then. The display status never depends on this string.
     */
    private static String refundStatus(OrderQueryViewEntity row) {
        if (row.getRefundOrderId() == null) return null;
        BigDecimal refunded = row.getRefundedAmount();
        return refunded != null && refunded.signum() > 0 ? "SUCCESS" : "CREATED";
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static java.time.OffsetDateTime instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
