package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.user.biz.application.UserProfileService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET/PUT /api/v1/c/profile (HTTP10 §3.2.1): masked phone only, updates idempotent via the
 * service layer, USER_FROZEN writes rejected there. Phone rebinding is not part of profile.
 */
@RestController
@RequestMapping("/api/v1/c/profile")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CProfileController {

    private final UserProfileService profiles;

    public CProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object attribute = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attribute instanceof MiniSessionView view)) {
            throw new IllegalArgumentException("Session view missing");
        }
        return view;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(toBody(profiles.getProfile(view.userId())), CShared.trace(req));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Request body, HttpServletRequest req) {
        body.require(Set.of("nickname", "avatarUrl"));
        MiniSessionView view = session(req);
        UserProfileService.ProfileView updated = profiles.updateProfile(
                new CommandContext(
                        CShared.requestId(req), CShared.trace(req), OperatorType.USER,
                        view.userId(), "MINIAPP"),
                body.string("nickname"), body.string("avatarUrl"));
        return ApiResponse.success(toBody(updated), CShared.trace(req));
    }

    private static Map<String, Object> toBody(UserProfileService.ProfileView profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", profile.userId());
        body.put("nickname", profile.nickname());
        body.put("avatarUrl", profile.avatarUrl());
        body.put("phoneMasked", profile.phoneMasked());
        body.put("passwordEnabled", profile.passwordEnabled());
        return body;
    }
}
