package com.petplatform.community.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.community.api.CommunityAdminApi;
import com.petplatform.community.api.CommunityApiCodes;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort.UserCard;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.CommentRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.PostRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.ReportRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CCR-COM-001 operations moderation kernel. */
public final class CommunityAdminService extends CommunitySupport implements CommunityAdminApi {

    private static final Set<String> POST_STATUSES = Set.of(
            "PENDING_REVIEW", "PUBLISHED", "OFFLINE", "DELETED");
    private static final Set<String> DECISIONS = Set.of("APPROVE", "OFFLINE", "DELETE");
    private static final Set<String> REPORT_DECISIONS = Set.of("RESOLVE", "DISMISS");

    private final CommunityAdminAuthorizationPort authorization;

    public CommunityAdminService(com.petplatform.community.biz.infrastructure.persistence.CommunityStore store,
            CommunityAdminAuthorizationPort authorization,
            com.petplatform.community.biz.application.port.CommunityUserDirectoryPort users,
            com.petplatform.community.biz.application.port.CommunityMediaObjectStore mediaStore,
            java.time.Clock clock, String mediaBaseUrl) {
        super(store, users, mediaStore, clock, mediaBaseUrl);
        this.authorization = Objects.requireNonNull(authorization);
    }

    @Override
    public CommunityViews.Page<CommunityViews.AdminPostView> listPosts(AdminPostListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "community.post.read", "COMMUNITY_POST_LIST", "0");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize(), 100);
        String status = query.status() == null || query.status().isBlank() ? null
                : query.status().strip().toUpperCase();
        if (status != null && !POST_STATUSES.contains(status)) invalid("status is invalid");
        String keyword = optionalText(query.keyword(), 100, "keyword");
        String pattern = keyword == null ? null : "%" + keyword.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return store.read(mapper -> {
            List<PostRow> rows = mapper.selectAdminPosts(status, pattern, pageSize,
                    (long) (page - 1) * pageSize);
            long total = mapper.countAdminPosts(status, pattern);
            List<Long> authorIds = rows.stream().map(row -> row.authorUserId).distinct().toList();
            Map<Long, UserCard> cards = userCards(authorIds);
            List<CommunityViews.AdminPostView> items = rows.stream()
                    .map(row -> adminPost(row, cards.get(row.authorUserId)))
                    .toList();
            return new CommunityViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public CommunityViews.Receipt moderatePost(ModeratePostCommand command) {
        Objects.requireNonNull(command, "command is required");
        String decision = command.decision() == null ? "" : command.decision().strip().toUpperCase();
        if (!DECISIONS.contains(decision)) invalid("decision is invalid");
        String reason = "APPROVE".equals(decision) ? optionalText(command.reason(), 500, "reason")
                : requireText(command.reason(), 1, 500, "reason");
        authorize(command.authorization(), command.context(), "community.post.moderate",
                "COMMUNITY_POST", command.postId());
        long postId = id(command.postId());
        String requestKey = requestKey("community.post.moderate", command.context(), command.postId());
        Map<String, Object> params = Map.of("postId", postId, "decision", decision,
                "reason", reason == null ? "" : reason, "expectedVersion", command.expectedVersion());
        return idempotent(requestKey, "community.post.moderate", params,
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    PostRow row = mapper.selectPostForUpdate(postId);
                    if (row == null) notFound(CommunityApiCodes.POST_NOT_FOUND, "动态不存在");
                    if (row.version == null || row.version != command.expectedVersion()) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "动态版本已变化");
                    }
                    String to;
                    switch (decision) {
                        case "APPROVE" -> {
                            if (!"PENDING_REVIEW".equals(row.status)) {
                                conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "当前状态不能通过审核");
                            }
                            to = "PUBLISHED";
                        }
                        case "OFFLINE" -> {
                            if (!"PUBLISHED".equals(row.status)) {
                                conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "当前状态不能下线");
                            }
                            to = "OFFLINE";
                        }
                        default -> {
                            if ("DELETED".equals(row.status)) {
                                conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "动态已删除");
                            }
                            to = "DELETED";
                        }
                    }
                    if (mapper.updatePostStatus(postId, row.status, to, command.expectedVersion(),
                            nowUtc()) != 1) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "动态状态已变化");
                    }
                    audit(mapper, "POST", postId, decision, row.status, to, command.context(), reason);
                    return new CommunityViews.Receipt(Long.toUnsignedString(postId), to, false,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    @Override
    public CommunityViews.Page<CommunityViews.AdminReportView> listReports(
            AdminReportListQuery query) {
        Objects.requireNonNull(query, "query is required");
        authorizeRead(query.authorization(), "community.report.read",
                "COMMUNITY_REPORT_LIST", "0");
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize(), 100);
        String status = query.status() == null || query.status().isBlank() ? null
                : query.status().strip().toUpperCase();
        if (status != null && !Set.of("PENDING", "RESOLVED", "DISMISSED").contains(status)) {
            invalid("status is invalid");
        }
        return store.read(mapper -> {
            List<ReportRow> rows = mapper.selectReports(status, pageSize,
                    (long) (page - 1) * pageSize);
            long total = mapper.countReports(status);
            List<Long> reporterIds = rows.stream().map(row -> row.reporterUserId).distinct().toList();
            Map<Long, UserCard> cards = userCards(reporterIds);
            List<CommunityViews.AdminReportView> items = new ArrayList<>(rows.size());
            for (ReportRow row : rows) {
                String preview = "POST".equals(row.targetType)
                        ? previewPost(mapper.selectPostById(row.targetId))
                        : previewComment(mapper.selectCommentById(row.targetId));
                UserCard card = cards.get(row.reporterUserId);
                items.add(new CommunityViews.AdminReportView(
                        Long.toUnsignedString(row.id), row.targetType,
                        Long.toUnsignedString(row.targetId), Long.toUnsignedString(row.reporterUserId),
                        card == null ? "宠友" : card.nickname(), row.reasonCode, row.reasonText,
                        row.status, preview, offset(row.createdAt), offset(row.handledAt),
                        row.handlingNote));
            }
            return new CommunityViews.Page<>(items, page, pageSize, total);
        });
    }

    @Override
    public CommunityViews.Receipt resolveReport(ResolveReportCommand command) {
        Objects.requireNonNull(command, "command is required");
        String decision = command.decision() == null ? "" : command.decision().strip().toUpperCase();
        if (!REPORT_DECISIONS.contains(decision)) invalid("decision is invalid");
        String note = requireText(command.note(), 1, 200, "note");
        authorize(command.authorization(), command.context(), "community.report.handle",
                "COMMUNITY_REPORT", command.reportId());
        long reportId = id(command.reportId());
        String requestKey = requestKey("community.report.resolve", command.context(), command.reportId());
        return idempotent(requestKey, "community.report.resolve",
                Map.of("reportId", reportId, "decision", decision, "note", note),
                CommunitySupport::parseReceipt, () -> store.execute(mapper -> {
                    ReportRow row = mapper.selectReportForUpdate(reportId);
                    if (row == null || !"PENDING".equals(row.status)) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "举报已处理或不存在");
                    }
                    String to = "RESOLVE".equals(decision) ? "RESOLVED" : "DISMISSED";
                    if (mapper.updateReportStatus(reportId, "PENDING", to,
                            operatorId(command.context()), note, nowUtc()) != 1) {
                        conflict(CommunityApiCodes.STATE_NOT_ALLOWED, "举报状态已变化");
                    }
                    audit(mapper, "REPORT", reportId, decision, "PENDING", to,
                            command.context(), note);
                    return new CommunityViews.Receipt(Long.toUnsignedString(reportId), to, false,
                            nowOffset());
                }), CommunitySupport::receiptJson);
    }

    // ------------------------------------------------------------------ helpers

    private CommunityViews.AdminPostView adminPost(PostRow row, UserCard card) {
        return new CommunityViews.AdminPostView(
                Long.toUnsignedString(row.id),
                author(card, Long.toUnsignedString(row.authorUserId)),
                row.title, row.content, imageUrls(row),
                row.topicCode == null ? null : new CommunityViews.TopicView(row.topicCode,
                        row.topicName, row.topicDescription,
                        row.topicSortWeight == null ? 0 : row.topicSortWeight),
                row.likeCount == null ? 0 : row.likeCount,
                row.commentCount == null ? 0 : row.commentCount,
                row.favoriteCount == null ? 0 : row.favoriteCount,
                row.viewCount == null ? 0 : row.viewCount,
                row.visibility, row.status, row.version == null ? 0 : row.version,
                offset(row.publishedAt), offset(row.createdAt), offset(row.updatedAt));
    }

    private static String previewPost(PostRow row) {
        if (row == null || "DELETED".equals(row.status)) return "内容已删除";
        String value = row.title == null ? row.content : row.title + " · " + row.content;
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String previewComment(CommentRow row) {
        if (row == null || "DELETED".equals(row.status)) return "评论已删除";
        return row.content.length() > 80 ? row.content.substring(0, 80) : row.content;
    }

    private void authorizeRead(AdminAuthorization auth, String action, String resourceType,
            String resourceId) {
        requireSession(auth);
        CommunityAdminAuthorizationPort.Decision decision = authorization.check(
                new CommunityAdminAuthorizationPort.Check(auth.sessionId(),
                        auth.sessionGeneration(), auth.operatorId(), action, resourceType, resourceId, null));
        requireDecision(decision);
    }

    private void authorize(AdminAuthorization auth, CommandContext context, String action,
            String resourceType, String resourceId) {
        requireSession(auth);
        if (context == null || context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "platform operator context is required");
        }
        try {
            PublicContractChecks.requireCommandRequestId(context);
        } catch (IllegalArgumentException failure) {
            invalid("requestId is invalid");
        }
        CommunityAdminAuthorizationPort.Decision decision = authorization.check(
                new CommunityAdminAuthorizationPort.Check(auth.sessionId(),
                        auth.sessionGeneration(), context.operatorId(), action, resourceType,
                        resourceId, null));
        requireDecision(decision);
    }

    private static void requireSession(AdminAuthorization auth) {
        if (auth == null || auth.sessionId() == null || auth.sessionId().isBlank()
                || auth.sessionGeneration() <= 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "authorization reference is required");
        }
        if (!"ALL".equals(auth.scopeMode()) && !"CITY".equals(auth.scopeMode())) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "社区审核数据范围不足");
        }
    }

    private static void requireDecision(CommunityAdminAuthorizationPort.Decision decision) {
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            unavailable("authorization decision is unavailable");
        }
        if (!decision.allowed()) throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
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
