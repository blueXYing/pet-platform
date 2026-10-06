package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import com.petplatform.verification.api.command.VerificationCredentialApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 47 §4 reserved C routes (this slice turns NOT_IMPLEMENTED into implemented
 * default-off): GET reads the current credential view, POST issues/refreshes one. The owner is
 * always the current session user; refreshKind is the client's explicit intent
 * (INITIAL/AUTO/MANUAL) checked against the kernel rules. Strict JSON (unknown field, explicit
 * null, duplicate key and trailing tokens are all 400), X-Request-Id terminal UUID on POST,
 * five-tuple idempotency inside the kernel, and Cache-Control: no-store on every reply — the
 * code must never be cached or logged.
 */
@RestController
@RequestMapping("/api/v1/c/orders/{orderId}/verification-code")
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "verification.credential.http.enabled"},
    havingValue = "true")
public class CVerificationCredentialController {

    private static final ObjectReader STRICT =
        com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private static final Set<String> ISSUE_FIELDS = Set.of("expectedCredentialVersion", "refreshKind");

    private final VerificationCredentialApi credentials;

    public CVerificationCredentialController(VerificationCredentialApi credentials) {
        this.credentials = credentials;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> read(@PathVariable String orderId, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        VerificationCredentialApi.View view = credentials.read(
            id(orderId),
            new QueryContext(trace(req), OperatorType.USER, session.userId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", view.orderId());
        data.put("credentialVersion", view.credentialVersion());
        data.put("status", view.status());
        data.put("code", view.code());
        data.put("expiresAt", format(view.expiresAt()));
        data.put("refreshAfter", format(view.refreshAfter()));
        data.put("lockedUntil", format(view.lockedUntil()));
        return ApiResponse.success(data, trace(req));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> issue(
            @PathVariable String orderId, @RequestBody String raw, HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid();
        MiniSessionView session = session(req);
        JsonNode body = strictObject(raw);
        String version = requiredText(body, "expectedCredentialVersion");
        if (!version.matches("^(0|[1-9][0-9]{0,18})$")) throw invalid();
        String kind = requiredText(body, "refreshKind");
        if (!Set.of("INITIAL", "AUTO", "MANUAL").contains(kind)) throw invalid();
        VerificationCredentialApi.Receipt receipt = credentials.issue(new VerificationCredentialApi.Issue(
            new CommandContext(CShared.requestId(req), trace(req), OperatorType.USER,
                session.userId(), "MINIAPP"),
            id(orderId), version, kind));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", receipt.orderId());
        data.put("credentialId", receipt.credentialId());
        data.put("credentialVersion", receipt.credentialVersion());
        data.put("code", receipt.code());
        data.put("issuedAt", format(receipt.issuedAt()));
        data.put("expiresAt", format(receipt.expiresAt()));
        data.put("refreshAfter", format(receipt.refreshAfter()));
        return ApiResponse.success(data, trace(req));
    }

    /** Contract 47: unknown fields, explicit nulls, duplicate keys and trailing tokens are 400. */
    private static JsonNode strictObject(String raw) {
        try {
            JsonNode root = STRICT.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            for (String name : root.propertyNames()) {
                if (!ISSUE_FIELDS.contains(name)) throw invalid();
                if (root.get(name).isNull()) throw invalid();
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
        return session;
    }

    private static ApiException invalid() {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
}
