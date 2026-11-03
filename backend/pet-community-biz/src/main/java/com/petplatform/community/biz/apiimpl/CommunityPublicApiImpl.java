package com.petplatform.community.biz.apiimpl;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.CommunityPublicService;
import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort;
import com.petplatform.community.biz.infrastructure.persistence.CommunityStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Local implementation of contract 73 public community reads/writes. */
public final class CommunityPublicApiImpl implements CommunityPublicApi {

    private final CommunityPublicService service;

    public CommunityPublicApiImpl(DataSource dataSource, SnowflakeIdGenerator ids,
            CommunityUserDirectoryPort users, CommunityMediaObjectStore mediaStore, Clock clock,
            String mediaBaseUrl) {
        this.service = new CommunityPublicService(
                new CommunityStore(Objects.requireNonNull(dataSource, "dataSource is required"),
                        Objects.requireNonNull(ids, "ids are required")),
                Objects.requireNonNull(users, "users are required"),
                Objects.requireNonNull(mediaStore, "mediaStore is required"), clock, mediaBaseUrl);
    }

    @Override public CommunityViews.Page<CommunityViews.PostCard> listPosts(PostFeedQuery query) { return service.listPosts(query); }
    @Override public CommunityViews.PostCard getPost(PostQuery query) { return service.getPost(query); }
    @Override public CommunityViews.Page<CommunityViews.CommentView> listComments(CommentListQuery query) { return service.listComments(query); }
    @Override public List<CommunityViews.TopicView> listTopics() { return service.listTopics(); }
    @Override public List<CommunityViews.PostCard> listHotPosts(HotPostsQuery query) { return service.listHotPosts(query); }
    @Override public CommunityViews.UserProfile getUserProfile(UserProfileQuery query) { return service.getUserProfile(query); }
    @Override public CommunityViews.MediaContent media(String mediaId) { return service.media(mediaId); }
    @Override public CommunityViews.MediaView uploadMedia(UploadMediaCommand command) { return service.uploadMedia(command); }
    @Override public CommunityViews.Receipt createPost(CreatePostCommand command) { return service.createPost(command); }
    @Override public CommunityViews.Receipt deletePost(DeletePostCommand command) { return service.deletePost(command); }
    @Override public CommunityViews.InteractionResult likePost(PostInteractionCommand command) { return service.likePost(command); }
    @Override public CommunityViews.InteractionResult unlikePost(PostInteractionCommand command) { return service.unlikePost(command); }
    @Override public CommunityViews.InteractionResult favoritePost(PostInteractionCommand command) { return service.favoritePost(command); }
    @Override public CommunityViews.InteractionResult unfavoritePost(PostInteractionCommand command) { return service.unfavoritePost(command); }
    @Override public CommunityViews.Receipt followUser(FollowCommand command) { return service.followUser(command); }
    @Override public CommunityViews.Receipt unfollowUser(FollowCommand command) { return service.unfollowUser(command); }
    @Override public CommunityViews.Receipt createComment(CreateCommentCommand command) { return service.createComment(command); }
    @Override public CommunityViews.Receipt deleteComment(DeleteCommentCommand command) { return service.deleteComment(command); }
    @Override public CommunityViews.Receipt report(ReportCommand command) { return service.report(command); }
}
