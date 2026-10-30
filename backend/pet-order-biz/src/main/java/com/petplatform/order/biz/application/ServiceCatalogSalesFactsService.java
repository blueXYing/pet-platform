package com.petplatform.order.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSold;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSoldQuery;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySold;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySoldQuery;
import com.petplatform.order.biz.infrastructure.persistence.ServiceCatalogSalesFactsStore;
import com.petplatform.order.biz.infrastructure.persistence.entity.ServiceCatalogSalesCountEntity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Public service-catalog sales facts (74号 §6.2 / CCR-SVC-CARD-001 §3.1): batch grouped counts
 * of effective orders — {@code paid_at IS NOT NULL}, not canceled, {@code refunded_amount = 0};
 * partial and full refunds both do not count and completion/verification is not required.
 * Monthly sold is attributed by {@code paid_at} and fixed to the current natural month of
 * Asia/Shanghai (UTC+8) while storage stays UTC; service sold is all-time. Only ids and counts
 * leave this service. A persistence failure propagates: the composing read face renders the
 * affected field null or fails the page, never a fabricated zero; a grouped row without its
 * count is damaged facts and fails closed as {@code DEPENDENCY_UNAVAILABLE}.
 */
public final class ServiceCatalogSalesFactsService {

    static final ZoneId MONTH_ZONE = ZoneId.of("Asia/Shanghai");
    static final int MAX_BATCH = 200;

    private final ServiceCatalogSalesFactsStore store;
    private final Clock clock;

    public ServiceCatalogSalesFactsService(ServiceCatalogSalesFactsStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public List<StoreMonthlySold> storeMonthlySold(StoreMonthlySoldQuery query) {
        Objects.requireNonNull(query, "query is required");
        List<Long> storeIds = coordinates(query.storeIds(), "storeIds");
        if (storeIds.isEmpty()) return List.of();
        MonthWindow month = currentMonth();
        return store.read(mapper -> mapper.storeMonthlySold(storeIds, month.fromUtc(), month.toUtc()))
                .stream()
                .map(row -> new StoreMonthlySold(Long.toString(row.getId()), count(row)))
                .toList();
    }

    public List<ServiceSold> serviceSold(ServiceSoldQuery query) {
        Objects.requireNonNull(query, "query is required");
        List<Long> serviceIds = coordinates(query.serviceIds(), "serviceIds");
        if (serviceIds.isEmpty()) return List.of();
        return store.read(mapper -> mapper.serviceSold(serviceIds))
                .stream()
                .map(row -> new ServiceSold(Long.toString(row.getId()), count(row)))
                .toList();
    }

    /**
     * The current Asia/Shanghai natural month as a half-open UTC instant window: the first
     * instant of month M in UTC+8 up to (excluding) the first instant of M+1, bound as
     * [from, to) against the DATETIME(3) storage.
     */
    private MonthWindow currentMonth() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MONTH_ZONE));
        LocalDate first = now.toLocalDate().withDayOfMonth(1);
        return new MonthWindow(
                first.atStartOfDay(MONTH_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                first.plusMonths(1).atStartOfDay(MONTH_ZONE).withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime());
    }

    /** Fail closed on a damaged aggregate row: never turn an unavailable count into zero. */
    static long count(ServiceCatalogSalesCountEntity row) {
        if (row.getOrderCount() == null) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "销量事实不可用");
        }
        return row.getOrderCount();
    }

    /** Deduped canonical ids in caller order; empty input is an empty fact, malformed is 400-ish. */
    private static List<Long> coordinates(List<String> values, String label) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_BATCH) {
            throw new IllegalArgumentException(label + " must hold at most " + MAX_BATCH + " ids");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(values.size());
        for (String value : values) {
            ids.add(coordinate(value, label));
        }
        return List.copyOf(ids);
    }

    private static long coordinate(String value, String label) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException(label + " must hold canonical decimal ids");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException(label + " must hold canonical decimal ids", overflow);
        }
    }

    private record MonthWindow(LocalDateTime fromUtc, LocalDateTime toUtc) {}
}
