package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi;
import com.petplatform.order.api.query.MerchantStatsQueryApi.Overview;
import com.petplatform.order.api.query.MerchantStatsQueryApi.ServiceRow;
import com.petplatform.order.api.query.MerchantStatsQueryApi.StatsQuery;
import com.petplatform.order.api.query.MerchantStatsQueryApi.TrendBucket;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.UserQueryApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business stats routes (contract 70, HTTP10 §4.9): read-only store aggregates. Rates carry
 * one decimal without the % sign; null renders "--" per PRD 5.14. Range vocabulary is the PRD
 * quick windows TODAY/LAST_7D/LAST_30D (default LAST_7D) plus the 2026-10-19 expansion
 * CUSTOM from/to (≤92 closed UTC days); the 2026-10-19 expansion also adds the service
 * drill-down page and the schedule utilization read.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/stats", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "pet.order.stats.http.enabled", havingValue = "true")
public final class MerchantStatsController {

    private final MerchantStatsQueryApi queries;

    public MerchantStatsController(MerchantStatsQueryApi queries) {
        this.queries = queries;
    }

    @ModelAttribute
    void cache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/overview")
    Map<String, Object> overview(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "from", "to");
        mini(request);
        Overview value = queries.overview(query(merchantId, storeId, range, from, to, null,
                userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", value.range());
        if ("CUSTOM".equals(value.range())) {
            data.put("from", from);
            data.put("to", to);
        }
        data.put("revenue", amount(value.revenue()));
        data.put("orderCount", value.orderCount());
        data.put("newCustomers", value.newCustomers());
        data.put("avgTicket", amount(value.avgTicket()));
        data.put("verifiedOrderCount", value.verifiedOrderCount());
        data.put("verifyRate", value.verifyRate());
        data.put("refundOrderCount", value.refundOrderCount());
        data.put("refundRate", value.refundRate());
        data.put("repeatRate", value.repeatRate());
        data.put("reviewAvg", value.reviewAvg());
        data.put("rejectRate", value.rejectRate());
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("revenue", decimal(value.deltas().revenue()));
        deltas.put("orderCount", decimal(value.deltas().orderCount()));
        deltas.put("newCustomers", decimal(value.deltas().newCustomers()));
        deltas.put("avgTicket", decimal(value.deltas().avgTicket()));
        data.put("deltas", deltas);
        data.put("generatedAt", time(value.generatedAt()));
        return envelope(data, request);
    }

    @GetMapping("/services/top")
    Map<String, Object> serviceTop(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "from", "to", "limit");
        mini(request);
        List<ServiceRow> rows = queries.serviceTop(query(merchantId, storeId, range, from, to,
                limitNumber(limit), userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", rangeLabel(range));
        if ("CUSTOM".equals(data.get("range"))) {
            data.put("from", from);
            data.put("to", to);
        }
        data.put("items", rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("serviceId", row.serviceId());
            item.put("serviceName", row.serviceName());
            item.put("orderCount", row.orderCount());
            item.put("revenue", amount(row.revenue()));
            return item;
        }).toList());
        return envelope(data, request);
    }

    @GetMapping("/revenue-trend")
    Map<String, Object> revenueTrend(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "from", "to");
        mini(request);
        List<TrendBucket> buckets = queries.revenueTrend(query(merchantId, storeId, range, from,
                to, null, userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", rangeLabel(range));
        if ("CUSTOM".equals(data.get("range"))) {
            data.put("from", from);
            data.put("to", to);
        }
        data.put("buckets", buckets.stream().map(bucket -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", bucket.date());
            item.put("revenue", amount(bucket.revenue()));
            item.put("orderCount", bucket.orderCount());
            return item;
        }).toList());
        return envelope(data, request);
    }

    /** 服务下钻 (2026-10-19 expansion): paged read-only order rows of one window service. */
    @GetMapping("/services/{serviceId}/orders")
    Map<String, Object> serviceOrders(@PathVariable String serviceId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "from", "to", "page", "pageSize");
        mini(request);
        MerchantStatsQueryApi.ServiceOrdersPage value = queries.serviceOrders(
                query(merchantId, storeId, range, from, to, null, userQuery(request)),
                id(serviceId), pageNumber(page), sizeNumber(pageSize));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceId", value.serviceId());
        data.put("serviceName", value.serviceName());
        data.put("range", rangeLabel(range));
        if ("CUSTOM".equals(data.get("range"))) {
            data.put("from", from);
            data.put("to", to);
        }
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        data.put("items", value.items().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", row.orderId());
            item.put("orderNo", row.orderNo());
            item.put("displayStatus", row.displayStatus());
            item.put("payAmount", amount(row.payAmount()));
            item.put("refundedAmount", amount(row.refundedAmount()));
            item.put("createdAt", time(row.createdAt()));
            item.put("appointmentStartAt", time(row.appointmentStartAt()));
            item.put("verifiedAt", time(row.verifiedAt()));
            return item;
        }).toList());
        return envelope(data, request);
    }

    /** 排期利用率 (2026-10-19 expansion): SCH read-side aggregate behind the optional port. */
    @GetMapping("/schedule-utilization")
    Map<String, Object> scheduleUtilization(@RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "from", "to");
        mini(request);
        MerchantStatsQueryApi.UtilizationView value = queries.scheduleUtilization(
                query(merchantId, storeId, range, from, to, null, userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", value.range());
        if ("CUSTOM".equals(value.range())) {
            data.put("from", from);
            data.put("to", to);
        } else {
            data.put("from", value.from());
            data.put("to", value.to());
        }
        data.put("availableMinutes", value.availableMinutes());
        data.put("bookedMinutes", value.bookedMinutes());
        data.put("utilizationRate", value.utilizationRate());
        return envelope(data, request);
    }

    private static MerchantStatsQueryApi.StatsQuery query(String merchantId, String storeId,
            String range, String from, String to, Integer limit,
            com.petplatform.common.QueryContext context) {
        return new MerchantStatsQueryApi.StatsQuery(id(merchantId), id(storeId),
                blankToNull(range), limit, date(from), date(to), context);
    }

    private static String rangeLabel(String range) {
        return range == null || range.isEmpty() ? "LAST_7D" : range;
    }

    /** ISO calendar date only when the client actually sent one (CUSTOM contract). */
    private static String date(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid();
        return raw;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer limitNumber(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("[0-9]{1,2}")) throw invalid();
        return Integer.parseInt(raw);
    }

    private static Integer pageNumber(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("[0-9]{1,10}")) throw invalid();
        long value = Long.parseLong(raw);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    private static Integer sizeNumber(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("[0-9]{1,3}")) throw invalid();
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 100) throw invalid();
        return value;
    }

    /** 11号 DecimalAmountOutput：恒两位小数。 */
    private static String amount(java.math.BigDecimal value) {
        return value == null ? null
                : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
