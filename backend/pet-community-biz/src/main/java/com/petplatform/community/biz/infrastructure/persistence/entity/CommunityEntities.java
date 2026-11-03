package com.petplatform.community.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** Mutable MyBatis row projections for the CCR-COM-001 tables. */
public final class CommunityEntities {

    private CommunityEntities() {}

    public static final class TopicRow {
        public Long id;
        public String topicCode;
        public String name;
        public String description;
        public String status;
        public Integer sortWeight;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class MediaRow {
        public Long id;
        public Long ownerUserId;
        public String contentType;
        public Long bytes;
        public Integer width;
        public Integer height;
        public String sha256;
        public String objectKey;
        public byte[] content;
        public String status;
        public LocalDateTime createdAt;
    }

    public static final class PostRow {
        public Long id;
        public Long authorUserId;
        public String title;
        public String content;
        public Long topicId;
        public String imageIds;
        public String visibility;
        public String status;
        public Integer likeCount;
        public Integer commentCount;
        public Integer favoriteCount;
        public Integer viewCount;
        public Long version;
        public LocalDateTime publishedAt;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public String topicCode;
        public String topicName;
        public String topicDescription;
        public Integer topicSortWeight;
    }

    public static final class CommentRow {
        public Long id;
        public Long postId;
        public Long authorUserId;
        public String content;
        public String status;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class ReportRow {
        public Long id;
        public String targetType;
        public Long targetId;
        public Long reporterUserId;
        public String reasonCode;
        public String reasonText;
        public String status;
        public Long handledBy;
        public LocalDateTime handledAt;
        public String handlingNote;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class BindingRow {
        public Long id;
        public String requestKey;
        public String commandType;
        public String paramsSha256;
        public byte[] paramsCanonical;
        public String status;
        public String receiptJson;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class UserActionRow {
        public Long id;
        public Long targetId;
        public LocalDateTime createdAt;
    }
}
