package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CFavoriteController.command;
import static com.petplatform.boot.adapter.web.c.CFavoriteController.format;
import static com.petplatform.boot.adapter.web.c.CFavoriteController.parse;
import static com.petplatform.boot.adapter.web.c.CFavoriteController.rejectQuery;
import static com.petplatform.boot.adapter.web.c.CFavoriteController.session;
import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.user.api.command.FootprintCommandApi;
import com.petplatform.user.api.command.FootprintCommands.Delete;
import com.petplatform.user.api.command.FootprintCommands.Record;
import com.petplatform.user.api.dto.FootprintItemDTO;
import com.petplatform.user.api.query.FootprintQueryApi.MyFootprintListQuery;
import com.petplatform.user.biz.apiimpl.FootprintApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract 58 footprint HTTP face (58号 / 10号 §3.16). The recording route is the client-side
 * browse tracking point (浏览埋点): the anonymous-browsable catalog reads stay untouched and
 * the logged-in miniapp posts one bodyless record per service/store detail view. Repeated
 * views are server-side upserts on (user, target), so the list deduplicates by target and
 * orders last_viewed_at DESC; the row delete is idempotent. A 404 answer on record means the
 * target is currently hidden — the client treats it as "nothing to record", not an error.
 */
@RestController
@ConditionalOnProperty(prefix = "pet",
    name = {"auth.c.enabled", "favtrack.http.enabled",
            "service.query.enabled", "store.query.enabled"},
    havingValue = "true")
public class CFootprintController {

    private final FootprintApiImpl footprints;

    public CFootprintController(FootprintApiImpl footprints) {
        this.footprints = footprints;
    }

    @ModelAttribute
    public void responseHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping("/api/v1/c/footprints/{targetType}/{targetId}")
    public org.springframework.http.ResponseEntity<ApiResponse<Map<String, Object>>> record(
            @PathVariable String targetType, @PathVariable String targetId,
            HttpServletRequest req) {
        rejectQuery(req);
        MiniSessionView current = session(req);
        FootprintCommandApi.RecordOutcome outcome = footprints.recordFootprint(new Record(
                targetType, targetId,
                command(CShared.requestId(req), trace(req), current)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("footprintId", outcome.receipt().footprintId());
        data.put("targetType", outcome.receipt().targetType());
        data.put("targetId", outcome.receipt().targetId());
        data.put("lastViewedAt", format(outcome.receipt().lastViewedAt()));
        data.put("created", outcome.created());
        // Favorite-family split: only the first-ever view of the target inserts (201); repeated
        // views and requestId replays answer 200 with the refreshed current row.
        return org.springframework.http.ResponseEntity
                .status(outcome.created() ? 201 : 200)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(data, trace(req)));
    }

    @GetMapping("/api/v1/c/footprints")
    public ApiResponse<Map<String, Object>> list(
            jakarta.servlet.http.HttpServletRequest req,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String page,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String pageSize) {
        rejectUnknownParameters(req);
        MiniSessionView current = session(req);
        PageResult<FootprintItemDTO> value = footprints.listMyFootprints(new MyFootprintListQuery(
                parse(page, 1, 10_000, "page"), parse(pageSize, 20, 50, "pageSize"),
                new QueryContext(trace(req), OperatorType.USER, current.userId())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", value.items().stream().map(CFootprintController::view).toList());
        data.put("page", value.page());
        data.put("pageSize", value.pageSize());
        data.put("total", value.total());
        return ApiResponse.success(data, trace(req));
    }

    @DeleteMapping("/api/v1/c/footprints/{footprintId}")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable String footprintId, HttpServletRequest req) {
        rejectQuery(req);
        MiniSessionView current = session(req);
        FootprintCommandApi.DeleteOutcome outcome = footprints.deleteFootprint(new Delete(
                footprintId, command(CShared.requestId(req), trace(req), current)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("footprintId", outcome.receipt().footprintId());
        data.put("deleted", outcome.receipt().deleted());
        return ApiResponse.success(data, trace(req));
    }

    private static Map<String, Object> view(FootprintItemDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("footprintId", item.footprintId());
        row.put("targetType", item.targetType());
        row.put("targetId", item.targetId());
        row.put("targetAvailable", item.display().targetAvailable());
        row.put("title", item.display().title());
        row.put("categoryLabel", item.display().categoryLabel());
        row.put("salePrice", item.display().salePrice());
        row.put("storeId", item.display().storeId());
        row.put("coverUrl", item.display().coverUrl());
        row.put("lastViewedAt", format(item.lastViewedAt()));
        return row;
    }

    private static void rejectUnknownParameters(HttpServletRequest req) {
        for (String name : new String[] {"page", "pageSize"}) {
            String[] values = req.getParameterValues(name);
            if (values != null && values.length > 1) {
                throw CFavoriteController.invalid(name);
            }
        }
        for (String name : req.getParameterMap().keySet()) {
            if (!"page".equals(name) && !"pageSize".equals(name)) {
                throw CFavoriteController.invalid(name);
            }
        }
    }
}
