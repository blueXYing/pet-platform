package com.petplatform.boot.adapter.web.admin;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.boot.config.AdminBearerAuthenticationFilter;
import com.petplatform.boot.config.MerchantJsonReaderFactory;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.points.api.dto.PointsTaskTypes.ConfigView;
import com.petplatform.points.api.dto.PointsTaskTypes.UpdateConfigCommand;
import com.petplatform.points.api.query.PointsTaskQueryApi;
import com.petplatform.points.biz.apiimpl.PointsTaskCommandApiImpl;
import com.petplatform.points.biz.apiimpl.PointsTaskQueryApiImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collections;
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
 * GET/PUT /api/v1/admin/points-task-config (contract 58 §4, 32号 ruling: 签到与拉新的积分发放量
 * 后台可配置). Single-operator RBAC (SSOT §24): the reader needs points.task.config.read and the
 * writer needs points.task.config.write — no second approver exists in V1. Strict full-PUT JSON
 * (unknown field, explicit null, duplicate key, trailing token are all 400), terminal X-Request-Id
 * UUID required, supplement-23 idempotency inside the kernel, Cache-Control: no-store everywhere.
 */
@RestController
@RequestMapping("/api/v1/admin/points-task-config")
@ConditionalOnProperty(prefix = "pet", name = {"auth.admin.enabled", "points.task.http.enabled"},
    havingValue = "true")
public class AdminPointsTaskConfigController {

  private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
  private static final Set<String> FIELDS = Set.of(
      "signInDailyPoints", "signInStreakBonusPoints", "invitePerInviteePoints",
      "inviteMonthlyRewardCap", "inviteMilestoneBonusPoints", "firstOrderPoints",
      "profileCompletePoints");

  private final PointsTaskQueryApiImpl queries;
  private final PointsTaskCommandApiImpl commands;

  public AdminPointsTaskConfigController(PointsTaskQueryApiImpl queries,
      PointsTaskCommandApiImpl commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> get(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    requireAction(req, "points.task.config.read");
    return ApiResponse.success(body(queries.config(
        new PointsTaskQueryApi.ConfigQuery(null))), trace(req));
  }

  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> update(
      @RequestBody String raw, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    AdminSessionView session = requireAction(req, "points.task.config.write");
    JsonNode body = strictObject(raw);
    String requestId = requestId(req);
    ConfigView view = commands.updateConfig(new UpdateConfigCommand(
        requiredLong(body, "signInDailyPoints"),
        requiredLong(body, "signInStreakBonusPoints"),
        requiredLong(body, "invitePerInviteePoints"),
        requiredLong(body, "inviteMonthlyRewardCap"),
        requiredLong(body, "inviteMilestoneBonusPoints"),
        requiredLong(body, "firstOrderPoints"),
        requiredLong(body, "profileCompletePoints"),
        new CommandContext(requestId, trace(req), OperatorType.PLATFORM_OPERATOR,
            session.principal().operatorId(), "ADMIN_WEB")));
    return ApiResponse.success(body(view), trace(req));
  }

  private static Map<String, Object> body(ConfigView view) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("signInDailyPoints", Long.toString(view.signInDailyPoints()));
    data.put("signInStreakBonusPoints", Long.toString(view.signInStreakBonusPoints()));
    data.put("invitePerInviteePoints", Long.toString(view.invitePerInviteePoints()));
    data.put("inviteMonthlyRewardCap", Long.toString(view.inviteMonthlyRewardCap()));
    data.put("inviteMilestoneBonusPoints", Long.toString(view.inviteMilestoneBonusPoints()));
    data.put("firstOrderPoints", Long.toString(view.firstOrderPoints()));
    data.put("profileCompletePoints", Long.toString(view.profileCompletePoints()));
    data.put("version", Long.toString(view.version()));
    data.put("updatedAt", view.updatedAt() == null ? null : format(view.updatedAt()));
    return data;
  }

  /** RBAC + ADMIN_WEB audience gate, the same shape as the aftersale admin face. */
  private static AdminSessionView requireAction(HttpServletRequest req, String action) {
    if (Collections.list(req.getHeaders("Authorization")).size() != 1) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
    }
    if (!(req.getAttribute(AdminBearerAuthenticationFilter.VIEW)
        instanceof AdminSessionView session)) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "Session required");
    }
    if (!"ADMIN_WEB".equals(session.principal().audience())
        || !session.permissions().actionCodes().contains(action)) {
      throw new ApiException(CommonApiCodes.FORBIDDEN, "Action unavailable");
    }
    return session;
  }

  private static String requestId(HttpServletRequest req) {
    if (Collections.list(req.getHeaders("X-Request-Id")).size() != 1) throw invalid();
    try {
      return PublicContractChecks.requireTerminalRequestId(req.getHeader("X-Request-Id"));
    } catch (IllegalArgumentException bad) {
      throw invalid();
    }
  }

  /** Unknown fields, explicit nulls, duplicate keys and trailing tokens are all 400. */
  private static JsonNode strictObject(String raw) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject()) throw invalid();
      for (String name : root.propertyNames()) {
        if (!FIELDS.contains(name) || !root.get(name).isInt()) throw invalid();
      }
      if (root.size() != FIELDS.size()) throw invalid();
      return root;
    } catch (RuntimeException failure) {
      if (failure instanceof ApiException) throw failure;
      throw invalid();
    }
  }

  private static long requiredLong(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    return value.asLong();
  }

  private static String format(java.time.OffsetDateTime value) {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
  }

  private static String trace(HttpServletRequest req) {
    return com.petplatform.boot.adapter.web.admin.AdminAuthController.trace(req);
  }

  private static ApiException invalid() {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
  }
}
