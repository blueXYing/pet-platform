package com.petplatform.boot.adapter.web.review;

import static com.petplatform.boot.adapter.web.review.ReviewAppealHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract56 boundary helpers: route identity, bounded strict input and safe errors. The
 * merchant face rides the MINIAPP Bearer (OWNER admission re-proven in the kernel under the
 * store guard); the admin face rides the ADMIN_WEB Bearer with the review.appeal.* action
 * codes re-checked by the kernel on every call and replay.
 */
final class ReviewAppealHttpSupport {
    private static final ObjectReader JSON = MerchantJsonReaderFactory.strictReader();

    private ReviewAppealHttpSupport() {}

    static boolean adminParty(HttpServletRequest r) {
        return r.getRequestURI().startsWith("/api/v1/admin/");
    }

    /** Write context: unique terminal X-Request-Id UUID on both faces; admin writes also
     *  pre-check the session's action list (the kernel re-checks the real decision). */
    static CommandContext context(HttpServletRequest r, boolean write, String action) {
        if (Collections.list(r.getHeaders("Authorization")).size() != 1) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
        }
        String requestId = null;
        if (write) {
            if (Collections.list(r.getHeaders("X-Request-Id")).size() != 1) throw invalid();
            try {
                requestId = PublicContractChecks.requireTerminalRequestId(r.getHeader("X-Request-Id"));
            } catch (IllegalArgumentException bad) {
                throw invalid();
            }
        }
        if (adminParty(r)) {
            if (!(r.getAttribute(AdminBearerAuthenticationFilter.VIEW)
                    instanceof AdminSessionView s)) {
                throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
            }
            if (!"ADMIN_WEB".equals(s.principal().audience())
                    || action != null && !s.permissions().actionCodes().contains(action)) {
                throw new ApiException(CommonApiCodes.FORBIDDEN, "Action unavailable");
            }
            return new CommandContext(requestId, trace(r), OperatorType.PLATFORM_OPERATOR,
                    s.principal().operatorId(), "ADMIN_WEB");
        }
        if (!(r.getAttribute(CBearerSessionFilter.VIEW) instanceof MiniSessionView s)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
        }
        if (write && !"ACTIVE".equals(s.userStatus())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "Action unavailable");
        }
        return new CommandContext(requestId, trace(r), OperatorType.USER, s.userId(), "MINIAPP");
    }

    /** Strict JSON object with the exact allowed string fields (contract 56 §3). */
    static JsonNode body(HttpServletRequest r, Set<String> fields) {
        onlyParameters(r);
        try {
            var content = MediaType.parseMediaType(r.getContentType());
            if (!"application".equalsIgnoreCase(content.getType())
                    || !"json".equalsIgnoreCase(content.getSubtype())) throw invalid();
            byte[] raw = r.getInputStream().readNBytes(32769);
            if (raw.length == 0 || raw.length > 32768) throw invalid();
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!fields.contains(name)) throw invalid();
            }
            return root;
        } catch (ApiException known) {
            throw known;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    /** reason: required textual 1..1000 code points, no lone surrogates (Schema06 bound). */
    static String reason(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        String text = value.asString();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 1000
                || text.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) throw invalid();
        return text;
    }

    static String oneOf(JsonNode root, String name, String... values) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        for (String candidate : values) {
            if (candidate.contentEquals(value.asString())) return candidate;
        }
        throw invalid();
    }

    static void onlyParameters(HttpServletRequest r, String... names) {
        var allowed = Set.of(names);
        if (!allowed.containsAll(r.getParameterMap().keySet())) throw invalid();
        for (String name : names) {
            if (r.getParameterValues(name) != null && r.getParameterValues(name).length != 1) {
                throw invalid();
            }
        }
    }

    static void noBody(HttpServletRequest r) {
        try {
            if (r.getInputStream().read() != -1) throw invalid();
        } catch (java.io.IOException failure) {
            throw invalid();
        }
    }

    static String id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")
                    || Long.parseLong(value) <= 0) throw invalid();
            return value;
        } catch (NumberFormatException bad) {
            throw invalid();
        }
    }

    static int number(String value, int fallback, int max) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[1-9][0-9]{0,4}")) throw invalid();
            int n = Integer.parseInt(value);
            if (n > max) throw invalid();
            return n;
        } catch (NumberFormatException bad) {
            throw invalid();
        }
    }

    static Map<String, Object> envelope(String code, String message, Object data, String trace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        body.put("traceId", trace);
        return body;
    }

    static HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
        headers.set("Pragma", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    static String trace(HttpServletRequest r) {
        String s = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
        if (s != null && !s.isBlank()) return s;
        Object existing = r.getAttribute("reviewAppealTraceId");
        if (existing instanceof String t) return t;
        s = UUID.randomUUID().toString();
        r.setAttribute("reviewAppealTraceId", s);
        return s;
    }

    /** Error12 §11 appeal mapping (contract 56 §4): no new codes, safe messages only. */
    static ResponseEntity<Map<String, Object>> error(RuntimeException e, HttpServletRequest r) {
        String code = e instanceof ApiException api ? api.code()
                : e instanceof IllegalArgumentException ? CommonApiCodes.INVALID_ARGUMENT
                : CommonApiCodes.INTERNAL_ERROR;
        int status = switch (code) {
            case "COMMON_INVALID_ARGUMENT" -> 400;
            case "COMMON_UNAUTHORIZED" -> 401;
            case "COMMON_FORBIDDEN" -> 403;
            case "COMMON_NOT_FOUND", "REVIEW_NOT_FOUND", "ORDER_NOT_FOUND" -> 404;
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "REVIEW_APPEAL_ALREADY_USED" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
            default -> 500;
        };
        if (status == 500) code = CommonApiCodes.INTERNAL_ERROR;
        String message = switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "无权执行该操作";
            case 404 -> "评价或申诉不存在";
            case 409 -> "该评价已申诉或申诉已裁决，不可重复操作";
            case 503 -> "服务暂不可用，请稍后重试";
            default -> "服务内部错误，请稍后重试";
        };
        return ResponseEntity.status(status).headers(noStore())
                .body(envelope(code, message, null, trace(r)));
    }

    static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "Invalid review appeal input");
    }
}
