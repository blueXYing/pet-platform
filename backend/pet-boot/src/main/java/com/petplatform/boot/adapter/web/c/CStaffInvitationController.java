package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.command.ConfirmStaffMemberInvitationCommand;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationDetailDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationListPageDTO;
import com.petplatform.merchant.api.dto.MerchantStaffInvitationSummaryDTO;
import com.petplatform.merchant.api.query.MyStaffInvitationPageQuery;
import com.petplatform.merchant.api.query.MyStaffInvitationQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract 54 §4 D1-a employee confirm channel: the invited user reads the invitation with
 * their own MINIAPP session and confirms it. Phone equality is re-proven server-side inside
 * the read/confirm kernel; a non-matching session reads exactly like a missing invitation (404).
 * Contract 54 §7 adds the employee-side list: the session's verified account phone is matched
 * server-side against the invitation phones, so the route carries no scope coordinates and a
 * non-matching session simply reads an empty page — never an existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/c/staff/invitations")
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "merchant.staff-member.enabled"},
    havingValue = "true")
public class CStaffInvitationController {
  private final MerchantStaffMemberApiImpl members;

  public CStaffInvitationController(MerchantStaffMemberApiImpl members) {
    this.members = members;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String page,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String pageSize,
      HttpServletRequest req) {
    onlyParameters(req, "page", "pageSize");
    MiniSessionView session = session(req);
    MerchantStaffInvitationListPageDTO result = members.listMyInvitations(
        new MyStaffInvitationPageQuery(page(page, 1, 10_000), page(pageSize, 20, 50),
            new QueryContext(trace(req), OperatorType.USER, session.userId())));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", result.items().stream().map(CStaffInvitationController::summary).toList());
    data.put("page", result.page());
    data.put("pageSize", result.pageSize());
    data.put("total", result.total());
    return ApiResponse.success(data, trace(req));
  }

  private static Map<String, Object> summary(MerchantStaffInvitationSummaryDTO row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("invitationId", row.invitationId());
    item.put("merchantId", row.merchantId());
    item.put("merchantName", row.merchantName());
    item.put("storeId", row.storeId());
    item.put("storeName", row.storeName());
    item.put("memberName", row.memberName());
    item.put("grantedActions", row.grantedActions());
    item.put("status", row.status());
    item.put("invitedAt", row.invitedAt() == null ? null : format(row.invitedAt()));
    item.put("updatedAt", row.updatedAt() == null ? null : format(row.updatedAt()));
    return item;
  }

  /** Millisecond ISO instant, the C-side wire convention (coupon/points precedent). */
  private static String format(java.time.OffsetDateTime value) {
    return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(value.toInstant());
  }

  @GetMapping("/{invitationId}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable String invitationId,
      HttpServletRequest req) {
    onlyParameters(req);
    MiniSessionView session = session(req);
    MerchantStaffInvitationDetailDTO detail = members.getMyInvitation(
        new MyStaffInvitationQuery(invitationId, new QueryContext(trace(req), OperatorType.USER,
            session.userId())));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invitationId", detail.invitationId());
    data.put("merchantId", detail.merchantId());
    data.put("merchantName", detail.merchantName());
    data.put("storeId", detail.storeId());
    data.put("storeName", detail.storeName());
    data.put("memberName", detail.memberName());
    data.put("grantedActions", detail.grantedActions());
    data.put("status", detail.status());
    return ApiResponse.success(data, trace(req));
  }

  @PostMapping("/{invitationId}/confirm")
  public ApiResponse<Map<String, Object>> confirm(@PathVariable String invitationId,
      HttpServletRequest req) {
    onlyParameters(req);
    MiniSessionView session = session(req);
    CommandContext context = new CommandContext(CShared.requestId(req), trace(req),
        OperatorType.USER, session.userId(), "MINIAPP");
    var result = members.confirmInvitation(new ConfirmStaffMemberInvitationCommand(invitationId, context));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("memberId", result.member().memberId());
    data.put("merchantId", result.member().merchantId());
    data.put("storeId", result.member().storeId());
    data.put("memberStatus", result.member().memberStatus());
    data.put("grantedActions", result.member().grantedActions());
    data.put("replayed", result.replayed());
    return ApiResponse.success(data, trace(req));
  }

  private static MiniSessionView session(HttpServletRequest req) {
    Object view = req.getAttribute(CBearerSessionFilter.VIEW);
    if (view instanceof MiniSessionView s) return s;
    throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
  }

  /** §7 list accepts exactly page/pageSize once each; anything else is a plain 400. */
  private static void onlyParameters(HttpServletRequest req, String... allowed) {
    for (Map.Entry<String, String[]> entry : req.getParameterMap().entrySet()) {
      boolean known = false;
      for (String candidate : allowed) known = known || candidate.equals(entry.getKey());
      if (!known || entry.getValue() != null && entry.getValue().length > 1)
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
  }

  /** Same paging lexicon as the owner-side staff lists: decimal, bounded, defaulting. */
  private static int page(String value, int fallback, int max) {
    if (value == null) return fallback;
    if (!value.matches("[1-9][0-9]*") || value.length() > 5)
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > max) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
      return parsed;
    } catch (NumberFormatException failure) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
  }
}
