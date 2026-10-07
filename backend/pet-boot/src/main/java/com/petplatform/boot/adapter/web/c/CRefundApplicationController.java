package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.order.biz.application.OrderDisplayStatus;
import com.petplatform.refund.api.command.RefundApplicationCommandApi;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Apply;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 10 §3.9 / OpenAPI11 applyRefund (this slice turns the draft into implemented
 * default-off): the buyer's own ordinary full-refund application over the 49号 kernel. All
 * admission rules — ORDER eligibility, both §3.9 windows (pre-service auto full, post-service
 * merchant 24h), one live application per order, rejected-may-retry, any-source refund_order
 * exclusion — stay inside the kernel; the boundary only builds the trusted USER session
 * context. Strict JSON (unknown field, explicit null on reasonCode, duplicate key and trailing
 * tokens are 400), terminal X-Request-Id UUID on the five-tuple idempotency, 201 first submit /
 * 200 protected replay, and Cache-Control: no-store on every reply. The first receipt is fixed
 * by contract 49 (orderId/applicationId/status/version/deadline/decidedAt/decisionId); the
 * refund_order itself is created later by the durable REFUND_APPLICATION_CREATE task, so
 * refundOrderId is null in this reply and displayStatus states the §3.9 window outcome.
 */
@RestController
@RequestMapping("/api/v1/c/orders/{orderId}/refund-applications")
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "refund.application.http.enabled"},
    havingValue = "true")
public class CRefundApplicationController {

    private static final ObjectReader STRICT =
        com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> APPLY_FIELDS = Set.of("reasonCode", "reasonText");

    private final RefundApplicationCommandApi applications;

    public CRefundApplicationController(RefundApplicationCommandApi applications) {
        this.applications = applications;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable String orderId, @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        JsonNode body = strictObject(raw);
        String reasonCode = requiredText(body, "reasonCode");
        String reasonText = optionalText(body, "reasonText");
        RefundApplicationCommandApi.CreationResult outcome = applications.applyWithOutcome(new Apply(
            new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
                session.userId(), "MINIAPP"),
            id(orderId), reasonCode, reasonText));
        Receipt receipt = outcome.receipt();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", receipt.applicationId());
        data.put("applicationStatus", receipt.applicationStatus());
        data.put("route", route(receipt));
        data.put("merchantDeadline", format(receipt.merchantDeadline()));
        data.put("refundOrderId", null);
        data.put("displayStatus", displayStatus(receipt));
        return ResponseEntity.status(outcome.created() ? 201 : 200).headers(noStore())
            .body(envelope("SUCCESS", "ok", data, trace(req)));
    }

    /** HTTP10/Contract51: the JSON envelope is exactly {code,message,data,traceId} — no success key. */
    private static Map<String, Object> envelope(String code, String message, Object data, String trace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        body.put("traceId", trace);
        return body;
    }

    private static HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
        headers.set("Pragma", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    /** §3.9 window routing; only the two apply-time terminal statuses can reach this reply. */
    private static String route(Receipt receipt) {
        return "AUTO_APPROVED".equals(receipt.applicationStatus()) ? "AUTO_FULL_BEFORE_SERVICE"
            : "MERCHANT_CONFIRM_AFTER_SERVICE";
    }

    /** §3.9 examples: auto full refund is already in flight; merchant window awaits confirmation. */
    private static String displayStatus(Receipt receipt) {
        return "AUTO_APPROVED".equals(receipt.applicationStatus())
            ? OrderDisplayStatus.REFUNDING : OrderDisplayStatus.REFUND_PENDING_CONFIRM;
    }

    /** Contract 49: unknown fields, explicit reasonCode null, duplicate keys, trailing tokens are 400. */
    private static JsonNode strictObject(String raw) {
        try {
            JsonNode root = STRICT.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!APPLY_FIELDS.contains(name)) throw invalid();
                if (root.get(name).isNull() && !"reasonText".equals(name)) throw invalid();
            }
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String requiredText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }

    /** reasonText is the single optional field: absent or explicit null both mean "not supplied". */
    private static String optionalText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }

    private static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        return value;
    }

    private static String format(String value) {
        return value == null ? null
            : new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(java.time.OffsetDateTime.parse(value).toInstant());
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        // Write surface requires ACTIVE; the kernel session authority re-verifies both anyway.
        if (!"ACTIVE".equals(session.userStatus())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "当前账号无法申请退款");
        }
        return session;
    }

    /**
     * Error12 §6 refund-application C surface mapping: COMMON_* keep the global semantics; the
     * 49号 admission rejections (window, live application, existing refund_order) are 409, and
     * the pre-service route staying behind its kernel switch is a 503 fail-closed state. Anything
     * unrecognized is wrapped as a safe 500, never leaking kernel internals.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> failure(RuntimeException failure, HttpServletRequest req) {
        String code = failure instanceof ApiException api ? api.code()
            : failure instanceof IllegalArgumentException ? CommonApiCodes.INVALID_ARGUMENT
            : CommonApiCodes.INTERNAL_ERROR;
        int status = switch (code) {
            case "COMMON_INVALID_ARGUMENT" -> 400;
            case "COMMON_UNAUTHORIZED" -> 401;
            case "COMMON_FORBIDDEN" -> 403;
            case "COMMON_NOT_FOUND", "ORDER_NOT_FOUND" -> 404;
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "ORDER_OPERATION_BUSY",
                 "REFUND_NOT_ELIGIBLE", "REFUND_APPLICATION_ALREADY_PROCESSED",
                 "REFUND_ORDER_ALREADY_EXISTS", "REFUND_ALREADY_EXISTS",
                 "REFUND_MERCHANT_DEADLINE_PASSED" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE", "REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED" -> 503;
            default -> 500;
        };
        if (status == 500) code = CommonApiCodes.INTERNAL_ERROR;
        String message = switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "无权执行该操作";
            case 404 -> "订单不存在";
            case 409 -> "当前订单状态或请求参数冲突，请刷新后核对";
            case 503 -> "服务暂不可用，请稍后重试";
            default -> "服务内部错误，请稍后重试";
        };
        return ResponseEntity.status(status).headers(noStore())
            .body(envelope(code, message, null, trace(req)));
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
