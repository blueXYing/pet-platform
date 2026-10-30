package com.petplatform.community.api;

import com.petplatform.common.CommandContext;
import com.petplatform.community.api.dto.CommunityViews;
import java.util.List;

/**
 * CCR-COM-001 public community face. Reads are anonymous-browsable; every write carries a
 * terminal requestId and the authenticated MINIAPP user id resolved by the boot adapter.
 */
public interface CommunityPublicApi {

    CommunityViews.Page<CommunityViews.PostCard> listPosts(PostFeedQuery query);

    CommunityViews.PostCard getPost(PostQuery query);

    CommunityViews.Page<CommunityViews.CommentView> listComments(CommentListQuery query);

    List<CommunityViews.TopicView> listTopics();

    List<CommunityViews.PostCard> listHotPosts(HotPostsQuery query);

    CommunityViews.UserProfile getUserProfile(UserProfileQuery query);

    CommunityViews.MediaContent media(String mediaId);

    CommunityViews.MediaView uploadMedia(UploadMediaCommand command);

    CommunityViews.Receipt createPost(CreatePostCommand command);

    CommunityViews.Receipt deletePost(DeletePostCommand command);

    CommunityViews.InteractionResult likePost(PostInteractionCommand command);

    CommunityViews.InteractionResult unlikePost(PostInteractionCommand command);

    CommunityViews.InteractionResult favoritePost(PostInteractionCommand command);

    CommunityViews.InteractionResult unfavoritePost(PostInteractionCommand command);

    CommunityViews.Receipt followUser(FollowCommand command);

    CommunityViews.Receipt unfollowUser(FollowCommand command);

    CommunityViews.Receipt createComment(CreateCommentCommand command);

    CommunityViews.Receipt deleteComment(DeleteCommentCommand command);

    CommunityViews.Receipt report(ReportCommand command);

    record PostFeedQuery(String scope, String topicCode, int page, int pageSize, String viewerUserId) {}

    record PostQuery(String postId, String viewerUserId) {}

    record CommentListQuery(String postId, int page, int pageSize, String viewerUserId) {}

    record HotPostsQuery(int limit, String viewerUserId) {}

    record UserProfileQuery(String userId, String viewerUserId) {}

    record UploadMediaCommand(byte[] content, String contentType, String userId, CommandContext context) {
        public UploadMediaCommand {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record CreatePostCommand(String userId, String title, String content, List<String> mediaIds,
            String topicCode, CommandContext context) {}

    record DeletePostCommand(String postId, String userId, CommandContext context) {}

    record PostInteractionCommand(String postId, String userId, CommandContext context) {}

    record FollowCommand(String targetUserId, String userId, CommandContext context) {}

    record CreateCommentCommand(String postId, String userId, String content, CommandContext context) {}

    record DeleteCommentCommand(String commentId, String userId, CommandContext context) {}

    record ReportCommand(String targetType, String targetId, String userId, String reasonCode,
            String reasonText, CommandContext context) {}
}
