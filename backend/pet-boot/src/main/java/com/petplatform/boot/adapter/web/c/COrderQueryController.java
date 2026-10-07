package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderQueryApi.MyOrderListQuery;
import com.petplatform.order.biz.apiimpl.OrderQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/orders, GET /api/v1/c/orders/{orderId} (HTTP contract 10 §3.7, C-004 read
 * slice; OpenAPI11 listMyOrders/getMyOrder). Read-only: the caller's own orders only, the sole
 * selectable filter is the domain-computed display tab, the fixed sort is created_at DESC then
 * id DESC, and a non-owner or absent order is a uniform 404 (anti-enumeration). Field
 * projection and the display derivation both stay inside the order module (ARCH-005);
 * Cache-Control: no-store on every reply.
 */
@RestController
@RequestMapping("/api/v1/c/orders")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class COrderQueryController {

    private final OrderQueryApiImpl orders;

    public COrderQueryController(OrderQueryApiImpl orders) {
        this.orders = orders;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String displayStatus,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        if (displayStatus != null
                && !com.petplatform.order.biz.application.OrderDisplayStatus.VALUES.contains(displayStatus)) {
            throw invalid();
        }
        MiniSessionView session = session(req);
        PageResult<com.petplatform.order.api.dto.OrderSnapshotDTO> value = orders.listMyOrders(
                new MyOrderListQuery(
                        displayStatus,
                        parsePage(page),
                        parsePageSize(pageSize),
                        new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream()
                .map(com.petplatform.order.biz.application.OrderSnapshotWire::fields).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String orderId, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        if (orderId == null || !orderId.matches("[1-9][0-9]{0,18}")) throw invalid();
        // PublicId: the lexical pattern plus the positive-Long bound are both 400-level shape.
        try {
            Long.parseLong(orderId);
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
        com.petplatform.order.api.dto.OrderSnapshotDTO value = orders.getOrder(
                new com.petplatform.order.api.query.OrderQueryApi.OrderIdQuery(
                        orderId,
                        new QueryContext(trace(req), OperatorType.USER, session.userId())));
        return ApiResponse.success(
                com.petplatform.order.biz.application.OrderSnapshotWire.fields(value), trace(req));
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return session;
    }

    /** Page has no contract upper bound; the digit guard only keeps it a sane integer. */
    private static int parsePage(String raw) {
        if (raw == null || raw.isEmpty()) return 1;
        if (!raw.matches("[0-9]{1,10}")) throw invalid();
        long value = Long.parseLong(raw);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    /** PageSize follows the OpenAPI 11 PageSize parameter: 1..100, default 20. */
    private static int parsePageSize(String raw) {
        if (raw == null || raw.isEmpty()) return 20;
        if (!raw.matches("[0-9]{1,3}")) throw invalid();
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 100) throw invalid();
        return value;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"displayStatus", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid();
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"displayStatus".equals(name) && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid();
            }
        }
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
