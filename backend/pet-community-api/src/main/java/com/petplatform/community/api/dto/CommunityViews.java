package com.petplatform.community.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CCR-COM-001 public/admin projections. All ids are decimal Snowflake Strings and all
 * timestamps are ISO-8601 UTC with millisecond precision.
 */
public final class CommunityViews {

    private CommunityViews() {}

    public record Page<T>(List<T> items, int page, int pageSize, long total) {}

    public record AuthorView(String userId, String nickname, String avatarUrl) {}

    public record TopicView(String topicCode, String name, String description, int sortOrder) {}

    public record PostCard(
            String postId,
            AuthorView author,
            String title,
            String content,
            List<String> imageUrls,
            TopicView topic,
            int likeCount,
            int commentCount,
            int favoriteCount,
            int viewCount,
            boolean liked,
            boolean favorited,
            boolean followingAuthor,
            OffsetDateTime publishedAt) {}

    public record CommentView(
            String commentId,
            AuthorView author,
            String content,
            OffsetDateTime createdAt) {}

    public record UserProfile(
            AuthorView author,
            long postCount,
            long followerCount,
            long followingCount,
            boolean following) {}

    public record Receipt(
            String id,
            String status,
            boolean created,
            OffsetDateTime occurredAt) {}

    public record MediaView(
            String mediaId,
            String url,
            String contentType,
            long bytes,
            int width,
            int height,
            String sha256) {}

    public record MediaContent(byte[] content, String contentType, String sha256) {
        public MediaContent {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    public record InteractionResult(
            String postId,
            boolean active,
            int count,
            boolean changed) {}

    /** Admin post row includes moderation state and the author projection. */
    public record AdminPostView(
            String postId,
            AuthorView author,
            String title,
            String content,
            List<String> imageUrls,
            TopicView topic,
            int likeCount,
            int commentCount,
            int favoriteCount,
            int viewCount,
            String visibility,
            String status,
            long version,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record AdminReportView(
            String reportId,
            String targetType,
            String targetId,
            String reporterUserId,
            String reporterNickname,
            String reasonCode,
            String reasonText,
            String status,
            String targetPreview,
            OffsetDateTime createdAt,
            OffsetDateTime handledAt,
            String handlingNote) {}
}
