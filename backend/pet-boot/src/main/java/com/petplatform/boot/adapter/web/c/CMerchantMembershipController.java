package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantMembershipDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantAdmissionApiImpl;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/c/auth/merchant-memberships (CCR-W2-ADMISSION-001 §2): the caller's own
 * selectable stores. Assembled only when both the C auth and merchant application modules
 * are enabled; otherwise the endpoint fails closed.
 */
@RestController
@RequestMapping("/api/v1/c/auth/merchant-memberships")
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.c.enabled", "merchant.application.enabled"},
    havingValue = "true")
public class CMerchantMembershipController {

  private final MerchantAdmissionApiImpl admission;

  public CMerchantMembershipController(MerchantAdmissionApiImpl admission) {
    this.admission = admission;
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> memberships(
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      HttpServletRequest req) {
    rejectUnknownParameters(req);
    Object view = req.getAttribute(CBearerSessionFilter.VIEW);
    if (!(view instanceof MiniSessionView session)) {
      throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    MerchantMembershipPageDTO value =
        admission.listMemberships(
            new MerchantMembershipQuery(
                parse(page, 1, "page"),
                parse(pageSize, 20, "pageSize"),
                new QueryContext(trace(req), OperatorType.USER, session.userId())));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", value.items().stream().map(CMerchantMembershipController::membership).toList());
    data.put("page", value.page());
    data.put("pageSize", value.pageSize());
    data.put("total", value.total());
    return ApiResponse.success(data, trace(req));
  }

  private static Map<String, Object> membership(MerchantMembershipDTO item) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("merchantId", item.merchantId());
    row.put("merchantName", item.merchantName());
    row.put("storeId", item.storeId());
    row.put("storeName", item.storeName());
    row.put("membershipKind", item.membershipKind());
    return row;
  }

  private static int parse(String raw, int fallback, String field) {
    if (raw == null || raw.isEmpty()) return fallback;
    if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
    int value = Integer.parseInt(raw);
    int max = "page".equals(field) ? 10_000 : 50;
    if (value < 1 || value > max) throw invalid(field);
    return value;
  }

  private static void rejectUnknownParameters(HttpServletRequest req) {
    for (String name : new String[] {"page", "pageSize"}) {
      String[] values = req.getParameterValues(name);
      if (values != null && values.length > 1) throw invalid(name);
    }
    for (String name : req.getParameterMap().keySet()) {
      if (!"page".equals(name) && !"pageSize".equals(name)) throw invalid(name);
    }
  }

  private static ApiException invalid(String field) {
    return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
  }
}
