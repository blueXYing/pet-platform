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
 * the command; a non-matching session reads exactly like a missing invitation (404).
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

  @GetMapping("/{invitationId}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable String invitationId,
      HttpServletRequest req) {
    rejectUnknownParameters(req);
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
    rejectUnknownParameters(req);
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

  private static void rejectUnknownParameters(HttpServletRequest req) {
    if (!req.getParameterMap().isEmpty()) {
      throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
    }
  }
}
