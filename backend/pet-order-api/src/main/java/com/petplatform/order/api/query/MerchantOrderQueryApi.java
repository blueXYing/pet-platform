package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Read-only merchant order list surface (HTTP contract 10 §4.1 supplement, the slice after
 * contract 45's manual decisions): the caller's own store orders only. The OWNER merchant
 * coordinate (merchantId+storeId) is re-proven per call inside the store guard transaction by
 * the merchant authority port; the query then filters by both keys, so a foreign or absent
 * store reads as the same COMMON_FORBIDDEN (anti-enumeration) and cross-store rows never leak.
 * {@code displayStatus} reuses the same DisplayOrderStatus vocabulary and truth table the
 * C-end §3.7 list filters with (computed inside the order domain); the fixed sort is
 * created_at DESC, id DESC. This API offers no write path.
 */
public interface MerchantOrderQueryApi {

    /**
     * One page of the store's orders, newest first. {@code displayStatus} is one of the ten
     * DisplayOrderStatus values or null (no filter); page starts at 1, pageSize 1..100.
     */
    PageResult<Summary> listStoreOrders(StoreOrderListQuery query);

    /** Minimal merchant-operations summary; ids/amounts/instants render as wire strings per contract. */
    record Summary(String orderId, String orderNo, String displayStatus, BigDecimal payAmount,
            OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd, OffsetDateTime paidAt) {}

    /** merchantId/storeId are canonical decimal strings; the subject is the session user. */
    record StoreOrderListQuery(String merchantId, String storeId, String displayStatus,
            int page, int pageSize, QueryContext context) {}
}
