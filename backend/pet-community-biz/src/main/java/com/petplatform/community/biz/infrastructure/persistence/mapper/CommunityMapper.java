package com.petplatform.community.biz.infrastructure.persistence.mapper;

import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.BindingRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.CommentRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.MediaRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.PostRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.ReportRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.TopicRow;
import com.petplatform.community.biz.infrastructure.persistence.entity.CommunityEntities.UserActionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** community_* SQL lives in resources/mapper/CommunityMapper.xml (22号 MyBatis ruling). */
public interface CommunityMapper {

    List<TopicRow> selectEnabledTopics();

    TopicRow selectTopicById(@Param("id") long id);

    TopicRow selectTopicByCode(@Param("topicCode") String topicCode);

    int insertMedia(MediaRow row);

    MediaRow selectMediaById(@Param("id") long id);

    int insertPost(PostRow row);

    PostRow selectPostById(@Param("id") long id);

    PostRow selectPostForUpdate(@Param("id") long id);

    List<PostRow> selectLatestPosts(@Param("topicId") Long topicId, @Param("limit") int limit,
            @Param("offset") long offset);

    long countLatestPosts(@Param("topicId") Long topicId);

    List<PostRow> selectHotPosts(@Param("topicId") Long topicId, @Param("limit") int limit,
            @Param("offset") long offset);

    long countHotPosts(@Param("topicId") Long topicId);

    List<PostRow> selectFollowingPosts(@Param("viewerId") long viewerId,
            @Param("limit") int limit, @Param("offset") long offset);

    long countFollowingPosts(@Param("viewerId") long viewerId);

    List<PostRow> selectMyPosts(@Param("viewerId") long viewerId, @Param("limit") int limit,
            @Param("offset") long offset);

    long countMyPosts(@Param("viewerId") long viewerId);

    List<PostRow> selectFavoritePosts(@Param("viewerId") long viewerId, @Param("limit") int limit,
            @Param("offset") long offset);

    long countFavoritePosts(@Param("viewerId") long viewerId);

    List<PostRow> selectAdminPosts(@Param("status") String status,
            @Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);

    long countAdminPosts(@Param("status") String status, @Param("keyword") String keyword);

    int updatePostStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int incrementLikeCount(@Param("postId") long postId, @Param("delta") int delta,
            @Param("now") LocalDateTime now);

    int incrementFavoriteCount(@Param("postId") long postId, @Param("delta") int delta,
            @Param("now") LocalDateTime now);

    int incrementCommentCount(@Param("postId") long postId, @Param("delta") int delta,
            @Param("now") LocalDateTime now);

    List<CommentRow> selectComments(@Param("postId") long postId, @Param("limit") int limit,
            @Param("offset") long offset);

    long countComments(@Param("postId") long postId);

    int insertComment(CommentRow row);

    CommentRow selectCommentById(@Param("id") long id);

    CommentRow selectCommentForUpdate(@Param("id") long id);

    int updateCommentStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("now") LocalDateTime now);

    long countPostsByAuthor(@Param("authorId") long authorId);

    long countFollowers(@Param("userId") long userId);

    long countFollowing(@Param("userId") long userId);

    UserActionRow selectLike(@Param("postId") long postId, @Param("userId") long userId);

    long countLikes(@Param("postId") long postId);

    int insertLike(@Param("id") long id, @Param("postId") long postId,
            @Param("userId") long userId, @Param("now") LocalDateTime now);

    int deleteLike(@Param("postId") long postId, @Param("userId") long userId);

    UserActionRow selectFavorite(@Param("postId") long postId, @Param("userId") long userId);

    long countFavorites(@Param("postId") long postId);

    int insertFavorite(@Param("id") long id, @Param("postId") long postId,
            @Param("userId") long userId, @Param("now") LocalDateTime now);

    int deleteFavorite(@Param("postId") long postId, @Param("userId") long userId);

    UserActionRow selectFollow(@Param("followerId") long followerId,
            @Param("followeeId") long followeeId);

    int insertFollow(@Param("id") long id, @Param("followerId") long followerId,
            @Param("followeeId") long followeeId, @Param("now") LocalDateTime now);

    int deleteFollow(@Param("followerId") long followerId,
            @Param("followeeId") long followeeId);

    ReportRow selectReportByTargetReporter(@Param("targetType") String targetType,
            @Param("targetId") long targetId, @Param("reporterId") long reporterId);

    int insertReport(ReportRow row);

    ReportRow selectReportForUpdate(@Param("id") long id);

    List<ReportRow> selectReports(@Param("status") String status, @Param("limit") int limit,
            @Param("offset") long offset);

    long countReports(@Param("status") String status);

    int updateReportStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("handledBy") long handledBy,
            @Param("note") String note, @Param("now") LocalDateTime now);

    int insertCommunityAudit(@Param("id") long id, @Param("targetType") String targetType,
            @Param("targetId") long targetId, @Param("actionType") String actionType,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("operatorType") String operatorType, @Param("operatorId") long operatorId,
            @Param("reason") String reason, @Param("requestId") String requestId,
            @Param("traceId") String traceId, @Param("now") LocalDateTime now);

    BindingRow selectBinding(@Param("requestKey") String requestKey);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("commandType") String commandType, @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical, @Param("now") LocalDateTime now);

    int markBindingSucceeded(@Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson, @Param("now") LocalDateTime now);

    int deleteBinding(@Param("requestKey") String requestKey);
}
