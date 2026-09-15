package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/c/account/phone-binding with exactly one credential source: the attempt path
 * (attemptId + X-Auth-Attempt, first binding after VERIFY_PHONE) or the MINIAPP Bearer path
 * (rebind of the logged-in account). Supplying an attemptId alongside a Bearer is refused.
 */
@RestController
@RequestMapping("/api/v1/c/account")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CAccountController {

    private final UserAuthService auth;

    public CAccountController(UserAuthService auth) {
        this.auth = auth;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/phone-binding")
    public ApiResponse<Map<String, Object>> phoneBinding(
            @RequestBody Request body, HttpServletRequest req) {
        body.require(Set.of("phoneCode", "attemptId"), "phoneCode");
        String attemptId = body.string("attemptId");
        Map<String, Object> data;
        if (attemptId != null) {
            if (CBearerSessionFilter.bearer(req) != null) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法");
            }
            data = auth.bindPhoneWithAttempt(
                    CShared.requestId(req), attemptId, CShared.attemptSecret(req),
                    body.string("phoneCode"));
        } else {
            String bearer = CBearerSessionFilter.bearer(req);
            if (bearer == null) {
                throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            data = auth.rebindPhoneWithSession(
                    CShared.requestId(req), bearer, body.string("phoneCode"));
        }
        return ApiResponse.success(data, CShared.trace(req));
    }
}
