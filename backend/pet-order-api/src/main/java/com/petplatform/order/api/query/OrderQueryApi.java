package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSnapshotDTO;

/**
 * Read-only order query surface realizing internal contract 07 §7.1 (C-004 read slice,
 * 2026-10-07): {@link #getOrder(OrderIdQuery)} per the registered §7.4 snapshot, plus
 * {@link #listMyOrders(MyOrderListQuery)} registered by the same slice for HTTP contract 10
 * §3.7. The subject is always the session user carried by {@link QueryContext}; client
 * parameters never select it. {@code displayStatus} is computed by the order domain with the
 * tech baseline §5 priority; this API offers no write path. The §7.1 eligibility checks stay
 * drafted until their own slices.
 */
public interface OrderQueryApi {

    /**
     * One own-order snapshot. An order that does not exist or belongs to someone else is
     * reported the same way (COMMON_NOT_FOUND) so the id space cannot be enumerated.
     */
    OrderSnapshotDTO getOrder(OrderIdQuery query);

    /**
     * Page of the caller's own orders, newest first (created_at DESC, id DESC as the stable
     * tiebreaker). {@code displayStatus} optionally filters by the domain-computed display
     * state (DisplayOrderStatus vocabulary); null means no filter.
     */
    PageResult<OrderSnapshotDTO> listMyOrders(MyOrderListQuery query);

    /** orderId is the canonical decimal string form of the pet_order id. */
    record OrderIdQuery(String orderId, QueryContext context) {}

    /** displayStatus is one of the ten DisplayOrderStatus values or null; page starts at 1. */
    record MyOrderListQuery(String displayStatus, int page, int pageSize, QueryContext context) {}
}
