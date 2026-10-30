package com.petplatform.boot.adapter.web.marketing;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.marketing.api.MarketingAdminApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/** Boundary helpers for the marketing three-face controllers (contract 71). */
public final class MarketingHttpSupport {

    private static final tools.jackson.databind.ObjectReader STRICT =
            com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();

    private MarketingHttpSupport() {}

    public static AdminSessionView admin(HttpServletRequest req, String action) {
        if (java.util.Collections.list(req.getHeaders("Authorization")).size() != 1) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (!(req.getAttribute(AdminBearerAuthenticationFilter.VIEW) instanceof AdminSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (!"ADMIN_WEB".equals(session.principal().audience())
                || !session.permissions().actionCodes().contains(action)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
        return session;
    }

    public static MarketingAdminApi.AdminAuthorization adminAuth(AdminSessionView session) {
        var scope = session.permissions().dataScope();
        return new MarketingAdminApi.AdminAuthorization(
                session.principal().sessionId(), session.principal().sessionGeneration(),
                session.principal().operatorId(), scope.mode(), scope.cityCodes(), scope.merchantIds());
    }

    public static QueryContext adminQuery(HttpServletRequest req, AdminSessionView session) {
        return new QueryContext(trace(req), OperatorType.PLATFORM_OPERATOR,
                session.principal().operatorId());
    }

    public static CommandContext adminCommand(HttpServletRequest req, AdminSessionView session) {
        return new CommandContext(requestId(req), trace(req), OperatorType.PLATFORM_OPERATOR,
                session.principal().operatorId(), "ADMIN_WEB");
    }

    public static MiniSessionView mini(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (view instanceof MiniSessionView session) return session;
        throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    public static QueryContext userQuery(HttpServletRequest req) {
        return new QueryContext(trace(req), OperatorType.USER, mini(req).userId());
    }

    public static CommandContext userCommand(HttpServletRequest req) {
        return new CommandContext(requestId(req), trace(req), OperatorType.USER,
                mini(req).userId(), "MINIAPP");
    }

    public static String requestId(HttpServletRequest req) {
        try {
            return PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static String trace(HttpServletRequest req) {
        String mdc = MDC.get(TraceContextFilter.TRACE_MDC_KEY);
        if (mdc != null && !mdc.isBlank()) return mdc;
        String header = req.getHeader(TraceContextFilter.TRACE_HEADER);
        String trace = header != null && header.matches("[A-Za-z0-9._:-]{1,64}")
                ? header : UUID.randomUUID().toString();
        req.setAttribute("marketingTraceId", trace);
        return trace;
    }

    public static JsonNode body(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        try {
            return STRICT.readTree(raw);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static void onlyParameters(HttpServletRequest req, String... names) {
        Set<String> allowed = Set.of(names);
        if (!allowed.containsAll(req.getParameterMap().keySet())) throw invalid();
        for (String name : names) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length != 1) throw invalid();
        }
    }

    public static void noQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
    }

    public static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }

    public static String optionalText(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }

    public static int integer(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asInt();
    }

    public static long longValue(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    public static java.math.BigDecimal decimal(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isNumber()) throw invalid();
        return value.decimalValue();
    }

    public static OffsetDateTime time(JsonNode body, String field) {
        String raw = text(body, field);
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static String time(OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }

    /** Parse a stored JSON column for the wire; malformed legacy data degrades to null. */
    public static Object jsonObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return STRICT.readTree(json);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        return value;
    }

    public static int page(String value) {
        if (value == null || value.isEmpty()) return 1;
        if (!value.matches("[0-9]{1,5}")) throw invalid();
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 10_000) throw invalid();
        return parsed;
    }

    public static int pageSize(String value) {
        if (value == null || value.isEmpty()) return 20;
        if (!value.matches("[0-9]{1,3}")) throw invalid();
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 100) throw invalid();
        return parsed;
    }

    public static <T> ApiResponse<T> ok(T data, HttpServletRequest req) {
        return ApiResponse.success(data, trace(req));
    }

    /** Merchant mini-program faces use the historical {success,...} envelope. */
    public static Map<String, Object> merchantOk(Object data, HttpServletRequest req) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("success", true);
        out.put("code", "SUCCESS");
        out.put("message", "成功");
        out.put("data", data);
        out.put("traceId", trace(req));
        return out;
    }

    public static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
