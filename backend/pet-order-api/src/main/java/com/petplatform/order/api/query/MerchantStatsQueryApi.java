package com.petplatform.order.api.query;

import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Merchant business stats read surface (contract 70, PRD 5.14; 2026-10-19 CCR expansion):
 * store-scoped aggregates computed from the order domain's own pet_order facts plus the review
 * domain's score summary port and the schedule domain's utilization facts port; no new fact
 * sources, no write path, money as 2-dp BigDecimal. Ranges are the PRD quick windows TODAY /
 * LAST_7D / LAST_30D (UTC day anchors) plus CUSTOM from/to (closed day interval, ≤92 days,
 * same UTC day buckets); the delta block compares the equal-length adjacent previous window.
 * The review average rides the optional {@link StoreReviewScoreFactsApi} port and the schedule
 * utilization rides the optional schedule-api utilization port: absent ports render null (--),
 * a present but failing port is 503.
 */
public interface MerchantStatsQueryApi {

    Overview overview(StatsQuery query);

    /** Top services of the window by order count (limit 1..5, default 5). */
    List<ServiceRow> serviceTop(StatsQuery query);

    /** Daily net-revenue/order buckets closing at the window end (TODAY is a single bucket). */
    List<TrendBucket> revenueTrend(StatsQuery query);

    /** Read-only order rows of one service inside the window (2026-10-19 服务下钻 expansion). */
    ServiceOrdersPage serviceOrders(StatsQuery query, String serviceId, Integer page,
            Integer pageSize);

    /** Schedule utilization of the window through the optional SCH facts port. */
    UtilizationView scheduleUtilization(StatsQuery query);

    record StatsQuery(String merchantId, String storeId, String range, Integer limit,
            String from, String to, QueryContext context) {}

    /** Rate strings carry one decimal without the % sign; null means not computable (--). */
    record Overview(String range, BigDecimal revenue, long orderCount, long newCustomers,
            BigDecimal avgTicket, long verifiedOrderCount, String verifyRate,
            long refundOrderCount, String refundRate, String repeatRate, String reviewAvg,
            String rejectRate, Deltas deltas, OffsetDateTime generatedAt) {}

    /** Same-key previous-window percentage deltas; null when the base is 0 or the metric is null. */
    record Deltas(BigDecimal revenue, BigDecimal orderCount, BigDecimal newCustomers,
            BigDecimal avgTicket) {}

    record ServiceRow(String serviceId, String serviceName, long orderCount, BigDecimal revenue) {}

    record TrendBucket(String date, BigDecimal revenue, long orderCount) {}

    /** One drilled-down order: this store's own facts only, newest creation first. */
    record ServiceOrderRow(String orderId, String orderNo, String orderStage, BigDecimal payAmount,
            BigDecimal refundedAmount, OffsetDateTime createdAt, OffsetDateTime appointmentStartAt,
            OffsetDateTime verifiedAt) {}

    record ServiceOrdersPage(String serviceId, String serviceName, long page, long pageSize,
            long total, List<ServiceOrderRow> items) {}

    /** Minute sums from the SCH read side; utilizationRate is a one-decimal percentage string,
     *  null when the store published no OPEN capacity in the window (--). All three facts read
     *  null together when the schedule facts port is not assembled (page renders "--"). */
    record UtilizationView(String range, String from, String to, Long availableMinutes,
            Long bookedMinutes, String utilizationRate) {}
}
