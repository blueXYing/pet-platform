package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Deltas;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceOrderRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceOrdersPage;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi.StatsQuery;
import com.petplatform.order.api.query.MerchantStatsQueryApi.TrendBucket;
import com.petplatform.order.api.query.MerchantStatsQueryApi.UtilizationView;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Overview;
import com.petplatform.order.api.query.StoreReviewScoreFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderQueryStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.CustomerWindowCount;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.ServiceOrderRowEntity;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.ServiceTopRow;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.StatsTotals;
import com.petplatform.order.biz.infrastructure.persistence.entity.MerchantReadProjectionEntities.TrendRow;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.query.ScheduleUtilizationQueryApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Business stats read model (contract 70): every figure is an order-domain aggregate over the
 * store's own pet_order rows inside the guard + OWNER re-proof, with the review average coming
 * through the optional {@link StoreReviewScoreFactsApi} port (absent port → null, "--"; present
 * but failing → 503) and the schedule utilization through the optional schedule-api facts port.
 * Windows are UTC day anchors: TODAY / LAST_7D / LAST_30D plus the 2026-10-19 CCR expansion
 * CUSTOM from/to (closed UTC day interval, at most 92 days — the guardrail that keeps every
 * aggregate on the store-prefixed index); deltas compare the equal-length adjacent previous
 * window. Money keeps two decimals via BigDecimal; rates are one-decimal percentage strings
 * without the % sign and null when not computable.
 */
public final class MerchantStatsQueryService {

    static final Set<String> RANGES = Set.of("TODAY", "LAST_7D", "LAST_30D", "CUSTOM");
    static final Set<String> QUICK_RANGES = Set.of("TODAY", "LAST_7D", "LAST_30D");
    static final String DEFAULT_RANGE = "LAST_7D";
    static final int DEFAULT_TOP_LIMIT = 5;
    /** Custom window guardrail (contract 70 §1): at most 92 closed UTC days per query. */
    static final int MAX_CUSTOM_DAYS = 92;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private final OrderQueryStore store;
    private final ScheduleCapacityGuardApi guard;
    private final MerchantOrderAuthorityApi authority;
    private final StoreReviewScoreFactsApi reviewScores;
    private final ScheduleUtilizationQueryApi scheduleUtilization;
    private final Clock clock;

    public MerchantStatsQueryService(OrderQueryStore store, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, StoreReviewScoreFactsApi reviewScores,
            ScheduleUtilizationQueryApi scheduleUtilization, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.authority = Objects.requireNonNull(authority, "authority is required");
        this.reviewScores = reviewScores;
        this.scheduleUtilization = scheduleUtilization;
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
            long rejected = count(current.getRejectOrderCount());
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
                    rate(rejected, paid),
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

    /** 服务下钻 (2026-10-19 expansion): paged read-only order rows of one window service. */
    public ServiceOrdersPage serviceOrders(StatsQuery query, String serviceId, Integer page,
            Integer pageSize) {
        Objects.requireNonNull(query, "query is required");
        Window window = window(query);
        long service = serviceCoordinate(serviceId);
        int pageNumber = page == null ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        if (pageNumber < 1) invalid();
        return store.read((queries, mapper) -> {
            proveOwnership(query.merchantId(), query.storeId(), query.context());
            long total = mapper.statsServiceOrderTotal(merchant(query), store(query), service,
                    window.from(), window.to());
            // Anti-enumeration 404 (same shape as the 69号 detail): only services that appear
            // in this store's own window orders are readable at all.
            if (total == 0) notFound();
            List<ServiceOrderRowEntity> rows = total <= (long) (pageNumber - 1) * size
                    ? List.of()
                    : mapper.statsServiceOrders(merchant(query), store(query), service,
                            window.from(), window.to(), size, (long) (pageNumber - 1) * size);
            List<ServiceOrderRow> items = rows.stream()
                    .map(MerchantStatsQueryService::serviceOrder).toList();
            return new ServiceOrdersPage(IDS.toApi(service), mapper.statsServiceLatestName(
                    merchant(query), store(query), service), pageNumber, size, total, items);
        });
    }

    /** 排期利用率 (2026-10-19 expansion): SCH read-side aggregate behind the optional port. */
    public UtilizationView scheduleUtilization(StatsQuery query) {
        Objects.requireNonNull(query, "query is required");
        Window window = window(query);
        String from = DAY.format(window.from().toLocalDate());
        String to = DAY.format(window.to().toLocalDate());
        if (scheduleUtilization == null) {
            // Port not assembled: the whole metric renders "--" (contract 70 §1 boundary,
            // not fail-open — an assembled-but-failing port is 503 below).
            return new UtilizationView(window.range(), from, to, null, null, null);
        }
        ScheduleUtilizationQueryApi.Utilization facts;
        try {
            facts = scheduleUtilization.utilization(new ScheduleUtilizationQueryApi.UtilizationQuery(
                    query.merchantId(), query.storeId(), window.from(), window.to()));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "排期事实不可用");
        }
        if (facts == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "排期事实不可用");
        }
        return new UtilizationView(window.range(), from, to, facts.availableMinutes(),
                facts.bookedMinutes(), rate(facts.bookedMinutes(), facts.availableMinutes()));
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
        if ("CUSTOM".equals(range)) {
            // 自定义区间 (2026-10-19 expansion): closed UTC day interval [from,to], ≤92 days,
            // aligned to the same UTC day buckets as the quick windows.
            if (query.from() == null || query.to() == null) invalid();
            LocalDate from = day(query.from());
            LocalDate to = day(query.to());
            if (from.isAfter(to)) invalid();
            long days = to.toEpochDay() - from.toEpochDay() + 1;
            if (days < 1 || days > MAX_CUSTOM_DAYS) invalid();
            return new Window(range, from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                    from.minusDays(days).atStartOfDay());
        }
        LocalDate start = switch (range) {
            case "TODAY" -> today;
            case "LAST_7D" -> today.minusDays(6);
            default -> today.minusDays(29);
        };
        return new Window(range, start.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                start.minusDays(today.toEpochDay() - start.toEpochDay() + 1).atStartOfDay());
    }

    private record Window(String range, LocalDateTime from, LocalDateTime to, LocalDateTime previousFrom) {}

    private static LocalDate day(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) invalid();
            return LocalDate.parse(value, DAY);
        } catch (DateTimeParseException malformed) {
            return invalid();
        }
    }

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

    private static ServiceOrderRow serviceOrder(ServiceOrderRowEntity row) {
        return new ServiceOrderRow(IDS.toApi(row.getId()), row.getOrderNo() == null ? null
                : row.getOrderNo().toString(), row.getOrderStage(), money(row.getPayAmount()),
                money(row.getRefundedAmount()),
                row.getCreatedAt() == null ? null : row.getCreatedAt().atOffset(ZoneOffset.UTC),
                row.getAppointmentStartAt() == null ? null
                        : row.getAppointmentStartAt().atOffset(ZoneOffset.UTC),
                row.getVerifiedAt() == null ? null : row.getVerifiedAt().atOffset(ZoneOffset.UTC));
    }

    private static long merchant(StatsQuery query) {
        return coordinate(query.merchantId());
    }

    private static long store(StatsQuery query) {
        return coordinate(query.storeId());
    }

    private static long serviceCoordinate(String value) {
        try {
            if (value == null) invalid();
            return IDS.fromApi(value);
        } catch (RuntimeException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "服务坐标不合法");
        }
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

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "服务不在本店该周期订单中");
    }
}
