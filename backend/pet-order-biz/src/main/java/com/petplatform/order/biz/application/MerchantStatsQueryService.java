package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Deltas;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi.StatsQuery;
import com.petplatform.order.api.query.MerchantStatsQueryApi.TrendBucket;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Overview;
import com.petplatform.order.api.query.StoreReviewScoreFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerWindowCount;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.ServiceTopRow;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.StatsTotals;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.TrendRow;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Business stats read model (contract 70): every figure is an order-domain aggregate over the
 * store's own pet_order rows inside the guard + OWNER re-proof, with the review average coming
 * through the optional {@link StoreReviewScoreFactsApi} port (absent port → null, "--"; present
 * but failing → 503). Windows are UTC day anchors: TODAY / LAST_7D / LAST_30D, deltas compare
 * the equal-length adjacent previous window. Money keeps two decimals via BigDecimal; rates
 * are one-decimal percentage strings without the % sign and null when not computable.
 */
public final class MerchantStatsQueryService {

    static final Set<String> RANGES = Set.of("TODAY", "LAST_7D", "LAST_30D");
    static final String DEFAULT_RANGE = "LAST_7D";
    static final int DEFAULT_TOP_LIMIT = 5;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private final OrderQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final StoreReviewScoreFactsApi reviewScores;
    private final Clock clock;

    public MerchantStatsQueryService(OrderQueryStore store, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, StoreReviewScoreFactsApi reviewScores, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.reviewScores = reviewScores;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Overview overview(StatsQuery query) {
        Objects.requireNonNull(query, "query is required");
        Window window = window(query);
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            StatsTotals current = mapper.statsWindowTotals(merchant(query), store(query),
                    window.from(), window.to());
            StatsTotals previous = mapper.statsWindowTotals(merchant(query), store(query),
                    window.previousFrom(), window.from());
            CustomerWindowCount customers = mapper.statsWindowCustomers(merchant(query), store(query),
                    window.from(), window.to());
            CustomerWindowCount previousCustomers = mapper.statsWindowCustomers(merchant(query),
                    store(query), window.previousFrom(), window.from());
            Long newCustomers = mapper.statsNewCustomers(merchant(query), store(query),
                    window.from(), window.to());
            Long previousNew = mapper.statsNewCustomers(merchant(query), store(query),
                    window.previousFrom(), window.from());
            long orderCount = count(current.getOrderCount());
            long paid = count(current.getPaidOrderCount());
            long verified = count(current.getVerifiedOrderCount());
            long refunded = count(current.getRefundOrderCount());
            BigDecimal revenue = money(current.getRevenue());
            BigDecimal avgTicket = verified == 0 ? null
                    : revenue.divide(BigDecimal.valueOf(verified), 2, RoundingMode.DOWN);
            String reviewAvg = reviewAvg(query, window);
            return new Overview(window.range(), revenue, orderCount,
                    count(newCustomers), avgTicket, verified, rate(verified, paid),
                    refunded, rate(refunded, paid),
                    customers == null || count(customers.getCustomerCount()) == 0 ? null
                            : rate(count(customers.getRepeatCustomerCount()),
                                    count(customers.getCustomerCount())),
                    reviewAvg,
                    new Deltas(
                            delta(revenue, money(previous.getRevenue())),
                            delta(orderCount == 0 ? null : BigDecimal.valueOf(orderCount),
                                    count(previous.getOrderCount()) == 0 ? null
                                            : BigDecimal.valueOf(count(previous.getOrderCount()))),
                            delta(count(newCustomers) == 0 ? null : BigDecimal.valueOf(count(newCustomers)),
                                    count(previousNew) == 0 ? null : BigDecimal.valueOf(count(previousNew))),
                            delta(avgTicket,
                                    count(previous.getVerifiedOrderCount()) == 0 ? null
                                            : money(previous.getRevenue()).divide(
                                                    BigDecimal.valueOf(count(previous.getVerifiedOrderCount())),
                                                    2, RoundingMode.DOWN))),
                    java.time.OffsetDateTime.now(clock));
        });
    }

    public List<ServiceRow> serviceTop(StatsQuery query) {
        Objects.requireNonNull(query, "query is required");
        Window window = window(query);
        int limit = query.limit() == null ? DEFAULT_TOP_LIMIT : query.limit();
        if (limit < 1 || limit > 5) invalid();
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            return mapper.statsServiceTop(merchant(query), store(query), window.from(), window.to(),
                    limit).stream().map(MerchantStatsQueryService::service).toList();
        });
    }

    public List<TrendBucket> revenueTrend(StatsQuery query) {
        Objects.requireNonNull(query, "query is required");
        Window window = window(query);
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            List<TrendRow> rows = mapper.statsRevenueTrend(merchant(query), store(query),
                    window.from(), window.to());
            // Fill every day of the window so pages render continuous buckets (missing = 0).
            List<TrendBucket> buckets = new ArrayList<>();
            var cursor = window.from().toLocalDate();
            var end = window.to().toLocalDate();
            int index = 0;
            while (cursor.isBefore(end)) {
                String day = DAY.format(cursor);
                BigDecimal revenue = BigDecimal.ZERO.setScale(2);
                long orders = 0;
                while (index < rows.size() && day.equals(rows.get(index).getBucketDate())) {
                    revenue = money(rows.get(index).getRevenue());
                    orders = count(rows.get(index).getOrderCount());
                    index++;
                }
                buckets.add(new TrendBucket(day, revenue, orders));
                cursor = cursor.plusDays(1);
            }
            return List.copyOf(buckets);
        });
    }

    private String reviewAvg(StatsQuery query, Window window) {
        if (reviewScores == null) return null; // review kernel not assembled → "--" (contract 70 §1)
        try {
            StoreReviewScoreFactsApi.ScoreSummary summary = reviewScores.summary(
                    new StoreReviewScoreFactsApi.SummaryQuery(query.merchantId(), query.storeId(),
                            window.from().atOffset(ZoneOffset.UTC), window.to().atOffset(ZoneOffset.UTC)));
            return summary == null ? null : summary.avgScore();
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "评分事实不可用");
        }
    }

    private void proveOwnership(String merchantId, String storeId, QueryContext context) {
        if (context == null || context.operatorType() != OperatorType.USER
                || context.operatorId() == null) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query subject is required");
        }
        guard.acquire(List.of(storeId), new QueryContext(context.traceId(), OperatorType.SYSTEM, null));
        authority.requireOwnerRead(merchantId, storeId, context);
    }

    private Window window(StatsQuery query) {
        if (query.merchantId() == null || query.storeId() == null) invalid();
        String range = query.range() == null || query.range().isEmpty() ? DEFAULT_RANGE : query.range();
        if (!RANGES.contains(range)) invalid();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = switch (range) {
            case "TODAY" -> today;
            case "LAST_7D" -> today.minusDays(6);
            default -> today.minusDays(29);
        };
        return new Window(range, start.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                start.minusDays(today.toEpochDay() - start.toEpochDay() + 1).atStartOfDay());
    }

    private record Window(String range, LocalDateTime from, LocalDateTime to, LocalDateTime previousFrom) {}

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.DOWN);
    }

    /** One-decimal percentage string; denominator 0 → null ("--"). */
    private static String rate(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator * 100L)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.DOWN).toPlainString();
    }

    /** Previous-window percentage delta, one decimal; null base or null current → null. */
    private static BigDecimal delta(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static ServiceRow service(ServiceTopRow row) {
        return new ServiceRow(IDS.toApi(row.getServiceId()), row.getServiceName(),
                count(row.getOrderCount()), money(row.getRevenue()));
    }

    private static long merchant(StatsQuery query) {
        return coordinate(query.merchantId());
    }

    private static long store(StatsQuery query) {
        return coordinate(query.storeId());
    }

    private static long coordinate(String value) {
        try {
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "商家坐标不合法");
        }
    }

    private static void invalid() {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "统计参数不合法");
    }
}
