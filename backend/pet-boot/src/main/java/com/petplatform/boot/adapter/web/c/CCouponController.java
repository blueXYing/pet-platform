package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.dto.CouponInstanceDTO;
import com.petplatform.coupon.api.query.CouponQueryApi;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponQuery;
import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/coupons, GET /api/v1/c/coupons/{couponId} (CCR-C006-COUPON-POINTS-READ-001 P1).
 * The owner is always the current session user; client parameters never select it. D2: only
 * AVAILABLE/USED/EXPIRED buckets are selectable; FROZEN/RISK_FROZEN never surface. D1: the
 * rule_json-derived display fields are server-projected; a non-owner or absent coupon is a
 * uniform 404 (anti-enumeration).
 */
@RestController
@RequestMapping("/api/v1/c/coupons")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CCouponController {

    /** D2: the C surface only exposes the three display buckets; default is the usable one. */
    private static final Set<String> BUCKETS = Set.of("AVAILABLE", "USED", "EXPIRED");

    private final CouponQueryApiImpl coupons;

    public CCouponController(CouponQueryApiImpl coupons) {
        this.coupons = coupons;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        String bucket = status == null || status.isEmpty() ? "AVAILABLE" : status;
        if (!BUCKETS.contains(bucket)) throw invalid("status");
        MiniSessionView session = session(req);
        PageResult<CouponInstanceDTO> value = coupons.listMyCoupons(
                new MyCouponListQuery(
                        bucket,
                        parse(page, 1, 10_000, 1, "page"),
                        parse(pageSize, 20, 50, 1, "pageSize"),
                        new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CCouponController::body).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/{couponId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String couponId, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
        MiniSessionView session = session(req);
        CouponInstanceDTO value = coupons.getMyCoupon(
                new MyCouponQuery(
                        couponId,
                        new QueryContext(trace(req), OperatorType.USER, session.userId())));
        return ApiResponse.success(body(value), trace(req));
    }

    private static String format(OffsetDateTime value) {
        return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(value.toInstant());
    }

    /** Wire projection: fixed sort is expire_at then id; amounts are two-decimal strings. */
    private static Map<String, Object> body(CouponInstanceDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("couponId", item.couponId());
        row.put("name", item.name());
        row.put("amountOff", item.amountOff());
        row.put("thresholdAmount", item.thresholdAmount());
        row.put("scopeSummary", item.scopeSummary());
        row.put("typeLabel", item.typeLabel());
        row.put("validTo", item.validTo() == null ? null : item.validTo().toString());
        row.put("status", item.status());
        row.put("usedAt", item.usedAt() == null ? null : format(item.usedAt()));
        return row;
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return session;
    }

    private static int parse(String raw, int fallback, int max, int min, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < min || value > max) throw invalid(field);
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"status", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"status".equals(name) && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid(name);
            }
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
