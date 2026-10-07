package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.order.api.command.OrderRescheduleApi;
import com.petplatform.order.api.command.OrderRescheduleApi.Command;
import com.petplatform.order.api.command.OrderRescheduleApi.Receipt;
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
 * Contract 10 §3.8 / Contract 46 / OpenAPI11 rescheduleOrder (this slice turns the frozen
 * NOT_IMPLEMENTED draft into the implemented default-off C face): the buyer's one reschedule
 * over the 46号 kernel. Every admission rule — the single chance (reschedule_count), before the
 * original appointment start, PENDING_CONFIRM/PENDING_SERVICE + PAID + UNVERIFIED, any-source
 * refund_order exclusion, unchanged-interval conflict without consuming the chance, capacity
 * and staff proof, the real verification-credential fence, the round-1 auto-confirm task —
 * stays inside the kernel; the boundary only builds the trusted USER session context. Strict
 * JSON (unknown fields, explicit nulls, duplicate keys and trailing tokens are 400), the
 * OpenAPI oneOf branch shape (in-store times xor pickup pair, never mixed), terminal
 * X-Request-Id UUID on the five-tuple idempotency, one 200 receipt for both the first submit
 * and the protected replay, and Cache-Control: no-store on every reply. The receipt is fixed
 * by contract 46 (orderId/reservationId/rescheduleId/confirmRound=1/orderVersion/
 * orderStageAtCommit=PENDING_CONFIRM plus the new and old-window times); no user-sensitive
 * fields. Errors follow Error 12 §6 with the §3.8 reschedule codes as 409.
 */
@RestController
@RequestMapping("/api/v1/c/orders/{orderId}/reschedule")
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "order.reschedule.http.enabled"},
    havingValue = "true")
public class COrderRescheduleController {

    private static final ObjectReader STRICT =
        com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> FIELDS = Set.of("expectedOrderVersion",
        "appointmentStart", "appointmentEnd", "selectedGeneralWindowId",
        "pickupStart", "returnStart", "selectedPickupWindowId", "selectedReturnWindowId");
    private static final Set<String> STORE_FIELDS = Set.of("appointmentStart",
        "appointmentEnd", "selectedGeneralWindowId");
    private static final Set<String> PICKUP_FIELDS = Set.of("pickupStart", "returnStart",
        "selectedPickupWindowId", "selectedReturnWindowId");
    private static final String VERSION = "^(0|[1-9][0-9]*)$";

    private final OrderRescheduleApi reschedules;

    public COrderRescheduleController(OrderRescheduleApi reschedules) {
        this.reschedules = reschedules;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> reschedule(
            @PathVariable String orderId, @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        JsonNode body = strictObject(raw);
        String version = requiredText(body, "expectedOrderVersion");
        if (!version.matches(VERSION)) throw invalid();
        // OpenAPI oneOf: exactly one branch, and the other branch's fields must be absent.
        boolean store = present(body, "appointmentStart");
        boolean pickup = present(body, "pickupStart");
        if (store == pickup) throw invalid();
        Command command = store ? storeCommand(orderId, session, req, body, version)
                : pickupCommand(orderId, session, req, body, version);
        Receipt receipt = reschedules.reschedule(command);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", receipt.orderId());
        data.put("reservationId", receipt.reservationId());
        data.put("rescheduleId", receipt.rescheduleId());
        data.put("confirmRound", receipt.confirmRound());
        data.put("orderVersion", receipt.orderVersion());
        data.put("orderStageAtCommit", receipt.orderStageAtCommit());
        data.put("appointmentStart", format(receipt.appointmentStart()));
        data.put("appointmentEnd", format(receipt.appointmentEnd()));
        data.put("pickupStart", format(receipt.pickupStart()));
        data.put("returnStart", format(receipt.returnStart()));
        data.put("rescheduledAt", format(receipt.rescheduledAt()));
        data.put("confirmDeadline", format(receipt.confirmDeadline()));
        return ResponseEntity.ok().headers(noStore())
            .body(envelope("SUCCESS", "ok", data, trace(req)));
    }

    private static Command storeCommand(String orderId, MiniSessionView session,
            HttpServletRequest req, JsonNode body, String version) {
        for (String name : STORE_FIELDS) if (!present(body, name)) throw invalid();
        for (String name : PICKUP_FIELDS) if (present(body, name)) throw invalid();
        return new Command(context(session, req), id(orderId), version,
            time(body, "appointmentStart"), time(body, "appointmentEnd"), null, null,
            id(text(body, "selectedGeneralWindowId")), null, null);
    }

    private static Command pickupCommand(String orderId, MiniSessionView session,
            HttpServletRequest req, JsonNode body, String version) {
        for (String name : PICKUP_FIELDS) if (!present(body, name)) throw invalid();
        for (String name : STORE_FIELDS) if (present(body, name)) throw invalid();
        return new Command(context(session, req), id(orderId), version, null, null,
            time(body, "pickupStart"), time(body, "returnStart"), null,
            id(text(body, "selectedPickupWindowId")), id(text(body, "selectedReturnWindowId")));
    }

    private static CommandContext context(MiniSessionView session, HttpServletRequest req) {
        return new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
            session.userId(), "MINIAPP");
    }

    /** HTTP10/Contract51 envelope: exactly {code,message,data,traceId} — no success key. */
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

    /** Contract 46: unknown fields, any explicit null, duplicates and trailing tokens are 400. */
    private static JsonNode strictObject(String raw) {
        try {
            JsonNode root = STRICT.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!FIELDS.contains(name) || root.get(name).isNull()) throw invalid();
            }
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static boolean present(JsonNode body, String name) {
        return body.get(name) != null;
    }

    private static String requiredText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static OffsetDateTime time(JsonNode body, String name) {
        try {
            return OffsetDateTime.parse(text(body, name));
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
        return value;
    }

    private static String format(String value) {
        return value == null ? null
            : new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(OffsetDateTime.parse(value).toInstant());
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        // Write surface requires ACTIVE; the kernel session authority re-verifies it anyway.
        if (!"ACTIVE".equals(session.userStatus())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "当前账号无法改期");
        }
        return session;
    }

    /**
     * Error12 §6 reschedule C surface mapping: COMMON_* keep the global semantics; the 46号/§3.8
     * admission rejections (limit reached, past start, state, refund_order, unchanged intervals,
     * wrong version, idempotency conflict, capacity/swap) are 409; missing owners are 503.
     * Anything unrecognized is wrapped as a safe 500, never leaking kernel internals.
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
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "ORDER_OPERATION_BUSY",
                 "ORDER_RESCHEDULE_LIMIT_REACHED", "ORDER_RESCHEDULE_AFTER_START",
                 "ORDER_STATE_NOT_ALLOWED", "ORDER_REFUND_ALREADY_CREATED",
                 "SCHEDULE_SWAP_FAILED", "SCHEDULE_CAPACITY_EXCEEDED" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
            default -> 500;
        };
        if (status == 500) code = CommonApiCodes.INTERNAL_ERROR;
        String message = switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "无权执行该操作";
            case 409 -> "当前订单状态或时段冲突，请刷新后核对";
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
