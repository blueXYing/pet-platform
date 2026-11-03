package com.petplatform.community.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.community.api.CommunityApiCodes;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort.UserCard;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.CommentRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.MediaRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.PostRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.ReportRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.TopicRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CCR-COM-001 public community kernel. */
public final class CommunityPublicService extends CommunitySupport implements CommunityPublicApi {

    private static final Set<String> SCOPES = Set.of("LATEST", "HOT", "FOLLOWING", "MINE", "FAVORITES");
    private static final Set<String> REPORT_REASONS = Set.of(
            "SPAM", "PORN", "VIOLENCE", "ILLEGAL", "HARASSMENT", "FALSE_INFO", "OTHER");
    private static final int MAX_IMAGES = 9;

    public CommunityPublicService(com.petplatform.community.biz.infrastructure.persistence.CommunityStore store,
            com.petplatform.community.biz.application.port.CommunityUserDirectoryPort users,
            com.petplatform.community.biz.application.port.CommunityMediaObjectStore mediaStore,
            java.time.Clock clock, String mediaBaseUrl) {
        super(store, users, mediaStore, clock, mediaBaseUrl);
    }

    @Override
    public CommunityViews.Page<CommunityViews.PostCard> listPosts(PostFeedQuery query) {
        Objects.requireNonNull(query, "query is required");
        String scope = query.scope() == null || query.scope().isBlank()
                ? "LATEST" : query.scope().strip().toUpperCase();
        if (!SCOPES.contains(scope)) invalid("scope is invalid");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize(), 50);
        String viewer = viewer(query.viewerUserId());
        if (Set.of("FOLLOWING", "MINE", "FAVORITES").contains(scope) && viewer == null) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "登录后可查看该列表");
        }
        TopicRow topic = topicByCode(query.topicCode());
        long viewerId = viewer == null ? 0 : id(viewer);
        long offset = (long) (page - 1) * pageSize;
        return store.read(mapper -> {
            List<PostRow> rows;
            long total;
            switch (scope) {
                case "HOT" -> {
                    rows = mapper.selectHotPosts(topic == null ? null : topic.id, pageSize, offset);
                    total = mapper.countHotPosts(topic == null ? null : topic.id);
                }
                case "FOLLOWING" -> {
                    rows = mapper.selectFollowingPosts(viewerId, pageSize, offset);
                    total = mapper.countFollowingPosts(viewerId);
                }
                case "MINE" -> {
                    rows = mapper.selectMyPosts(viewerId, pageSize, offset);
                    total = mapper.countMyPosts(viewerId);
                }
                case "FAVORITES" -> {
                    rows = mapper.selectFavoritePosts(viewerId, pageSize, offset);
                    total = mapper.countFavoritePosts(viewerId);
                }
                default -> {
                    rows = mapper.selectLatestPosts(topic == null ? null : topic.id, pageSize, offset);
                    total = mapper.countLatestPosts(topic == null ? null : topic.id);
                }
            }
            return new CommunityViews.Page<>(cards(rows, viewer), page, pageSize, total);
        });
    }

    @Override
    public CommunityViews.PostCard getPost(PostQuery query) {
        Objects.requireNonNull(query, "query is required");
        long postId = id(query.postId());
        String viewer = viewer(query.viewerUserId());
        return store.read(mapper -> {
            PostRow row = mapper.selectPostById(postId);
            if (!visibleTo(row, viewer)) {
                notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
            }
            Map<Long, UserCard> cards = userCards(List.of(row.authorUserId));
            return postCard(row, cards.get(row.authorUserId), cards, viewer);
        });
    }

    @Override
    public CommunityViews.Page<CommunityViews.CommentView> listComments(CommentListQuery query) {
        Objects.requireNonNull(query, "query is required");
        long postId = id(query.postId());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize(), 50);
        String viewer = viewer(query.viewerUserId());
        return store.read(mapper -> {
            PostRow post = mapper.selectPostById(postId);
            if (!visibleTo(post, viewer) || !"PUBLISHED".equals(post.status)) {
                notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
            }
            List<CommentRow> rows = mapper.selectComments(postId, pageSize,
                    (long) (page - 1) * pageSize);
            long total = mapper.countComments(postId);
            List<Long> authorIds = rows.stream().map(row -> row.authorUserId).distinct().toList();
            Map<Long, UserCard> cards = userCards(authorIds);
            List<CommunityViews.CommentView> items = rows.stream()
                    .map(row -> new CommunityViews.CommentView(Long.toUnsignedString(row.id),
                            author(cards.get(row.authorUserId), Long.toUnsignedString(row.authorUserId)),
                            row.content, offset(row.createdAt)))
                    .toList();
            return new CommunityViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public List<CommunityViews.TopicView> listTopics() {
        return store.read(mapper -> mapper.selectEnabledTopics().stream()
                .map(CommunitySupport::topic).toList());
    }

    @Override
    public List<CommunityViews.PostCard> listHotPosts(HotPostsQuery query) {
        Objects.requireNonNull(query, "query is required");
        int limit = query.limit() < 1 ? 3 : Math.min(query.limit(), 6);
        String viewer = viewer(query.viewerUserId());
        return store.read(mapper -> cards(mapper.selectHotPosts(null, limit, 0), viewer));
    }

    @Override
    public CommunityViews.UserProfile getUserProfile(UserProfileQuery query) {
        Objects.requireNonNull(query, "query is required");
        long target = id(query.userId());
        String viewer = viewer(query.viewerUserId());
        Map<String, UserCard> found;
        try {
            found = users.lookup(List.of(Long.toUnsignedString(target)));
        } catch (RuntimeException failure) {
            found = Map.of();
        }
        UserCard card = found == null ? null : found.get(Long.toUnsignedString(target));
        if (card == null) notFound(CommunityApiCodes.USER_NOT_FOUND, "用户不存在");
        long postCount = store.read(mapper -> mapper.countPostsByAuthor(target));
        long followers = store.read(mapper -> mapper.countFollowers(target));
        long following = store.read(mapper -> mapper.countFollowing(target));
        boolean isFollowing = viewer != null && !viewer.equals(Long.toUnsignedString(target))
                && store.read(mapper -> mapper.selectFollow(id(viewer), target)) != null;
        return new CommunityViews.UserProfile(
                author(card, Long.toUnsignedString(target)), postCount, followers, following, isFollowing);
    }

    @Override
    public CommunityViews.MediaContent media(String mediaId) {
        long id = id(mediaId);
        MediaRow row = store.read(mapper -> mapper.selectMediaById(id));
        if (row == null) notFound(CommonApiCodes.NOT_FOUND, "素材不存在");
        byte[] content = row.content;
        if (content == null && row.objectKey != null && mediaStore.enabled()) {
            try {
                content = mediaStore.get(row.objectKey);
            } catch (RuntimeException failure) {
                unavailable("素材对象存储不可用");
            }
        }
        if (content == null || content.length == 0) {
            notFound(CommonApiCodes.NOT_FOUND, "素材不存在");
        }
        return new CommunityViews.MediaContent(content, row.contentType, row.sha256);
    }

    @Override
    public CommunityViews.MediaView uploadMedia(UploadMediaCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long owner = id(command.userId());
        byte[] content = command.content();
        if (content == null || content.length == 0) {
            invalidCode(CommunityApiCodes.MEDIA_INVALID, "图片内容为空");
        }
        if (content.length > 2 * 1024 * 1024) {
            throw new ApiException(CommunityApiCodes.MEDIA_TOO_LARGE, "图片不能超过 2MiB");
        }
        String type = command.contentType();
        if (type == null || !(type.equals("image/png") || type.equals("image/jpeg"))) {
            invalidCode(CommunityApiCodes.MEDIA_INVALID, "仅支持 PNG/JPEG");
        }
        var image = image(content);
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 2000 || image.getHeight() > 2000) {
            invalidCode(CommunityApiCodes.MEDIA_INVALID, "图片尺寸不合法");
        }
        String sha = sha256(content);
        String key = requestKey("community.media.upload", command.context(), sha);
        Map<String, Object> params = Map.of("sha256", sha, "contentType", type,
                "bytes", content.length, "width", image.getWidth(), "height", image.getHeight());
        return idempotent(key, "community.media.upload", params, CommunitySupport::parseMedia,
                () -> store.execute(mapper -> {
                    MediaRow row = new MediaRow();
                    row.id = store.nextId();
                    row.ownerUserId = owner;
                    row.contentType = type;
                    row.bytes = (long) content.length;
                    row.width = image.getWidth();
                    row.height = image.getHeight();
                    row.sha256 = sha;
                    if (mediaStore.enabled()) {
                        String extension = "image/jpeg".equals(type) ? "jpg" : "png";
                        String objectKey = "marketing-assets/community/" + Long.toUnsignedString(row.id) + "." + extension;
                        try {
                            mediaStore.put(objectKey, content, type);
                            row.objectKey = objectKey;
                            row.content = null;
                        } catch (RuntimeException failure) {
                            // Keep the upload usable on a bucket policy/outage failure: the public
                            // endpoint already prefers object_key but falls back to DB bytes.
                            row.objectKey = null;
                            row.content = content;
                        }
                    } else {
                        row.content = content;
                    }
                    row.status = "ACTIVE";
                    row.createdAt = nowUtc();
                    if (mapper.insertMedia(row) != 1) unavailable("素材写入失败");
                    return new CommunityViews.MediaView(Long.toUnsignedString(row.id),
                            mediaUrl(Long.toUnsignedString(row.id)), row.contentType, row.bytes,
                            row.width, row.height, row.sha256);
                }), CommunitySupport::mediaJson);
    }

    @Override
    public CommunityViews.Receipt createPost(CreatePostCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long author = id(command.userId());
        String title = optionalText(command.title(), 60, "title");
        String content = requireText(command.content(), 1, 2000, "content");
        String topicCode = optionalText(command.topicCode(), 32, "topicCode");
        List<String> mediaIds = mediaIds(command.mediaIds());
        TopicRow topic = topicByCode(topicCode);
        if (topicCode != null && topic == null) {
            notFound(CommunityApiCodes.TOPIC_NOT_FOUND, "话题不存在或已停用");
        }
        for (String mediaId : mediaIds) {
            long id = id(mediaId);
            MediaRow media = store.read(mapper -> mapper.selectMediaById(id));
            if (media == null || media.ownerUserId == null || media.ownerUserId != author) {
                invalidCode(CommunityApiCodes.MEDIA_INVALID, "图片不存在或不属于当前用户");
            }
        }
        String status = sensitive(title, content) ? "PENDING_REVIEW" : "PUBLISHED";
        String requestKey = requestKey("community.post.create", command.context(), null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", title);
        params.put("content", content);
        params.put("topicCode", topicCode);
        params.put("mediaIds", mediaIds);
        return idempotent(requestKey, "community.post.create", params,
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    PostRow row = new PostRow();
                    row.id = store.nextId();
                    row.authorUserId = author;
                    row.title = title;
                    row.content = content;
                    row.topicId = topic == null ? null : topic.id;
                    row.imageIds = jsonList(mediaIds);
                    row.visibility = "PUBLIC";
                    row.status = status;
                    row.publishedAt = "PUBLISHED".equals(status) ? nowUtc() : null;
                    row.createdAt = nowUtc();
                    row.updatedAt = row.createdAt;
                    if (mapper.insertPost(row) != 1) unavailable("动态写入失败");
                    audit(mapper, "POST", row.id, "CREATE", null, status, command.context(), null);
                    return new CommunityViews.Receipt(Long.toUnsignedString(row.id), status, true,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    @Override
    public CommunityViews.Receipt deletePost(DeletePostCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long postId = id(command.postId());
        long author = id(command.userId());
        String requestKey = requestKey("community.post.delete", command.context(), command.postId());
        return idempotent(requestKey, "community.post.delete",
                Map.of("postId", postId, "userId", author), CommunitySupport::parseReceipt,
                () -> store.execute(mapper -> {
                    PostRow row = mapper.selectPostForUpdate(postId);
                    if (row == null || !Long.toUnsignedString(row.authorUserId).equals(command.userId())) {
                        notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
                    }
                    if ("DELETED".equals(row.status)) conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "动态已删除");
                    if (mapper.updatePostStatus(postId, row.status, "DELETED",
                            row.version == null ? 1 : row.version, nowUtc()) != 1) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "动态状态已变化");
                    }
                    audit(mapper, "POST", postId, "DELETE", row.status, "DELETED",
                            command.context(), null);
                    return new CommunityViews.Receipt(Long.toUnsignedString(postId), "DELETED", false,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    @Override
    public CommunityViews.InteractionResult likePost(PostInteractionCommand command) {
        return interact(command, true, false);
    }

    @Override
    public CommunityViews.InteractionResult unlikePost(PostInteractionCommand command) {
        return interact(command, false, false);
    }

    @Override
    public CommunityViews.InteractionResult favoritePost(PostInteractionCommand command) {
        return interact(command, true, true);
    }

    @Override
    public CommunityViews.InteractionResult unfavoritePost(PostInteractionCommand command) {
        return interact(command, false, true);
    }

    @Override
    public CommunityViews.Receipt followUser(FollowCommand command) {
        return follow(command, true);
    }

    @Override
    public CommunityViews.Receipt unfollowUser(FollowCommand command) {
        return follow(command, false);
    }

    @Override
    public CommunityViews.Receipt createComment(CreateCommentCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long postId = id(command.postId());
        long author = id(command.userId());
        String content = requireText(command.content(), 1, 500, "content");
        String status = sensitive(null, content) ? "PENDING_REVIEW" : "PUBLISHED";
        String requestKey = requestKey("community.comment.create", command.context(), null);
        return idempotent(requestKey, "community.comment.create",
                Map.of("postId", postId, "userId", author, "content", content),
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    PostRow post = mapper.selectPostForUpdate(postId);
                    if (!publishable(post)) notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
                    CommentRow row = new CommentRow();
                    row.id = store.nextId();
                    row.postId = postId;
                    row.authorUserId = author;
                    row.content = content;
                    row.status = status;
                    row.createdAt = nowUtc();
                    row.updatedAt = row.createdAt;
                    if (mapper.insertComment(row) != 1) unavailable("评论写入失败");
                    if ("PUBLISHED".equals(status)) {
                        mapper.incrementCommentCount(postId, 1, nowUtc());
                    }
                    audit(mapper, "COMMENT", row.id, "CREATE", null, status, command.context(), null);
                    return new CommunityViews.Receipt(Long.toUnsignedString(row.id), status, true,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    @Override
    public CommunityViews.Receipt deleteComment(DeleteCommentCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long commentId = id(command.commentId());
        String requestKey = requestKey("community.comment.delete", command.context(), command.commentId());
        return idempotent(requestKey, "community.comment.delete",
                Map.of("commentId", commentId, "userId", id(command.userId())),
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    CommentRow row = mapper.selectCommentForUpdate(commentId);
                    if (row == null || !Long.toUnsignedString(row.authorUserId).equals(command.userId())) {
                        notFound(CommunityApiCodes.COMMENT_NOT_FOUND, "评论不存在");
                    }
                    if ("DELETED".equals(row.status)) conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "评论已删除");
                    if (mapper.updateCommentStatus(commentId, row.status, "DELETED", nowUtc()) != 1) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "评论状态已变化");
                    }
                    if ("PUBLISHED".equals(row.status)) mapper.incrementCommentCount(row.postId, -1, nowUtc());
                    audit(mapper, "COMMENT", commentId, "DELETE", row.status, "DELETED",
                            command.context(), null);
                    return new CommunityViews.Receipt(Long.toUnsignedString(commentId), "DELETED", false,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    @Override
    public CommunityViews.Receipt report(ReportCommand command) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        String targetType = command.targetType() == null ? "" : command.targetType().strip().toUpperCase();
        if (!Set.of("POST", "COMMENT").contains(targetType)) {
            invalidCode(CommunityApiCodes.REPORT_INVALID, "举报对象不合法");
        }
        long targetId = id(command.targetId());
        String reason = command.reasonCode() == null ? "" : command.reasonCode().strip().toUpperCase();
        if (!REPORT_REASONS.contains(reason)) invalidCode(CommunityApiCodes.REPORT_INVALID, "举报原因不合法");
        String note = optionalText(command.reasonText(), 200, "reasonText");
        long reporter = id(command.userId());
        String requestKey = requestKey("community.report.create", command.context(), null);
        return idempotent(requestKey, "community.report.create",
                Map.of("targetType", targetType, "targetId", targetId, "reporter", reporter,
                        "reason", reason), CommunitySupport::parseReceipt,
                () -> store.execute(mapper -> {
                    if ("POST".equals(targetType)) {
                        PostRow post = mapper.selectPostById(targetId);
                        if (!publishable(post)) notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
                    } else {
                        CommentRow comment = mapper.selectCommentById(targetId);
                        if (comment == null || !"PUBLISHED".equals(comment.status)) {
                            notFound(CommunityApiCodes.COMMENT_NOT_FOUND, "评论不存在");
                        }
                    }
                    if (mapper.selectReportByTargetReporter(targetType, targetId, reporter) != null) {
                        conflict(CommunityApiCodes.ALREADY_REPORTED, "已举报过该内容");
                    }
                    ReportRow row = new ReportRow();
                    row.id = store.nextId();
                    row.targetType = targetType;
                    row.targetId = targetId;
                    row.reporterUserId = reporter;
                    row.reasonCode = reason;
                    row.reasonText = note;
                    row.status = "PENDING";
                    row.createdAt = nowUtc();
                    row.updatedAt = row.createdAt;
                    if (mapper.insertReport(row) != 1) unavailable("举报写入失败");
                    audit(mapper, "REPORT", row.id, "CREATE", null, "PENDING",
                            command.context(), reason);
                    return new CommunityViews.Receipt(Long.toUnsignedString(row.id), "PENDING", true,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    // ------------------------------------------------------------------ helpers

    private CommunityViews.InteractionResult interact(PostInteractionCommand command,
            boolean active, boolean favorite) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long postId = id(command.postId());
        long userId = id(command.userId());
        String action = favorite ? (active ? "favorite" : "unfavorite")
                : (active ? "like" : "unlike");
        String requestKey = requestKey("community." + action, command.context(), command.postId());
        return idempotent(requestKey, "community." + action,
                Map.of("postId", postId, "userId", userId, "active", active),
                CommunitySupport::parseInteraction, () -> store.execute(mapper -> {
                    PostRow post = mapper.selectPostForUpdate(postId);
                    if (!publishable(post)) notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
                    boolean exists = favorite
                            ? mapper.selectFavorite(postId, userId) != null
                            : mapper.selectLike(postId, userId) != null;
                    boolean changed = false;
                    if (active && !exists) {
                        if (favorite) {
                            mapper.insertFavorite(store.nextId(), postId, userId, nowUtc());
                            mapper.incrementFavoriteCount(postId, 1, nowUtc());
                        } else {
                            mapper.insertLike(store.nextId(), postId, userId, nowUtc());
                            mapper.incrementLikeCount(postId, 1, nowUtc());
                        }
                        changed = true;
                    } else if (!active && exists) {
                        if (favorite) {
                            mapper.deleteFavorite(postId, userId);
                            mapper.incrementFavoriteCount(postId, -1, nowUtc());
                        } else {
                            mapper.deleteLike(postId, userId);
                            mapper.incrementLikeCount(postId, -1, nowUtc());
                        }
                        changed = true;
                    }
                    int count = favorite ? Math.toIntExact(mapper.countFavorites(postId))
                            : Math.toIntExact(mapper.countLikes(postId));
                    return new CommunityViews.InteractionResult(Long.toUnsignedString(postId),
                            active, count, changed);
                }), CommunitySupport::interactionJson);
    }

    private CommunityViews.Receipt follow(FollowCommand command, boolean active) {
        Objects.requireNonNull(command, "command is required");
        requireCommand(command.context(), command.userId());
        long follower = id(command.userId());
        long followee = id(command.targetUserId());
        if (follower == followee) invalidCode(CommunityApiCodes.STATE_NOT_ALLOWED, "不能关注自己");
        Map<String, UserCard> found;
        try {
            found = users.lookup(List.of(Long.toUnsignedString(followee)));
        } catch (RuntimeException failure) {
            found = Map.of();
        }
        if (found == null || found.get(Long.toUnsignedString(followee)) == null) {
            notFound(CommunityApiCodes.USER_NOT_FOUND, "用户不存在");
        }
        String action = active ? "follow" : "unfollow";
        String requestKey = requestKey("community." + action, command.context(), command.targetUserId());
        return idempotent(requestKey, "community." + action,
                Map.of("follower", follower, "followee", followee, "active", active),
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    boolean exists = mapper.selectFollow(follower, followee) != null;
                    if (active && !exists) {
                        mapper.insertFollow(store.nextId(), follower, followee, nowUtc());
                    } else if (!active && exists) {
                        mapper.deleteFollow(follower, followee);
                    }
                    audit(mapper, "USER", followee, active ? "FOLLOW" : "UNFOLLOW",
                            null, null, command.context(), null);
                    return new CommunityViews.Receipt(Long.toUnsignedString(followee),
                            active ? "FOLLOWING" : "NOT_FOLLOWING", false, nowOffset());
                }), CommunitySupport::receiptJson);
    }

    private List<CommunityViews.PostCard> cards(List<PostRow> rows, String viewer) {
        List<Long> authorIds = rows.stream().map(row -> row.authorUserId).distinct().toList();
        Map<Long, UserCard> cards = userCards(authorIds);
        List<CommunityViews.PostCard> out = new ArrayList<>(rows.size());
        for (PostRow row : rows) {
            out.add(postCard(row, cards.get(row.authorUserId), cards, viewer));
        }
        return List.copyOf(out);
    }

    private TopicRow topicByCode(String topicCode) {
        if (topicCode == null || topicCode.isBlank()) return null;
        TopicRow row = store.read(mapper -> mapper.selectTopicByCode(topicCode.strip()));
        return row != null && "ENABLED".equals(row.status) ? row : null;
    }

    private static String viewer(String value) {
        if (value == null || value.isBlank()) return null;
        return Long.toUnsignedString(id(value));
    }

    private static List<String> mediaIds(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_IMAGES) invalid("图片最多 9 张");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) invalid("imageId is invalid");
            ids.add(Long.toUnsignedString(id(value.strip())));
        }
        return List.copyOf(ids);
    }

    private static String jsonList(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception failure) {
            throw new IllegalStateException("image list serialization failed", failure);
        }
    }

    private void audit(com.petplatform.community.biz.infrastructure.persistence.mapper.CommunityMapper mapper,
            String targetType, long targetId, String action, String from, String to,
            CommandContext context, String reason) {
        if (mapper.insertCommunityAudit(store.nextId(), targetType, targetId, action, from, to,
                context.operatorType().name(), operatorId(context), reason, context.requestId(),
                context.traceId(), nowUtc()) != 1) {
            unavailable("社区审计写入失败");
        }
    }
}
