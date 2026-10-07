package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSnapshotDTO;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;

/**
 * Read-only order query surface realizing internal contract 07 §7.1 (C-004 read slice,
 * 2026-10-07): {@link #getOrder(OrderIdQuery)} per the registered §7.4 snapshot, plus
 * {@link #listMyOrders(MyOrderListQuery)} registered by the same slice for HTTP contract 10
 * §3.7. The subject is always the session user carried by {@link QueryContext}; client
 * parameters never select it. {@code displayStatus} is computed by the order domain with the
 * tech baseline §5 priority; this API offers no write path. Of the §7.1 eligibility checks,
 * {@link #checkReviewEligibility(OrderIdQuery)} (§7.7) is delivered by the REV-001 slice; the
 * refund and verification eligibility queries stay drafted until their own slices.
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

    /**
     * §7.7 review eligibility over the caller's own order facts (REV-001 slice). Same
     * anti-enumeration contract as {@link #getOrder(OrderIdQuery)}: absent and foreign ids
     * are one indistinguishable COMMON_NOT_FOUND. Existing reviews are not this API's concern.
     */
    ReviewEligibilityDTO checkReviewEligibility(OrderIdQuery query);

    /** orderId is the canonical decimal string form of the pet_order id. */
    record OrderIdQuery(String orderId, QueryContext context) {}

    /** displayStatus is one of the ten DisplayOrderStatus values or null; page starts at 1. */
    record MyOrderListQuery(String displayStatus, int page, int pageSize, QueryContext context) {}
}
