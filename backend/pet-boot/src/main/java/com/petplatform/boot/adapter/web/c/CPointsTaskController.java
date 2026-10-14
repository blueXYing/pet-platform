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
import com.petplatform.points.api.command.PointsTaskCommandApi.BindReferralCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.ClaimRewardCommand;
import com.petplatform.points.api.command.PointsTaskCommandApi.SignInCommand;
import com.petplatform.points.api.dto.PointsTaskTypes.AwardClaimReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.ReferralBindingReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInReceipt;
import com.petplatform.points.api.dto.PointsTaskTypes.SignInStatusView;
import com.petplatform.points.api.dto.PointsTaskTypes.TasksView;
import com.petplatform.points.api.query.PointsTaskQueryApi.SignInStatusQuery;
import com.petplatform.points.api.query.PointsTaskQueryApi.TasksQuery;
import com.petplatform.points.biz.apiimpl.PointsTaskCommandApiImpl;
import com.petplatform.points.biz.apiimpl.PointsTaskQueryApiImpl;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/**
 * Contract 58 C-side earn routes (CCR-POINTS-TASK-001): sign-in status/command, task-center and
 * reward views, claim commands and referral bindings. The subject is always the current session
 * user. Strict JSON on every write (unknown field, explicit null, duplicate key and trailing
 * token are all 400), terminal X-Request-Id UUID required, query strings fully rejected and
 * Cache-Control: no-store on every reply. V1 points are earned only — no spend/exchange route
 * exists anywhere in this family.
 */
@RestController
@RequestMapping("/api/v1/c/points")
@ConditionalOnProperty(prefix = "pet", name = {"auth.c.enabled", "points.task.http.enabled"},
    havingValue = "true")
public class CPointsTaskController {

  private static final ObjectReader STRICT = MerchantJsonReaderFactory.strictReader();
  private static final Set<String> CLAIM_TYPES = Set.of(
      "INVITE_PER_HEAD", "FIRST_ORDER", "SIGN_IN_STREAK_7", "INVITE_MILESTONE_3",
      "PROFILE_COMPLETE");

  private final PointsTaskQueryApiImpl queries;
  private final PointsTaskCommandApiImpl commands;

  public CPointsTaskController(PointsTaskQueryApiImpl queries,
      PointsTaskCommandApiImpl commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  // ------------------------------------------------------------------ reads

  @GetMapping(value = "/sign-in/status", produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> signInStatus(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    SignInStatusView view = queries.signInStatus(
        new SignInStatusQuery(
            new QueryContext(trace(req), OperatorType.USER, session.userId())));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("signedToday", view.signedToday());
    data.put("streak", view.streak());
    data.put("dailyPoints", view.dailyPoints());
    data.put("week", view.week().stream().map(day -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", day.date());
      row.put("signed", day.signed());
      return row;
    }).toList());
    return ApiResponse.success(data, trace(req));
  }

  @GetMapping(value = "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> tasks(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    TasksView view = queries.tasks(
        new TasksQuery(
            new QueryContext(trace(req), OperatorType.USER, session.userId())));
    return ApiResponse.success(tasksBody(view), trace(req));
  }

  // ------------------------------------------------------------------ writes

  @PostMapping(value = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> signIn(
      @RequestBody String raw, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    strictEmptyObject(raw);
    String requestId = requestId(req);
    SignInReceipt receipt = commands.signIn(new SignInCommand(context(requestId, req, session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("signedDate", receipt.signedDate());
    data.put("points", receipt.points());
    data.put("streakAfter", receipt.streakAfter());
    data.put("ledgerId", receipt.ledgerId());
    data.put("balanceAfter", receipt.balanceAfter());
    return ApiResponse.success(data, trace(req));
  }

  @PostMapping(value = "/claims", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> claim(
      @RequestBody String raw, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    JsonNode body = strictObject(raw, Set.of("claimType"));
    String claimType = requiredText(body, "claimType");
    if (!CLAIM_TYPES.contains(claimType)) throw invalid();
    String requestId = requestId(req);
    AwardClaimReceipt receipt = commands.claimReward(
        new ClaimRewardCommand(claimType, context(requestId, req, session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("claimType", receipt.claimType());
    data.put("pointsGranted", receipt.pointsGranted());
    data.put("ledgerIds", receipt.ledgerIds());
    data.put("balanceAfter", receipt.balanceAfter());
    return ApiResponse.success(data, trace(req));
  }

  @PostMapping(value = "/referral-bindings", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> bindReferral(
      @RequestBody String raw, HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) throw invalid();
    MiniSessionView session = session(req);
    JsonNode body = strictObject(raw, Set.of("inviteCode"));
    String inviteCode = requiredText(body, "inviteCode");
    if (inviteCode.isEmpty() || inviteCode.length() > 32
        || !inviteCode.matches("[A-Za-z0-9]+")) throw invalid();
    String requestId = requestId(req);
    ReferralBindingReceipt receipt = commands.bindReferral(
        new BindReferralCommand(inviteCode, context(requestId, req, session)));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bindingId", receipt.bindingId());
    data.put("createdAt", receipt.createdAt() == null ? null : format(receipt.createdAt()));
    return ApiResponse.success(data, trace(req));
  }

  // ------------------------------------------------------------------ helpers

  private CommandContext context(String requestId, HttpServletRequest req,
      MiniSessionView session) {
    return new CommandContext(requestId, trace(req), OperatorType.USER, session.userId(),
        "C_MINIAPP");
  }

  static Map<String, Object> tasksBody(TasksView view) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("inviteCode", view.inviteCode());
    Map<String, Object> signIn = new LinkedHashMap<>();
    signIn.put("signedToday", view.signIn().signedToday());
    signIn.put("streak", view.signIn().streak());
    signIn.put("dailyPoints", view.signIn().dailyPoints());
    data.put("signIn", signIn);
    Map<String, Object> invite = new LinkedHashMap<>();
    invite.put("perInviteePoints", view.invite().perInviteePoints());
    invite.put("monthlyRewardCap", view.invite().monthlyRewardCap());
    invite.put("rewardedThisMonth", view.invite().rewardedThisMonth());
    invite.put("claimableCount", view.invite().claimableCount());
    invite.put("milestoneRewarded", view.invite().milestoneRewarded());
    invite.put("milestoneStatus", view.invite().milestoneStatus());
    invite.put("milestoneProgress", view.invite().milestoneProgress());
    data.put("invite", invite);
    data.put("tasks", view.tasks().stream().map(task -> row(
        "taskCode", task.taskCode(), "title", task.title(), "description", task.description(),
        "points", task.points(), "status", task.status(), "progress", task.progress())));
    data.put("rewards", view.rewards().stream().map(reward -> row(
        "rewardCode", reward.rewardCode(), "title", reward.title(), "description",
        reward.description(), "points", reward.points(), "status", reward.status(),
        "progress", reward.progress())));
    return data;
  }

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      row.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return row;
  }

  /** The sign-in command body is exactly an empty JSON object. */
  private static void strictEmptyObject(String raw) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject() || !root.isEmpty()) throw invalid();
    } catch (RuntimeException failure) {
      if (failure instanceof ApiException) throw failure;
      throw invalid();
    }
  }

  /** Unknown fields, explicit nulls, duplicate keys and trailing tokens are all 400. */
  private static JsonNode strictObject(String raw, Set<String> fields) {
    try {
      JsonNode root = STRICT.readTree(raw);
      if (root == null || !root.isObject()) throw invalid();
      for (String name : root.propertyNames()) {
        if (!fields.contains(name) || !root.get(name).isTextual()) throw invalid();
      }
      return root;
    } catch (RuntimeException failure) {
      if (failure instanceof ApiException) throw failure;
      throw invalid();
    }
  }

  private static String requiredText(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isTextual()) throw invalid();
    return value.asText();
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
