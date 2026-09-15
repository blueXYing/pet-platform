package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/c/auth/attempts, POST /api/v1/c/auth/wechat-login (HTTP10 §3.1, accepted
 * AUTH-001 mapping), GET /api/v1/c/auth/session and POST /api/v1/c/auth/logout. All responses
 * carrying attempt/session secrets are Cache-Control: no-store.
 */
@RestController
@RequestMapping("/api/v1/c/auth")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CAuthController {

    private final UserAuthService auth;

    public CAuthController(UserAuthService auth) {
        this.auth = auth;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/attempts")
    public ApiResponse<Map<String, Object>> attempts(
            @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
        body.require(Set.of("purpose"), "purpose");
        Map<String, Object> data =
                auth.createAttempt(CShared.requestId(req), body.string("purpose"), req.getRemoteAddr());
        response.setStatus(201);
        return ApiResponse.success(data, CShared.trace(req));
    }

    @PostMapping("/wechat-login")
    public ApiResponse<Map<String, Object>> wechatLogin(
            @RequestBody Request body, HttpServletRequest req) {
        body.require(Set.of("attemptId", "wechatCode", "phoneCode"), "attemptId", "wechatCode");
        Map<String, Object> data = auth.wechatLogin(
                CShared.requestId(req),
                body.string("attemptId"),
                CShared.attemptSecret(req),
                body.string("wechatCode"),
                body.string("phoneCode"));
        return ApiResponse.success(data, CShared.trace(req));
    }

    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> session(HttpServletRequest req) {
        Object attribute = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attribute instanceof MiniSessionView view)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", view.sessionId());
        data.put("userId", view.userId());
        data.put("audience", "MINIAPP");
        data.put("expiresAt", new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(view.expiresAt()));
        data.put("phoneMasked", view.phoneMasked());
        return ApiResponse.success(data, CShared.trace(req));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(@RequestBody Request body, HttpServletRequest req) {
        body.require(Set.of());
        String bearer = CBearerSessionFilter.bearer(req);
        if (bearer == null) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        // Convergent by contract: a stale token after an earlier logout still reports true.
        Map<String, Object> data = auth.logout(CShared.requestId(req), bearer);
        return ApiResponse.success(data, CShared.trace(req));
    }
}
