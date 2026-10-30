package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CShared.trace;

import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.community.api.CommunityApiCodes;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** CCR-COM-001 public community HTTP face. */
@RestController
@ConditionalOnProperty(prefix = "pet", name = {"community.enabled", "community.c.http.enabled", "auth.c.enabled"},
        havingValue = "true")
public class CommunityCController {

    private final CommunityPublicApi community;

    public CommunityCController(CommunityPublicApi community) {
        this.community = community;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping(value = "/api/v1/c/community/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> posts(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String topicCode,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        only(req, "scope", "topicCode", "page", "pageSize");
        CommunityViews.Page<CommunityViews.PostCard> result = community.listPosts(
                new CommunityPublicApi.PostFeedQuery(scope, topicCode, page(page), pageSize(pageSize, 50),
                        viewer(req)));
        return ApiResponse.success(pageWire(result, CommunityCController::post), trace(req));
    }

    @GetMapping(value = "/api/v1/c/community/posts/{postId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> post(@PathVariable String postId, HttpServletRequest req) {
        noQuery(req);
        return ApiResponse.success(post(community.getPost(
                new CommunityPublicApi.PostQuery(id(postId), viewer(req)))), trace(req));
    }

    @GetMapping(value = "/api/v1/c/community/posts/{postId}/comments",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> comments(@PathVariable String postId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest req) {
        only(req, "page", "pageSize");
        CommunityViews.Page<CommunityViews.CommentView> result = community.listComments(
                new CommunityPublicApi.CommentListQuery(id(postId), page(page),
                        pageSize(pageSize, 50), viewer(req)));
        return ApiResponse.success(pageWire(result, CommunityCController::comment), trace(req));
    }

    @GetMapping(value = "/api/v1/c/community/hot", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> hot(@RequestParam(required = false) String limit,
            HttpServletRequest req) {
        only(req, "limit");
        int bounded = limit == null || limit.isBlank() ? 3 : integer(limit, 1, 6, "limit");
        List<CommunityViews.PostCard> items = community.listHotPosts(
                new CommunityPublicApi.HotPostsQuery(bounded, viewer(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.stream().map(CommunityCController::post).toList());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping(value = "/api/v1/c/community/topics", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> topics(HttpServletRequest req) {
        noQuery(req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", community.listTopics().stream().map(CommunityCController::topic).toList());
        return ApiResponse.success(data, trace(req));
    }

    @GetMapping(value = "/api/v1/c/community/users/{userId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> profile(@PathVariable String userId, HttpServletRequest req) {
        noQuery(req);
        CommunityViews.UserProfile profile = community.getUserProfile(
                new CommunityPublicApi.UserProfileQuery(id(userId), viewer(req)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("author", author(profile.author()));
        data.put("postCount", profile.postCount());
        data.put("followerCount", profile.followerCount());
        data.put("followingCount", profile.followingCount());
        data.put("following", profile.following());
        return ApiResponse.success(data, trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/media",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> uploadMedia(@RequestPart("file") MultipartFile file,
            HttpServletRequest req) throws IOException {
        MiniSessionView session = mini(req);
        if (file == null || file.isEmpty()) {
            throw new ApiException(CommunityApiCodes.MEDIA_INVALID, "图片不能为空");
        }
        String type = mediaType(file);
        CommunityViews.MediaView view = community.uploadMedia(
                new CommunityPublicApi.UploadMediaCommand(file.getBytes(), type, session.userId(),
                        command(req, session)));
        return ApiResponse.success(media(view), trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/posts",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> createPost(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        CommunityViews.Receipt receipt = community.createPost(new CommunityPublicApi.CreatePostCommand(
                session.userId(), optionalText(body, "title"), text(body, "content"),
                strings(body.get("imageIds")), optionalText(body, "topicCode"),
                command(req, session)));
        return ApiResponse.success(receipt(receipt), trace(req));
    }

    @DeleteMapping(value = "/api/v1/c/community/posts/{postId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> deletePost(@PathVariable String postId, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        return ApiResponse.success(receipt(community.deletePost(
                new CommunityPublicApi.DeletePostCommand(id(postId), session.userId(),
                        command(req, session)))), trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/posts/{postId}/likes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> like(@PathVariable String postId, HttpServletRequest req) {
        return interaction(postId, req, true, false);
    }

    @DeleteMapping(value = "/api/v1/c/community/posts/{postId}/likes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> unlike(@PathVariable String postId, HttpServletRequest req) {
        return interaction(postId, req, false, false);
    }

    @PostMapping(value = "/api/v1/c/community/posts/{postId}/favorites",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> favorite(@PathVariable String postId, HttpServletRequest req) {
        return interaction(postId, req, true, true);
    }

    @DeleteMapping(value = "/api/v1/c/community/posts/{postId}/favorites",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> unfavorite(@PathVariable String postId, HttpServletRequest req) {
        return interaction(postId, req, false, true);
    }

    private ApiResponse<Map<String, Object>> interaction(String postId, HttpServletRequest req,
            boolean active, boolean favorite) {
        noQuery(req);
        MiniSessionView session = mini(req);
        CommunityPublicApi.PostInteractionCommand command = new CommunityPublicApi.PostInteractionCommand(
                id(postId), session.userId(), command(req, session));
        CommunityViews.InteractionResult result = favorite
                ? (active ? community.favoritePost(command) : community.unfavoritePost(command))
                : (active ? community.likePost(command) : community.unlikePost(command));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", result.postId());
        data.put("active", result.active());
        data.put("count", result.count());
        data.put("changed", result.changed());
        return ApiResponse.success(data, trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/posts/{postId}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> createComment(@PathVariable String postId,
            @RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        CommunityViews.Receipt receipt = community.createComment(
                new CommunityPublicApi.CreateCommentCommand(id(postId), session.userId(),
                        text(body(raw), "content"), command(req, session)));
        return ApiResponse.success(receipt(receipt), trace(req));
    }

    @DeleteMapping(value = "/api/v1/c/community/comments/{commentId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> deleteComment(@PathVariable String commentId,
            HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        return ApiResponse.success(receipt(community.deleteComment(
                new CommunityPublicApi.DeleteCommentCommand(id(commentId), session.userId(),
                        command(req, session)))), trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/users/{userId}/follow",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> follow(@PathVariable String userId, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        return ApiResponse.success(receipt(community.followUser(
                new CommunityPublicApi.FollowCommand(id(userId), session.userId(),
                        command(req, session)))), trace(req));
    }

    @DeleteMapping(value = "/api/v1/c/community/users/{userId}/follow",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> unfollow(@PathVariable String userId, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        return ApiResponse.success(receipt(community.unfollowUser(
                new CommunityPublicApi.FollowCommand(id(userId), session.userId(),
                        command(req, session)))), trace(req));
    }

    @PostMapping(value = "/api/v1/c/community/reports",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Map<String, Object>> report(@RequestBody String raw, HttpServletRequest req) {
        noQuery(req);
        MiniSessionView session = mini(req);
        JsonNode body = body(raw);
        CommunityViews.Receipt receipt = community.report(new CommunityPublicApi.ReportCommand(
                text(body, "targetType"), text(body, "targetId"), session.userId(),
                text(body, "reasonCode"), optionalText(body, "reasonText"),
                command(req, session)));
        return ApiResponse.success(receipt(receipt), trace(req));
    }

    // ------------------------------------------------------------------ wire

    private static Map<String, Object> post(CommunityViews.PostCard post) {
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
        out.put("liked", post.liked());
        out.put("favorited", post.favorited());
        out.put("followingAuthor", post.followingAuthor());
        out.put("publishedAt", time(post.publishedAt()));
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

    private static Map<String, Object> comment(CommunityViews.CommentView comment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commentId", comment.commentId());
        out.put("author", author(comment.author()));
        out.put("content", comment.content());
        out.put("createdAt", time(comment.createdAt()));
        return out;
    }

    private static Map<String, Object> media(CommunityViews.MediaView media) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mediaId", media.mediaId());
        out.put("url", media.url());
        out.put("contentType", media.contentType());
        out.put("bytes", media.bytes());
        out.put("width", media.width());
        out.put("height", media.height());
        out.put("sha256", media.sha256());
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

    private static <T> Map<String, Object> pageWire(CommunityViews.Page<T> page,
            java.util.function.Function<T, Map<String, Object>> mapper) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page.page());
        out.put("pageSize", page.pageSize());
        out.put("total", page.total());
        out.put("items", page.items().stream().map(mapper).toList());
        return out;
    }

    // ------------------------------------------------------------------ request helpers

    private static MiniSessionView mini(HttpServletRequest req) {
        Object value = req.getAttribute(CBearerSessionFilter.VIEW);
        if (value instanceof MiniSessionView session) return session;
        throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    private static String viewer(HttpServletRequest req) {
        Object value = req.getAttribute(CBearerSessionFilter.VIEW);
        return value instanceof MiniSessionView session ? session.userId() : null;
    }

    private static CommandContext command(HttpServletRequest req, MiniSessionView session) {
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "X-Request-Id is required");
        }
        return new CommandContext(requestId, trace(req), OperatorType.USER, session.userId(), "MINIAPP");
    }

    private static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "id is invalid");
        }
        return value;
    }

    private static int page(String value) {
        return value == null || value.isBlank() ? 1 : integer(value, 1, 10_000, "page");
    }

    private static int pageSize(String value, int max) {
        return value == null || value.isBlank() ? 20 : integer(value, 1, max, "pageSize");
    }

    private static int integer(String value, int min, int max, String field) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
        return parsed;
    }

    private static void only(HttpServletRequest req, String... names) {
        java.util.Set<String> allowed = java.util.Set.of(names);
        for (String name : req.getParameterMap().keySet()) {
            if (!allowed.contains(name) || req.getParameterValues(name).length != 1) {
                throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, name + " is invalid");
            }
        }
    }

    private static void noQuery(HttpServletRequest req) {
        if (!req.getParameterMap().isEmpty()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "query is invalid");
        }
    }

    private static JsonNode body(String raw) {
        try {
            JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (RuntimeException failure) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "body is invalid");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        return value.asText();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "imageIds is invalid");
        java.util.List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "imageIds is invalid");
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static String mediaType(MultipartFile file) {
        String declared = file.getContentType();
        if ("image/png".equals(declared)) return declared;
        if ("image/jpeg".equals(declared)) return declared;
        throw new ApiException(CommunityApiCodes.MEDIA_INVALID, "仅支持 PNG/JPEG");
    }

    private static String time(java.time.OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
