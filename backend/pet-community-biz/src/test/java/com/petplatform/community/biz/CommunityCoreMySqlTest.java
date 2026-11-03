package com.petplatform.community.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.community.api.CommunityAdminApi;
import com.petplatform.community.api.CommunityApiCodes;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.api.dto.CommunityViews;
import com.petplatform.community.biz.application.CommunityAdminAuthorizationPort;
import com.petplatform.community.biz.application.CommunityAdminService;
import com.petplatform.community.biz.application.CommunityPublicService;
import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort.UserCard;
import com.petplatform.community.biz.infrastructure.persistence.CommunityStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** CCR-COM-001 kernel over isolated real MySQL. */
class CommunityCoreMySqlTest {

    private static final long USER1 = 9_500_000_000_000_001L;
    private static final long USER2 = 9_500_000_000_000_002L;

    private static final class Users implements CommunityUserDirectoryPort {
        @Override public Map<String, UserCard> lookup(List<String> userIds) {
            return Map.of(
                    Long.toUnsignedString(USER1), new UserCard(Long.toUnsignedString(USER1), "林小姐", null),
                    Long.toUnsignedString(USER2), new UserCard(Long.toUnsignedString(USER2), "王店长", null));
        }
    }

    private static final CommunityMediaObjectStore NO_OSS = new CommunityMediaObjectStore() {
        @Override public boolean enabled() { return false; }
        @Override public void put(String objectKey, byte[] content, String contentType) { throw new UnsupportedOperationException(); }
        @Override public byte[] get(String objectKey) { throw new UnsupportedOperationException(); }
    };

    private static final class AllowAll implements CommunityAdminAuthorizationPort {
        @Override public Decision check(Check check) {
            return new Decision(true, "test:1", OffsetDateTime.now());
        }
    }

    private static CommunityPublicService service(MySqlCommunityTestDatabase db, AtomicLong ids) {
        return new CommunityPublicService(new CommunityStore(db.dataSource(), ids::incrementAndGet),
                new Users(), NO_OSS, Clock.systemUTC(), "");
    }

    private static CommunityAdminService admin(MySqlCommunityTestDatabase db, AtomicLong ids) {
        return new CommunityAdminService(new CommunityStore(db.dataSource(), ids::incrementAndGet),
                new AllowAll(), new Users(), NO_OSS, Clock.systemUTC(), "");
    }

    private static CommandContext command(long userId) {
        return new CommandContext(UUID.randomUUID().toString(), "trace", OperatorType.USER,
                Long.toUnsignedString(userId), "MINIAPP");
    }

    private static CommandContext adminCommand() {
        return new CommandContext(UUID.randomUUID().toString(), "trace", OperatorType.PLATFORM_OPERATOR,
                "9001", "ADMIN_WEB");
    }

    private static CommunityAdminApi.AdminAuthorization auth() {
        return new CommunityAdminApi.AdminAuthorization("session", 1, "9001", "ALL", List.of());
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test void feedPublishLikeCommentFollowAndReport() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            var ids = new AtomicLong(9_500_000_000_100_000L);
            var service = service(db, ids);
            assertEquals(5, service.listTopics().size());

            CommunityViews.Receipt created = service.createPost(new CommunityPublicApi.CreatePostCommand(
                    Long.toUnsignedString(USER1), "今天的豆豆", "洗得香喷喷，剪了个超可爱的造型！",
                    List.of(), "grooming", command(USER1)));
            assertEquals("PUBLISHED", created.status());
            CommunityViews.Page<CommunityViews.PostCard> feed = service.listPosts(
                    new CommunityPublicApi.PostFeedQuery("LATEST", null, 1, 20, null));
            assertEquals(1, feed.total());
            String postId = feed.items().get(0).postId();
            assertEquals("林小姐", feed.items().get(0).author().nickname());
            assertEquals("grooming", feed.items().get(0).topic().topicCode());

            CommunityViews.InteractionResult liked = service.likePost(
                    new CommunityPublicApi.PostInteractionCommand(postId, Long.toUnsignedString(USER1), command(USER1)));
            assertTrue(liked.active());
            assertTrue(liked.changed());
            assertEquals(1, liked.count());
            // Same requestId replays the first receipt; a different requestId is idempotent too.
            CommunityViews.InteractionResult again = service.likePost(
                    new CommunityPublicApi.PostInteractionCommand(postId, Long.toUnsignedString(USER1), command(USER1)));
            assertFalse(again.changed());
            assertEquals(1, again.count());

            CommunityViews.Receipt comment = service.createComment(new CommunityPublicApi.CreateCommentCommand(
                    postId, Long.toUnsignedString(USER2), "太可爱了！", command(USER2)));
            assertEquals("PUBLISHED", comment.status());
            assertEquals(1, service.listComments(new CommunityPublicApi.CommentListQuery(postId, 1, 20, null)).total());

            CommunityViews.Receipt followed = service.followUser(new CommunityPublicApi.FollowCommand(
                    Long.toUnsignedString(USER2), Long.toUnsignedString(USER1), command(USER1)));
            assertEquals("FOLLOWING", followed.status());
            service.createPost(new CommunityPublicApi.CreatePostCommand(
                    Long.toUnsignedString(USER2), null, "店里新到了一批进口宠物零食",
                    List.of(), null, command(USER2)));
            assertEquals(1, service.getUserProfile(new CommunityPublicApi.UserProfileQuery(
                    Long.toUnsignedString(USER2), Long.toUnsignedString(USER1))).followerCount());
            assertEquals(1, service.listPosts(new CommunityPublicApi.PostFeedQuery(
                    "FOLLOWING", null, 1, 20, Long.toUnsignedString(USER1))).total());

            // A report is once per reporter/target.
            service.report(new CommunityPublicApi.ReportCommand("POST", postId,
                    Long.toUnsignedString(USER2), "SPAM", "广告", command(USER2)));
            ApiException duplicate = assertThrows(ApiException.class, () -> service.report(
                    new CommunityPublicApi.ReportCommand("POST", postId,
                            Long.toUnsignedString(USER2), "OTHER", null, command(USER2))));
            assertEquals(CommunityApiCodes.ALREADY_REPORTED, duplicate.code());
        }
    }

    @Test void sensitivePostWaitsForAdminApprovalAndMediaIsPubliclyReadable() throws Exception {
        try (var db = new MySqlCommunityTestDatabase()) {
            var ids = new AtomicLong(9_500_000_000_200_000L);
            var service = service(db, ids);
            var admin = admin(db, ids);

            CommunityViews.MediaView media = service.uploadMedia(new CommunityPublicApi.UploadMediaCommand(
                    png(), "image/png", Long.toUnsignedString(USER1), command(USER1)));
            assertTrue(media.url().contains("/api/v1/c/community/media/"));
            assertEquals("image/png", service.media(media.mediaId()).contentType());

            CommunityViews.Receipt pending = service.createPost(new CommunityPublicApi.CreatePostCommand(
                    Long.toUnsignedString(USER1), "测试", "这里有赌博内容",
                    List.of(media.mediaId()), null, command(USER1)));
            assertEquals("PENDING_REVIEW", pending.status());
            assertEquals(0, service.listPosts(new CommunityPublicApi.PostFeedQuery(
                    "LATEST", null, 1, 20, null)).total());

            CommunityViews.AdminPostView row = admin.listPosts(
                    new CommunityAdminApi.AdminPostListQuery("PENDING_REVIEW", null, 1, 20,
                            auth(), null)).items().get(0);
            admin.moderatePost(new CommunityAdminApi.ModeratePostCommand(row.postId(), "APPROVE",
                    null, row.version(), auth(), adminCommand()));
            assertEquals(1, service.listPosts(new CommunityPublicApi.PostFeedQuery(
                    "LATEST", null, 1, 20, null)).total());
            assertEquals(1, service.listHotPosts(new CommunityPublicApi.HotPostsQuery(3, null)).size());
        }
    }
}
