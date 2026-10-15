package com.petplatform.boot.adapter.web.admin;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * Contract-63 boundary helpers: route identity, bounded strict query input, decimal strings and
 * safe errors for the GET-only platform trade read face (HTTP10 §5.11). Same discipline as the
 * aftersale admin face: single Authorization header, ADMIN_WEB audience, snapshot action check
 * plus the ALL data-scope gate, unknown query parameters rejected.
 */
final class AdminTradeHttpSupport {
    private static final tools.jackson.databind.ObjectReader JSON =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private AdminTradeHttpSupport() {}

    static AdminSessionView session(HttpServletRequest r, String action) {
        if (java.util.Collections.list(r.getHeaders("Authorization")).size() != 1) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
        }
        if (!(r.getAttribute(AdminBearerAuthenticationFilter.VIEW) instanceof AdminSessionView s)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
        }
        if (!"ADMIN_WEB".equals(s.principal().audience())
                || !s.permissions().actionCodes().contains(action)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "Action unavailable");
        }
        // Contract 63 v0.1: trade rows carry no uniform city/merchant attribution, so only the
        // ALL scope reads them (fail-closed for CITY/MERCHANT; CCR-PERM-001 §3).
        if (!"ALL".equals(s.permissions().dataScope().mode())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "Scope unavailable");
        }
        return s;
    }

    static void onlyParameters(HttpServletRequest r, String... names) {
        var allowed = Set.of(names);
        if (!allowed.containsAll(r.getParameterMap().keySet())) throw invalid();
        for (var name : names) {
            if (r.getParameterValues(name) != null && r.getParameterValues(name).length != 1) {
                throw invalid();
            }
        }
    }

    static String id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException bad) {
            throw invalid();
        }
    }

    static String optionalId(String value) {
        return value == null ? null : id(value);
    }

    static int page(String value) {
        return value == null ? 1 : bounded(value, 1, 10000);
    }

    static int pageSize(String value) {
        return value == null ? 20 : bounded(value, 1, 100);
    }

    private static int bounded(String value, int min, int max) {
        try {
            if (!value.matches("[1-9][0-9]{0,5}")) throw invalid();
            int n = Integer.parseInt(value);
            if (n < min || n > max) throw invalid();
            return n;
        } catch (NumberFormatException bad) {
            throw invalid();
        }
    }

    static boolean flag(String value) {
        if (value == null) return false;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw invalid();
    }

    static String keyword(String value) {
        if (value == null) return null;
        int length = value.codePointCount(0, value.length());
        if (length < 2 || length > 64 || value.isBlank()
                || value.indexOf('%') >= 0 || value.indexOf('_') >= 0 || value.indexOf('\\') >= 0) {
            throw invalid();
        }
        return value;
    }

    static OffsetDateTime instant(String value) {
        if (value == null) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            PublicContractChecks.requireMillisecondPrecision(parsed);
            return parsed;
        } catch (RuntimeException bad) {
            throw invalid();
        }
    }

    static String time(OffsetDateTime value) {
        return value == null
                ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    static Map<String, Object> ok(Object data, HttpServletRequest r) {
        return body("SUCCESS", "ok", data, trace(r));
    }

    /**
     * Contract-63 v0.2 disposal writes: one terminal X-Request-Id header (23号幂等契约), no
     * query string parameters and an empty body where the draft defines none.
     */
    static String requestId(HttpServletRequest r) {
        if (r.getParameterMap() != null && !r.getParameterMap().isEmpty()) throw invalid();
        if (java.util.Collections.list(r.getHeaders("X-Request-Id")).size() != 1) throw invalid();
        try {
            return PublicContractChecks.requireTerminalRequestId(r.getHeader("X-Request-Id"));
        } catch (IllegalArgumentException bad) {
            throw invalid();
        }
    }

    static void noBody(HttpServletRequest r) {
        try {
            if (r.getInputStream().read() != -1) throw invalid();
        } catch (java.io.IOException failure) {
            throw invalid();
        }
    }

    /** Bounded strict JSON object body with string-only fields (mirrors the aftersale boundary). */
    static Map<String, String> stringBody(HttpServletRequest r, String... fields) {
        try {
            var content = MediaType.parseMediaType(r.getContentType());
            if (!"application".equalsIgnoreCase(content.getType())
                    || !"json".equalsIgnoreCase(content.getSubtype())) throw invalid();
            byte[] raw = r.getInputStream().readNBytes(32769);
            if (raw.length == 0 || raw.length > 32768) throw invalid();
            var root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            var allowed = Set.of(fields);
            var values = new LinkedHashMap<String, String>();
            for (var field : root.properties()) {
                if (!allowed.contains(field.getKey()) || !field.getValue().isNull()
                        && !field.getValue().isTextual()) throw invalid();
                if (!field.getValue().isNull()) values.put(field.getKey(), field.getValue().textValue());
            }
            return values;
        } catch (ApiException known) {
            throw known;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    static Map<String, Object> error(RuntimeException e, HttpServletRequest r) {
        String code = e instanceof ApiException a
                ? a.code()
                : e instanceof IllegalArgumentException ? CommonApiCodes.INVALID_ARGUMENT : CommonApiCodes.INTERNAL_ERROR;
        int status = switch (code) {
            case "COMMON_INVALID_ARGUMENT" -> 400;
            case "COMMON_UNAUTHORIZED" -> 401;
            case "COMMON_FORBIDDEN" -> 403;
            case "COMMON_NOT_FOUND" -> 404;
            case "COMMON_CONFLICT", "IDEMPOTENCY_KEY_CONFLICT", "ORDER_STATE_NOT_ALLOWED",
                 "ORDER_REFUND_ALREADY_CREATED", "ORDER_OPERATION_BUSY", "ORDER_CONFIRM_DEADLINE_PASSED" -> 409;
            case "COMMON_DEPENDENCY_UNAVAILABLE" -> 503;
            default -> 500;
        };
        if (status == 500) code = CommonApiCodes.INTERNAL_ERROR;
        String message = switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "无权执行该操作";
            case 404 -> "资源不存在";
            case 409 -> "当前状态或请求参数冲突，请刷新后核对";
            case 503 -> "服务暂不可用，请稍后重试";
            default -> "内部错误，请稍后重试";
        };
        return body(code, message, null, trace(r));
    }

    static Map<String, Object> body(String code, String message, Object data, String traceId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("message", message);
        envelope.put("data", data);
        envelope.put("traceId", traceId);
        return envelope;
    }

    static String trace(HttpServletRequest r) {
        String mdc = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
        if (mdc != null && !mdc.isBlank()) return mdc;
        Object existing = r.getAttribute("adminTradeTraceId");
        if (existing instanceof String s) return s;
        String generated = UUID.randomUUID().toString();
        r.setAttribute("adminTradeTraceId", generated);
        return generated;
    }

    static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "Invalid trade query input");
    }
}
