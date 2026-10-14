package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.command.FavoriteCommandApi;
import com.petplatform.user.api.command.FavoriteCommands.Add;
import com.petplatform.user.api.command.FavoriteCommands.Cancel;
import com.petplatform.user.api.dto.FavoriteItemDTO;
import com.petplatform.user.api.query.FavoriteQueryApi.FavoriteStatus;
import com.petplatform.user.api.query.FavoriteQueryApi.FavoriteStatusQuery;
import com.petplatform.user.api.query.FavoriteQueryApi.MyFavoriteListQuery;
import com.petplatform.user.biz.apiimpl.FavoriteApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract 58 favorite HTTP face (58号 / 10号 §3.16): session-scoped user-private favorites
 * over SERVICE/STORE targets. Adds are bodyless path-parameter POSTs with terminal
 * X-Request-Id (201 first create / 200 replay or business duplicate); cancels are hard-delete
 * DELETEs that succeed when absent; the list pages created_at DESC with the optional
 * targetType filter; the status probe answers 200 with favoriteId=null when not favorited.
 * Target admission reuses the C catalog visibility APIs, so hidden/absent targets are the
 * uniform 404 (anti-enumeration).
 */
@RestController
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "favtrack.http.enabled",
            "service.query.enabled", "store.query.enabled"},
    havingValue = "true")
public class CFavoriteController {

    private final FavoriteApiImpl favorites;

    public CFavoriteController(FavoriteApiImpl favorites) {
        this.favorites = favorites;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping("/api/v1/c/favorites/{targetType}/{targetId}")
    public org.springframework.http.ResponseEntity<ApiResponse<Map<String, Object>>> add(
            @PathVariable String targetType, @PathVariable String targetId,
            HttpServletRequest req) {
        rejectQuery(req);
        MiniSessionView session = session(req);
        FavoriteCommandApi.AddOutcome outcome = favorites.addFavorite(new Add(
                targetType, targetId,
                command(CShared.requestId(req), trace(req), session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("favoriteId", outcome.receipt().favoriteId());
        data.put("targetType", outcome.receipt().targetType());
        data.put("targetId", outcome.receipt().targetId());
        data.put("favoritedAt", format(outcome.receipt().favoritedAt()));
        data.put("favorited", true);
        data.put("created", outcome.created());
        // Review-family split (10号 §3.16): only the very first create is a 201; replays and
        // business duplicates answer 200 with the same current-row receipt.
        return org.springframework.http.ResponseEntity
                .status(outcome.created() ? 201 : 200)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(data, trace(req)));
    }

    @DeleteMapping("/api/v1/c/favorites/{targetType}/{targetId}")
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable String targetType, @PathVariable String targetId,
            HttpServletRequest req) {
        rejectQuery(req);
        MiniSessionView session = session(req);
        FavoriteCommandApi.CancelOutcome outcome = favorites.cancelFavorite(new Cancel(
                targetType, targetId,
                command(CShared.requestId(req), trace(req), session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", outcome.receipt().targetType());
        data.put("targetId", outcome.receipt().targetId());
        data.put("removed", outcome.receipt().removed());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/favorites")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        rejectUnknownParameters(req);
        if (targetType != null && !Set.of("SERVICE", "STORE").contains(targetType)) {
            throw invalid("targetType");
        }
        MiniSessionView session = session(req);
        PageResult<FavoriteItemDTO> value = favorites.listMyFavorites(new MyFavoriteListQuery(
                targetType, parse(page, 1, 10_000, "page"), parse(pageSize, 20, 50, "pageSize"),
                new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CFavoriteController::view).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping("/api/v1/c/favorites/{targetType}/{targetId}")
    public ApiResponse<Map<String, Object>> status(
            @PathVariable String targetType, @PathVariable String targetId,
            HttpServletRequest req) {
        rejectQuery(req);
        MiniSessionView session = session(req);
        FavoriteStatus value = favorites.favoriteStatus(new FavoriteStatusQuery(
                targetType, targetId,
                new QueryContext(trace(req), OperatorType.USER, session.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", targetType);
        data.put("targetId", targetId);
        data.put("favoriteId", value.favoriteId());
        data.put("favoritedAt", value.favoritedAt() == null ? null : format(value.favoritedAt()));
        return ApiResponse.success(data, trace(req));
    }

    /** Fixed wire projection of a list row; unavailable targets keep null display columns. */
    private static Map<String, Object> view(FavoriteItemDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("favoriteId", item.favoriteId());
        row.put("targetType", item.targetType());
        row.put("targetId", item.targetId());
        row.put("targetAvailable", item.display().targetAvailable());
        row.put("title", item.display().title());
        row.put("categoryLabel", item.display().categoryLabel());
        row.put("salePrice", item.display().salePrice());
        row.put("storeId", item.display().storeId());
        row.put("coverUrl", item.display().coverUrl());
        row.put("favoritedAt", format(item.favoritedAt()));
        return row;
    }

    static String format(java.time.OffsetDateTime value) {
        return value == null ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                        .format(value.toInstant());
    }

    static CommandContext command(String requestId, String trace, MiniSessionView session) {
        return new CommandContext(requestId, trace, OperatorType.USER, session.userId(),
                "MINIAPP");
    }

    static MiniSessionView session(HttpServletRequest req) {
        Object view = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(view instanceof MiniSessionView session)) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (!"ACTIVE".equals(session.userStatus())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "当前账号不可用");
        }
        return session;
    }

    static int parse(String raw, int fallback, int max, String field) {
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]{1,5}")) throw invalid(field);
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) throw invalid(field);
        return value;
    }

    static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"targetType", "page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) throw invalid(name);
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"targetType".equals(name) && !"page".equals(name) && !"pageSize".equals(name)) {
                throw invalid(name);
            }
        }
    }

    static void rejectQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) throw invalid("query");
    }

    static ApiException invalid(String field) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
    }
}
