package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.requestId;
import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.notification.api.command.NotificationPreferenceApi;
import com.petplatform.notification.api.command.NotificationPreferenceApi.UpdatePreferenceCommand;
import com.petplatform.notification.api.dto.NotificationTypes.PreferenceView;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * GET/PUT /api/v1/c/notification-preferences (HTTP10 §3.15.3, SSOT §16.4): the receiver is
 * always the current session user. Exactly two switches exist (ordinary interaction reminder,
 * WeChat external push preference) — the mandatory in-site kinds are never settable here.
 * Strict JSON on PUT (unknown field, explicit null, duplicate key and trailing token are all
 * 400), X-Request-Id terminal UUID required, supplement-23 idempotency inside the service and
 * Cache-Control: no-store on every reply. The push switch only records the preference: the
 * external delivery capability itself is shelved (#108).
 */
@RestController
@RequestMapping("/api/v1/c/notification-preferences")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CNotificationPreferenceController {

  private static final ObjectReader STRICT =
      MerchantJsonReaderFactory.strictReader();
  private static final Set<String> UPDATE_FIELDS = Set.of("interactionEnabled", "externalPushEnabled");

  private final NotificationPreferenceApi preferences;

  public CNotificationPreferenceController(NotificationPreferenceApi preferences) {
    this.preferences = preferences;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> get(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    PreferenceView view =
        preferences.getPreference(
            new QueryContext(trace(req), OperatorType.USER, session.userId()));
    return ApiResponse.success(body(view), trace(req));
  }

  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> update(
      @RequestBody String raw, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    JsonNode body = strictObject(raw);
    String requestId = requestId(req);
    PreferenceView view =
        preferences.updatePreference(
            new UpdatePreferenceCommand(
                requiredBoolean(body, "interactionEnabled"),
                requiredBoolean(body, "externalPushEnabled"),
                new CommandContext(
                    requestId, trace(req), OperatorType.USER, session.userId(), "C_MINIAPP")));
    return ApiResponse.success(body(view), trace(req));
  }

  private static Map<String, Object> body(PreferenceView view) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("interactionEnabled", view.interactionEnabled());
    data.put("externalPushEnabled", view.externalPushEnabled());
    data.put("version", view.version());
    data.put("updatedAt", view.updatedAt() == null ? null : format(view.updatedAt()));
    return data;
  }

  /** Unknown fields, explicit nulls, duplicate keys and trailing tokens are all 400. */
  private static JsonNode strictObject(String raw) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject()) throw invalid();
      for (String name : root.propertyNames()) {
        if (!UPDATE_FIELDS.contains(name)) throw invalid();
        if (!root.get(name).isBoolean()) throw invalid();
      }
      return root;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static boolean requiredBoolean(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isBoolean()) throw invalid();
    return value.asBoolean();
  }

  private static String format(java.time.OffsetDateTime value) {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
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
