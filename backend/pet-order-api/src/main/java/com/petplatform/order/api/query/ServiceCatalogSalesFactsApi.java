package com.petplatform.order.api.query;

import java.util.List;

/**
 * Order-owned public sales facts read surface for the service catalog page (74号 §6.2 /
 * CCR-SVC-CARD-001 §3.1). Two batch-only aggregates over this module's own pet_order rows:
 *
 * <ul>
 *   <li>{@link #storeMonthlySold} — effective orders per store in the current natural month
 *       (Asia/Shanghai), attributed by {@code paid_at};</li>
 *   <li>{@link #serviceSold} — all-time effective orders per service, independent of the
 *       calendar window (PRD 5.1.13 does not bound service sold by time).</li>
 * </ul>
 *
 * <p>An effective order is {@code paid_at IS NOT NULL} AND not canceled AND
 * {@code refunded_amount = 0}. Partial refunds and full refunds both do not count; the order
 * does not need to be completed or verified. The natural month is fixed to Asia/Shanghai
 * (UTC+8) while {@code paid_at} is stored in UTC.
 *
 * <p>Pure fact read: only ids and counts cross the surface — no order id, user id, phone,
 * amount or per-order detail, and no enumeration face (callers must name the ids they ask
 * about). Ids without a qualifying order are absent from the result, which means zero.
 * Batch queries carry at most 200 canonical decimal ids (duplicates are collapsed); empty
 * input returns an empty result, malformed input is an {@link IllegalArgumentException}.
 */
public interface ServiceCatalogSalesFactsApi {

    /** Current natural month (Asia/Shanghai) effective-order counts, batch by store. */
    List<StoreMonthlySold> storeMonthlySold(StoreMonthlySoldQuery query);

    /** All-time effective-order counts, batch by service. */
    List<ServiceSold> serviceSold(ServiceSoldQuery query);

    /** storeIds are canonical decimal pet_order.store_id strings, at most 200 values. */
    record StoreMonthlySoldQuery(List<String> storeIds) {
        public StoreMonthlySoldQuery {
            storeIds = copyOf(storeIds);
        }
    }

    /** Effective orders of one store in the current Asia/Shanghai natural month. */
    record StoreMonthlySold(String storeId, long orderCount) {}

    /** serviceIds are canonical decimal pet_order.service_id strings, at most 200 values. */
    record ServiceSoldQuery(List<String> serviceIds) {
        public ServiceSoldQuery {
            serviceIds = copyOf(serviceIds);
        }
    }

    /** All-time effective orders of one service. */
    record ServiceSold(String serviceId, long orderCount) {}

    /** Null-tolerant defensive copy; a null coordinate is rejected as malformed input. */
    private static List<String> copyOf(List<String> ids) {
        if (ids == null) return List.of();
        for (String id : ids) {
            if (id == null) throw new IllegalArgumentException("ids must not contain null");
        }
        return List.copyOf(ids);
    }
}
