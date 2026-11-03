package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.marketing.MarketingHttpSupport.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.common.ApiResponse;
import com.petplatform.community.api.CommunityAdminApi;
import com.petplatform.community.api.CommunityAdminApi.AdminAuthorization;
import com.petplatform.community.api.dto.CommunityViews;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** CCR-COM-001 operations moderation HTTP face. */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"community.enabled", "community.admin.http.enabled"},
        havingValue = "true")
public class CommunityAdminController {

    private final CommunityAdminApi community;

    public CommunityAdminController(CommunityAdminApi community) {
        this.community = community;
    }

    @ModelAttribute
    void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping(value = "/api/v1/admin/community/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> posts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "keyword", "page", "pageSize");
        AdminSessionView session = admin(req, "community.post.read");
        CommunityViews.Page<CommunityViews.AdminPostView> result = community.listPosts(
                new CommunityAdminApi.AdminPostListQuery(status, keyword, page(page),
                        pageSize(pageSize), auth(session), adminQuery(req, session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(CommunityAdminController::post).toList());
        return ApiResponse.success(data, trace(req));
    }

    @PostMapping(value = "/api/v1/admin/community/posts/{postId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> moderate(@PathVariable String postId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "community.post.moderate");
        JsonNode body = body(raw);
        CommunityViews.Receipt receipt = community.moderatePost(
                new CommunityAdminApi.ModeratePostCommand(id(postId), text(body, "decision"),
                        optionalText(body, "reason"), longValue(body, "expectedVersion"),
                        auth(session), adminCommand(req, session)));
        return ApiResponse.success(receipt(receipt), trace(req));
    }

    @GetMapping(value = "/api/v1/admin/community/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> reports(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        onlyParameters(req, "status", "page", "pageSize");
        AdminSessionView session = admin(req, "community.report.read");
        CommunityViews.Page<CommunityViews.AdminReportView> result = community.listReports(
                new CommunityAdminApi.AdminReportListQuery(status, page(page), pageSize(pageSize),
                        auth(session), adminQuery(req, session)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("pageSize", result.pageSize());
        data.put("total", result.total());
        data.put("items", result.items().stream().map(CommunityAdminController::report).toList());
        return ApiResponse.success(data, trace(req));
    }

    @PostMapping(value = "/api/v1/admin/community/reports/{reportId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> resolve(@PathVariable String reportId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        AdminSessionView session = admin(req, "community.report.handle");
        JsonNode body = body(raw);
        CommunityViews.Receipt receipt = community.resolveReport(
                new CommunityAdminApi.ResolveReportCommand(id(reportId), text(body, "decision"),
                        text(body, "note"), auth(session), adminCommand(req, session)));
        return ApiResponse.success(receipt(receipt), trace(req));
    }

    private static AdminAuthorization auth(AdminSessionView session) {
        var scope = session.permissions().dataScope();
        return new AdminAuthorization(session.principal().sessionId(),
                session.principal().sessionGeneration(), session.principal().operatorId(),
                scope.mode(), scope.cityCodes());
    }

    private static Map<String, Object> post(CommunityViews.AdminPostView post) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postId", post.postId());
        out.put("author", author(post.author()));
        out.put("title", post.title());
        out.put("content", post.content());
        out.put("images", post.imageUrls());
        out.put("topic", post.topic() == null ? null : topic(post.topic()));
        out.put("likeCount", post.likeCount());
        out.put("commentCount", post.commentCount());
        out.put("favoriteCount", post.favoriteCount());
        out.put("viewCount", post.viewCount());
        out.put("visibility", post.visibility());
        out.put("status", post.status());
        out.put("version", Long.toString(post.version()));
        out.put("publishedAt", time(post.publishedAt()));
        out.put("createdAt", time(post.createdAt()));
        out.put("updatedAt", time(post.updatedAt()));
        return out;
    }

    private static Map<String, Object> report(CommunityViews.AdminReportView report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", report.reportId());
        out.put("targetType", report.targetType());
        out.put("targetId", report.targetId());
        out.put("reporterUserId", report.reporterUserId());
        out.put("reporterNickname", report.reporterNickname());
        out.put("reasonCode", report.reasonCode());
        out.put("reasonText", report.reasonText());
        out.put("status", report.status());
        out.put("targetPreview", report.targetPreview());
        out.put("createdAt", time(report.createdAt()));
        out.put("handledAt", time(report.handledAt()));
        out.put("handlingNote", report.handlingNote());
        return out;
    }

    private static Map<String, Object> receipt(CommunityViews.Receipt receipt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", receipt.id());
        out.put("status", receipt.status());
        out.put("created", receipt.created());
        out.put("occurredAt", time(receipt.occurredAt()));
        return out;
    }

    private static Map<String, Object> author(CommunityViews.AuthorView author) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", author.userId());
        out.put("nickname", author.nickname());
        out.put("avatarUrl", author.avatarUrl());
        return out;
    }

    private static Map<String, Object> topic(CommunityViews.TopicView topic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topicCode", topic.topicCode());
        out.put("name", topic.name());
        out.put("description", topic.description());
        out.put("sortOrder", topic.sortOrder());
        return out;
    }
}
