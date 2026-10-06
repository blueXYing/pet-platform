package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.dto.PointsBalanceDTO;
import com.petplatform.points.api.dto.PointsLedgerDTO;
import com.petplatform.points.api.query.PointsQueryApi.PointsBalanceQuery;
import com.petplatform.points.api.query.PointsQueryApi.PointsLedgerQuery;
import com.petplatform.points.biz.apiimpl.PointsQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/points/balance, GET /api/v1/c/points/ledger
 * (CCR-C006-COUPON-POINTS-READ-001 P1). The account subject is always the current session user.
 * The ledger keeps its fixed created_at DESC ordering; integers cross the wire as strings.
 */
@RestController
@RequestMapping("/api/v1/c/points")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CPointsController {

    private final PointsQueryApiImpl points;

    public CPointsController(PointsQueryApiImpl points) {
        this.points = points;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> balance(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
        MiniSessionView session = session(req);
        PointsBalanceDTO value = points.getBalance(
                new PointsBalanceQuery(new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balance", value.balance());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/ledger")
    public ApiResponse<Map<String, Object>> ledger(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        MiniSessionView session = session(req);
        PageResult<PointsLedgerDTO> value = points.queryLedger(
                new PointsLedgerQuery(
                        parse(page, 1, 10_000, 1, "page"),
                        parse(pageSize, 20, 50, 1, "pageSize"),
                        new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CPointsController::row).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    private static String format(java.time.OffsetDateTime value) {
        return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(value.toInstant());
    }

    private static Map<String, Object> row(PointsLedgerDTO item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ledgerId", item.ledgerId());
        data.put("bizType", item.bizType());
        data.put("delta", item.delta());
        data.put("balanceAfter", item.balanceAfter());
        data.put("createdAt", format(item.createdAt()));
        return data;
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
        for (String name : new String[] {"page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"page".equals(name) && !"pageSize".equals(name)) throw invalid(name);
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
