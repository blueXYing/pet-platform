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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business stats routes (contract 70, HTTP10 §4.9): read-only store aggregates. Rates carry
 * one decimal without the % sign; null renders "--" per PRD 5.14. Range vocabulary is the PRD
 * quick windows TODAY/LAST_7D/LAST_30D (default LAST_7D); the custom range stays a registered
 * deviation.
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
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range");
        mini(request);
        Overview value = queries.overview(
                new StatsQuery(id(merchantId), id(storeId), blankToNull(range), null, userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", value.range());
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
            @RequestParam(required = false) String limit,
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range", "limit");
        mini(request);
        List<ServiceRow> rows = queries.serviceTop(new StatsQuery(id(merchantId), id(storeId),
                blankToNull(range), limitNumber(limit), userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", range == null || range.isEmpty() ? "LAST_7D" : range);
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
            HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "range");
        mini(request);
        List<TrendBucket> buckets = queries.revenueTrend(new StatsQuery(id(merchantId), id(storeId),
                blankToNull(range), null, userQuery(request)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("range", range == null || range.isEmpty() ? "LAST_7D" : range);
        data.put("buckets", buckets.stream().map(bucket -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", bucket.date());
            item.put("revenue", amount(bucket.revenue()));
            item.put("orderCount", bucket.orderCount());
            return item;
        }).toList());
        return envelope(data, request);
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer limitNumber(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("[0-9]{1,2}")) throw invalid();
        return Integer.parseInt(raw);
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
