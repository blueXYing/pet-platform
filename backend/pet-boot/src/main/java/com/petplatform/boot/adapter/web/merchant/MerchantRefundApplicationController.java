package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.PageResult;
import com.petplatform.refund.api.command.RefundApplicationCommandApi;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Decide;
import com.petplatform.refund.api.command.RefundApplicationCommandApi.Receipt;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationListQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.StoreApplicationQuery;
import com.petplatform.refund.api.query.MerchantRefundApplicationQueryApi.Summary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 57 merchant refund HTTP face (HTTP10 §4.4/§4.5/§4.6 draft assembled on the
 * contract-49 kernel): pending list, single detail and the two OWNER decisions. The
 * boundary only builds the trusted USER session context, validates the wire shape and maps
 * error codes — admission, five-tuple idempotency, the 24h deadline race and the refund
 * sources stay inside the kernel; approve never means the money moved (the durable
 * REFUND_APPLICATION_CREATE task owns creation). Strict JSON (unknown field, duplicate key
 * and trailing tokens are 400), no query parameters on the commands, fixed
 * expectedApplicationVersion "0" per the contract-49 invariant (PENDING_MERCHANT rows are
 * version 0 and replays answer before the version check), and Cache-Control: no-store.
 */
@RestController
@RequestMapping(value = "/api/v1/merchant/refund-applications", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "pet.refund.merchant.http.enabled", havingValue = "true")
public final class MerchantRefundApplicationController {

    private static final ObjectReader JSON =
        com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

    private final RefundApplicationCommandApi applications;
    private final MerchantRefundApplicationQueryApi queries;

    public MerchantRefundApplicationController(RefundApplicationCommandApi applications,
            MerchantRefundApplicationQueryApi queries) {
        this.applications = applications;
        this.queries = queries;
    }

    @ModelAttribute
    void cache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /** Contract 57 / HTTP10 §4.4: the store's PENDING_MERCHANT backlog, newest first. */
    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String merchantId,
        @RequestParam(required = false) String storeId,
        @RequestParam(required = false) String page,
        @RequestParam(required = false) String pageSize,
        HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId", "page", "pageSize");
        var session = mini(request);
        PageResult<Summary> value = queries.listStoreApplications(new StoreApplicationListQuery(
            id(merchantId), id(storeId), number(page), size(pageSize),
            new com.petplatform.common.QueryContext(trace(request),
                com.petplatform.common.OperatorType.USER, session.userId())));
        List<Map<String, Object>> items = value.items().stream()
            .map(MerchantRefundApplicationController::summary).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return envelope(data, request);
    }

    /** Contract 57: one owned application in any status; foreign/unknown read as 403. */
    @GetMapping("/{applicationId}")
    Map<String, Object> detail(@PathVariable String applicationId,
        @RequestParam(required = false) String merchantId,
        @RequestParam(required = false) String storeId,
        HttpServletRequest request) {
        onlyParameters(request, "merchantId", "storeId");
        var session = mini(request);
        Summary value = queries.readStoreApplication(new StoreApplicationQuery(id(merchantId),
            id(storeId), id(applicationId),
            new com.petplatform.common.QueryContext(trace(request),
                com.petplatform.common.OperatorType.USER, session.userId())));
        Map<String, Object> data = summary(value);
        data.put("decidedAt", time(value.decidedAt()));
        data.put("decisionId", value.decisionId());
        data.put("refundOrderId", value.refundOrderId());
        return envelope(data, request);
    }

    /** Contract 57 / HTTP10 §4.5: full approve, no amount parameter, no business body. */
    @PostMapping("/{applicationId}/approve")
    Map<String, Object> approve(@PathVariable String applicationId,
        @RequestBody(required = false) String raw, HttpServletRequest request) {
        onlyParameters(request);
        mini(request);
        if (raw != null && !raw.isBlank()) {
            JsonNode body = strictObject(raw);
            // An empty JSON object is the only accepted shape (no business fields at all).
            if (body.size() != 0) throw invalid();
        }
        Receipt receipt = applications.decide(new Decide(userCommand(request), id(applicationId),
            "0", "APPROVE", null));
        return envelope(receipt(receipt), request);
    }

    /** Contract 57 / HTTP10 §4.6: reject with a mandatory free-text reason (1..500 code points). */
    @PostMapping("/{applicationId}/reject")
    Map<String, Object> reject(@PathVariable String applicationId,
        @RequestBody(required = false) String raw, HttpServletRequest request) {
        onlyParameters(request);
        mini(request);
        String reasonText = reasonText(raw);
        Receipt receipt = applications.decide(new Decide(userCommand(request), id(applicationId),
            "0", "REJECT", reasonText));
        return envelope(receipt(receipt), request);
    }

    private static Map<String, Object> summary(Summary item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("applicationId", item.applicationId());
        row.put("applicationNo", item.applicationNo());
        row.put("orderId", item.orderId());
        row.put("status", item.status());
        row.put("applicationVersion", item.applicationVersion());
        row.put("reasonCode", item.reasonCode());
        row.put("refundAmount", amount(item.refundAmount()));
        row.put("createdAt", time(item.createdAt()));
        row.put("merchantDeadline", time(item.merchantDeadline()));
        return row;
    }

    /** The fixed contract-49 receipt fields, wire-rendered (ids/versions/times as strings). */
    private static Map<String, Object> receipt(Receipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", receipt.orderId());
        data.put("applicationId", receipt.applicationId());
        data.put("applicationStatus", receipt.applicationStatus());
        data.put("applicationVersion", receipt.applicationVersion());
        data.put("merchantDeadline", instant(receipt.merchantDeadline()));
        data.put("decidedAt", instant(receipt.decidedAt()));
        data.put("decisionId", receipt.decisionId());
        return data;
    }

    /** Kernel receipt instants render at millisecond precision like the C face (contract 49). */
    private static String instant(String value) {
        return value == null ? null
            : new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(OffsetDateTime.parse(value).toInstant());
    }

    /** 11号 DecimalAmountOutput: always exactly two decimals (same rendering as the order list). */
    private static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Page guard: digit-only, >=1 (contract bounds live in the refund read module). */
    private static int number(String raw) {
        if (raw == null || raw.isEmpty()) return 1;
        if (!raw.matches("[0-9]{1,10}")) throw invalid();
        long value = Long.parseLong(raw);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    /** PageSize follows the §3.7/§4.1 precedent: 1..100, default 20. */
    private static int size(String raw) {
        if (raw == null || raw.isEmpty()) return 20;
        if (!raw.matches("[0-9]{1,3}")) throw invalid();
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 100) throw invalid();
        return value;
    }

    /** Contract 57: unknown fields, duplicate keys, trailing tokens and non-objects are 400. */
    private static JsonNode strictObject(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Mandatory reject reason: absent/null/non-textual/blank is 400 REFUND_MERCHANT_REASON_REQUIRED. */
    private static String reasonText(String raw) {
        JsonNode body = null;
        if (raw != null && !raw.isBlank()) body = strictObject(raw);
        JsonNode value = body == null ? null : body.get("reasonText");
        if (value == null || value.isNull() || !value.isTextual()) throw reasonRequired();
        for (String name : body.propertyNames()) {
            if (!"reasonText".equals(name)) throw invalid();
        }
        String text = value.asText();
        if (text.isBlank()) throw reasonRequired();
        int points = text.codePointCount(0, text.length());
        if (points < 1 || points > 500) throw invalid();
        if (text.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) throw invalid();
        return text;
    }

    private static ApiException reasonRequired() {
        return new ApiException("REFUND_MERCHANT_REASON_REQUIRED", "商家拒绝必须填写原因");
    }

    /**
     * Contract 57 error surface: 12号 §6 refund codes keep their registry semantics
     * (deadline/system takeover and processed conflicts are 409; a missing reject reason is
     * 400). Anything unrecognized is wrapped as a safe 500, never leaking kernel internals.
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> failure(RuntimeException failure, HttpServletRequest req) {
        String code = failure instanceof ApiException api ? api.code()
            : failure instanceof IllegalArgumentException ? CommonApiCodes.INVALID_ARGUMENT
            : CommonApiCodes.INTERNAL_ERROR;
        int status = switch (code) {
            case "COMMON_INVALID_ARGUMENT", "REFUND_MERCHANT_REASON_REQUIRED" -> 400;
            case "COMMON_UNAUTHORIZED" -> 401;
            case "COMMON_FORBIDDEN" -> 403;
            case "COMMON_NOT_FOUND", "REFUND_APPLICATION_NOT_FOUND" -> 404;
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "ORDER_OPERATION_BUSY",
                 "REFUND_NOT_ELIGIBLE", "REFUND_APPLICATION_ALREADY_PROCESSED",
                 "REFUND_ORDER_ALREADY_EXISTS", "REFUND_ALREADY_EXISTS",
                 "REFUND_MERCHANT_DEADLINE_PASSED" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
            default -> 500;
        };
        if (status == 500) code = CommonApiCodes.INTERNAL_ERROR;
        String message = switch (status) {
            case 400 -> "REFUND_MERCHANT_REASON_REQUIRED".equals(code) ? "商家拒绝必须填写原因" : "请求参数不合法";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "无权执行该操作";
            case 404 -> "退款申请不存在";
            case 409 -> "当前退款申请状态或期限冲突，请刷新后核对";
            case 503 -> "服务暂不可用，请稍后重试";
            default -> "服务内部错误，请稍后重试";
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        body.put("traceId", trace(req));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
        headers.set("Pragma", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
