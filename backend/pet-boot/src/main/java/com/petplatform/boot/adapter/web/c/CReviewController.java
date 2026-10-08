package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.ReviewEligibilityDTO;
import com.petplatform.review.api.command.ReviewCommandApi;
import com.petplatform.review.api.query.ReviewQueryApi;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 10 §3.14 / OpenAPI11 getReviewEligibility + createReview (REV-001 slice turns the
 * drafts into implemented default-off). The GET is the eligibility composition the review
 * kernel owns: ORDER-domain §7.7 facts plus the one-review-per-order overlay; the POST is the
 * buyer's own review over the same kernel. Strict JSON (unknown field, duplicate key and
 * trailing tokens are 400), terminal X-Request-Id UUID on the five-tuple idempotency, 201
 * first submit / 200 protected replay, and Cache-Control: no-store on every reply. The three
 * dimension scores are the contract's 1..5 integers — the 40/40/20 composite is a kernel
 * fact, never a client claim; the fixed receipt is {reviewId, scoreIncluded}.
 */
@RestController
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "review.http.enabled"},
    havingValue = "true")
public class CReviewController {

    private static final ObjectReader STRICT =
        com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> CREATE_FIELDS =
        Set.of("storeScore", "serviceScore", "staffScore", "content", "mediaFileIds");

    private final ReviewQueryApi reviews;
    private final ReviewCommandApi commands;

    public CReviewController(ReviewQueryApi reviews, ReviewCommandApi commands) {
        this.reviews = reviews;
        this.commands = commands;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @GetMapping(value = "/api/v1/c/orders/{orderId}/review-eligibility",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> eligibility(
            @PathVariable String orderId, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        ReviewEligibilityDTO value = reviews.checkEligibility(new ReviewQueryApi.ReviewEligibilityQuery(
            id(orderId), new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eligible", value.eligible());
        data.put("scoreIncluded", value.scoreIncluded());
        data.put("reviewDeadline", format(value.reviewDeadline()));
        data.put("rejectCode", value.rejectCode());
        return ResponseEntity.ok().headers(noStore())
            .body(envelope("SUCCESS", "ok", data, trace(req)));
    }

    @PostMapping(value = "/api/v1/c/orders/{orderId}/reviews",
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String orderId, @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        JsonNode body = strictObject(raw);
        ReviewCommandApi.CreationOutcome outcome = commands.createWithOutcome(new ReviewCommandApi.ReviewCreateCommand(
            new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
                session.userId(), "MINIAPP"),
            id(orderId),
            score(body, "storeScore"), score(body, "serviceScore"), score(body, "staffScore"),
            optionalText(body, "content"),
            mediaFileIds(body)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reviewId", outcome.result().reviewId());
        data.put("scoreIncluded", outcome.result().scoreIncluded());
        return ResponseEntity.status(outcome.created() ? 201 : 200).headers(noStore())
            .body(envelope("SUCCESS", "ok", data, trace(req)));
    }

    /** HTTP10/Contract51: the JSON envelope is exactly {code,message,data,traceId}. */
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

    /** Contract shape: three required integer scores; unknown fields and nulls are 400. */
    private static JsonNode strictObject(String raw) {
        try {
            JsonNode root = STRICT.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!CREATE_FIELDS.contains(name)) throw invalid();
            }
            for (String required : new String[] {"storeScore", "serviceScore", "staffScore"}) {
                JsonNode value = root.get(required);
                if (value == null || !value.isNumber() || !value.isIntegralNumber())
                    throw invalid();
            }
            return root;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Integral JSON number within the contract's 1..5; booleans/floats/strings are 400. */
    private static int score(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        long score = value.longValue();
        if (score < 1 || score > 5) throw invalid();
        return (int) score;
    }

    /** content is the single optional text: absent or explicit null both mean "not supplied". */
    private static String optionalText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        String text = value.asText();
        if (text.codePointCount(0, text.length()) > 2000) throw invalid();
        return text;
    }

    /** The media capability is not open in this slice: the field may be absent, null or empty. */
    private static java.util.List<String> mediaFileIds(JsonNode body) {
        JsonNode value = body.get("mediaFileIds");
        if (value == null || value.isNull()) return java.util.List.of();
        if (!value.isArray()) throw invalid();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !item.asText().matches("[1-9][0-9]{0,18}")) throw invalid();
            ids.add(item.asText());
        }
        return ids;
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

    private static String format(java.time.OffsetDateTime value) {
        return value == null ? null
            : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        // Write surface requires ACTIVE; the kernel re-proves the subject on every replay too.
        if (!"ACTIVE".equals(session.userStatus())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "当前账号无法提交评价");
        }
        return session;
    }

    /**
     * Error12 §11 C-side mapping: the REVIEW_* admission rejections (not verified, window
     * expired, already reviewed, refund-excluded per the 2026-10-07 ruling) are 409;
     * COMMON_NOT_FOUND keeps the order read's anti-enumeration 404; anything unrecognized is
     * wrapped as a safe 500, never leaking kernel internals.
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
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT",
                 "REVIEW_NOT_ELIGIBLE", "REVIEW_NOT_VERIFIED",
                 "REVIEW_WINDOW_EXPIRED", "REVIEW_ALREADY_EXISTS" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
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
