package com.petplatform.review.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.api.query.ReviewQueryApi;
import com.petplatform.review.api.query.ReviewQueryApi.ReviewSummary;
import com.petplatform.review.api.query.ReviewQueryApi.StoreReviewAggregate;
import com.petplatform.review.biz.apiimpl.ReviewApiImpl;
import com.petplatform.review.biz.application.ReviewAppealPorts;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * CCR-M-REVIEW-VIEW-001 (contract 56 §7) OWNER store read side over isolated real MySQL:
 * the full-history rating aggregate (ROUND buckets / score_included=1 exclusions / empty store
 * 0-0-null / cross-store isolation), the buyer nickname/avatar projection on the OWNER
 * list+detail (null identity degradation, no userId/phone anywhere) and the OWNER+store guard
 * (foreign coordinates deny without leaking rows; identity reads fail closed).
 */
class MerchantReviewViewMySqlTest {

    private static final long MERCHANT = 7_410_000_000_000_001L;
    private static final long STORE_A = 7_410_000_000_000_101L;
    private static final long STORE_B = 7_410_000_000_000_102L;
    private static final long NEVER_SEEDED = 7_410_000_000_000_199L;
    private static final long BUYER = 8_410_000_000_000_101L;
    private static final long BUYER_INTERNAL_AVATAR = 8_410_000_000_000_102L;
    private static final long BUYER_ABSENT = 8_410_000_000_000_103L;
    private static final long BUYER_HIDDEN = 8_410_000_000_000_201L;

    @Test
    void aggregateBucketsRoundAndExcludeUnscored() throws Exception {
        try (Database db = new Database()) {
            int seq = 0;
            // ROUND(composite): 4.4->4, 4.5->5, 3.5->4, 2.4->2; the excluded rows (1.0, 5.0)
            // leave the scored count, the average and every bucket untouched.
            db.review(++seq, STORE_A, BUYER, "4.4", true, "PUBLISHED");
            db.review(++seq, STORE_A, BUYER, "4.5", true, "PUBLISHED");
            db.review(++seq, STORE_A, BUYER, "3.5", true, "PUBLISHED");
            db.review(++seq, STORE_A, BUYER, "2.4", true, "PUBLISHED");
            db.review(++seq, STORE_A, BUYER, "1.0", false, "PUBLISHED");
            db.review(++seq, STORE_A, BUYER, "5.0", false, "HIDDEN");
            db.review(++seq, STORE_B, BUYER, "5.0", true, "PUBLISHED");

            StoreReviewAggregate a = db.api().storeReviewAggregate(storeQuery(STORE_A));
            assertEquals(6L, a.totalReviews(), "score_included=0 history stays in totalReviews");
            assertEquals(4L, a.scoredCount());
            assertEquals("3.7", a.averageScore(), "((4.4+4.5+3.5+2.4)/4).setScale(1,HALF_UP)");
            assertEquals(Map.of("1", 0L, "2", 1L, "3", 0L, "4", 2L, "5", 1L), a.scoreCounts());

            StoreReviewAggregate b = db.api().storeReviewAggregate(storeQuery(STORE_B));
            assertEquals(1L, b.totalReviews(), "no cross-store bleed in either direction");
            assertEquals(1L, b.scoredCount());
            assertEquals("5.0", b.averageScore());
            assertEquals(Map.of("1", 0L, "2", 0L, "3", 0L, "4", 0L, "5", 1L), b.scoreCounts());

            StoreReviewAggregate empty = db.api().storeReviewAggregate(storeQuery(NEVER_SEEDED));
            assertEquals(0L, empty.totalReviews());
            assertEquals(0L, empty.scoredCount());
            assertNull(empty.averageScore(), "an empty store never fakes an average");
            assertEquals(Map.of("1", 0L, "2", 0L, "3", 0L, "4", 0L, "5", 0L), empty.scoreCounts());
        }
    }

    @Test
    void hiddenIncludedReviewsStillScoreAndOwnersKeepIdentity() throws Exception {
        try (Database db = new Database()) {
            db.user(BUYER_HIDDEN, "林晓雨", "https://wx.qlogo.cn/face.png");
            db.review(1, STORE_A, BUYER_HIDDEN, "4.8", true, "HIDDEN");

            StoreReviewAggregate aggregate = db.api().storeReviewAggregate(storeQuery(STORE_A));
            assertEquals(1L, aggregate.totalReviews());
            assertEquals(1L, aggregate.scoredCount(), "56号 §4: HIDDEN keeps score_included=1");
            assertEquals("4.8", aggregate.averageScore());

            // OWNER list/detail: HIDDEN stays visible with the full field range + identity.
            ReviewSummary item = db.api().listStoreReviews(listQuery()).items().get(0);
            assertEquals("HIDDEN", item.visibilityStatus());
            assertEquals("林晓雨", item.reviewerNickname());
            assertEquals("https://wx.qlogo.cn/face.png", item.reviewerAvatarUrl());
            var detail = db.api().getStoreReview(new ReviewQueryApi.StoreReviewGetQuery(
                    context(), item.reviewId()));
            assertEquals("林晓雨", detail.review().reviewerNickname());
            assertEquals("https://wx.qlogo.cn/face.png", detail.review().reviewerAvatarUrl());
        }
    }

    @Test
    void ownerListProjectsNicknameAvatarAndDegradesMissingIdentity() throws Exception {
        try (Database db = new Database()) {
            db.user(BUYER, "张伟", "https://wx.qlogo.cn/zw.png");
            // Internal avatar reference form (asset:) never crosses the user domain.
            db.user(BUYER_INTERNAL_AVATAR, "王芳", "asset:900199900001");
            // BUYER_ABSENT has no user_account row at all (66号: data is kept, row may be gone).
            db.review(1, STORE_A, BUYER_ABSENT, "5.0", true, "PUBLISHED");
            db.review(2, STORE_A, BUYER_INTERNAL_AVATAR, "3.0", true, "PUBLISHED");
            db.review(3, STORE_A, BUYER, "4.0", true, "PUBLISHED");

            var page = db.api().listStoreReviews(listQuery());
            assertEquals(3, page.items().size());
            Function<String, ReviewSummary> byReviewId = reviewId -> page.items().stream()
                    .filter(item -> item.reviewId().equals(reviewId)).findFirst().orElseThrow();
            ReviewSummary withAvatar = byReviewId.apply("3");
            assertEquals("张伟", withAvatar.reviewerNickname());
            assertEquals("https://wx.qlogo.cn/zw.png", withAvatar.reviewerAvatarUrl());
            ReviewSummary internalAvatar = byReviewId.apply("2");
            assertEquals("王芳", internalAvatar.reviewerNickname());
            assertNull(internalAvatar.reviewerAvatarUrl(), "asset: never leaves the user domain");
            ReviewSummary anonymous = byReviewId.apply("1");
            assertNull(anonymous.reviewerNickname(), "absent account shows 匿名用户 on the page");
            assertNull(anonymous.reviewerAvatarUrl());
            // The detail projection carries the same identity facts.
            assertEquals("张伟", db.api().getStoreReview(new ReviewQueryApi.StoreReviewGetQuery(
                    context(), "3")).review().reviewerNickname());
        }
    }

    @Test
    void readSideProjectionCarriesNoUserIdOrPhone() {
        // The record itself is the projection boundary: exactly the 56号 twelve keys plus the
        // two identity keys, never a userId/phone field anywhere on the read side.
        assertEquals(
                Arrays.asList("reviewId", "orderId", "storeScore", "serviceScore", "staffScore",
                        "compositeScore", "scoreIncluded", "visibilityStatus", "content",
                        "createdAt", "appealStatus", "appealId",
                        "reviewerNickname", "reviewerAvatarUrl"),
                Arrays.stream(ReviewSummary.class.getRecordComponents())
                        .map(c -> c.getName()).toList());
    }

    @Test
    void foreignOwnerCoordinatesDenyWithoutLeakingRows() throws Exception {
        try (Database db = new Database()) {
            db.review(1, STORE_A, BUYER, "4.0", true, "PUBLISHED");

            // A denied OWNER proof refuses the read before any row is served: 403 on both faces.
            ReviewAppealPorts denying = mock(ReviewAppealPorts.class);
            doThrow(new ApiException(CommonApiCodes.FORBIDDEN, "denied")).when(denying)
                    .requireReviewOwnerRead(any(), anyString(), anyString());
            ReviewApiImpl deniedApi = db.api(denying, db.users());
            ApiException forbidden = assertThrows(ApiException.class,
                    () -> deniedApi.storeReviewAggregate(storeQuery(STORE_A)));
            assertEquals(CommonApiCodes.FORBIDDEN, forbidden.code());
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> deniedApi.listStoreReviews(listQuery())).code());

            // Only a MINIAPP USER may read the OWNER face (platform sessions are refused).
            ApiException wrongParty = assertThrows(ApiException.class, () -> db.api()
                    .storeReviewAggregate(new ReviewQueryApi.StoreReviewAggregateQuery(
                            new CommandContext(null, "trace", OperatorType.PLATFORM_OPERATOR,
                                    "op", "ADMIN_WEB"), Long.toString(MERCHANT),
                            Long.toString(STORE_A))));
            assertEquals(CommonApiCodes.FORBIDDEN, wrongParty.code());

            // Malformed coordinates never reach the guard.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> db.api().storeReviewAggregate(
                            new ReviewQueryApi.StoreReviewAggregateQuery(context(), "abc",
                                    Long.toString(STORE_A)))).code());
        }
    }

    @Test
    void missingUserBasicsPortAndUnreadableFactsFailClosed() throws Exception {
        try (Database db = new Database()) {
            db.review(1, STORE_A, BUYER, "4.0", true, "PUBLISHED");

            // Without the user basics port the OWNER reads fail closed (503), never as an
            // all-anonymous page; the aggregate needs no identity and still answers.
            ReviewApiImpl withoutUsers = db.api(db.ports(), null);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> withoutUsers.listStoreReviews(listQuery())).code());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> withoutUsers.getStoreReview(new ReviewQueryApi.StoreReviewGetQuery(
                            context(), "1"))).code());
            assertEquals(1L, db.api().storeReviewAggregate(storeQuery(STORE_A)).totalReviews());

            // An unreadable user fact also fails the read closed.
            UserQueryApi failing = mock(UserQueryApi.class);
            when(failing.listUsers(any())).thenThrow(new IllegalStateException("down"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> db.api(db.ports(), failing).listStoreReviews(listQuery())).code());
        }
    }

    @Test
    void aggregateRidesTheStoreScoreIndex() throws Exception {
        try (Database db = new Database()) {
            // The target store is a small slice of a realistic table, so the optimizer keeps
            // the (store_id, ...) prefix instead of a full scan.
            db.reviews(1_000, STORE_A, BUYER, "4.0", true, 40);
            db.reviews(2_000, STORE_B, BUYER, "3.0", true, 1_000);
            db.jdbc().execute("ANALYZE TABLE review");
            Map<String, Object> plan = db.jdbc().queryForMap(
                    "EXPLAIN SELECT COUNT(*), "
                            + "COALESCE(SUM(CASE WHEN score_included = 1 THEN 1 ELSE 0 END), 0), "
                            + "AVG(CASE WHEN score_included = 1 THEN composite_score END) "
                            + "FROM review WHERE store_id = ? AND merchant_id = ?",
                    STORE_A, MERCHANT);
            assertEquals("idx_review_store_score", String.valueOf(plan.get("key")),
                    "the aggregate must ride (store_id, score_included, created_at): " + plan);
        }
    }

    // ------------------------------------------------------------------ fixture helpers

    private static ReviewQueryApi.StoreReviewAggregateQuery storeQuery(long storeId) {
        return new ReviewQueryApi.StoreReviewAggregateQuery(
                context(), Long.toString(MERCHANT), Long.toString(storeId));
    }

    private static ReviewQueryApi.StoreReviewListQuery listQuery() {
        return new ReviewQueryApi.StoreReviewListQuery(context(),
                Long.toString(MERCHANT), Long.toString(STORE_A), 1, 20);
    }

    private static CommandContext context() {
        return new CommandContext(null, "trace-owner-read", OperatorType.USER,
                "owner-read", "MINIAPP");
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private static final String INSERT_REVIEW = """
                INSERT INTO review(id,order_id,user_id,merchant_id,store_id,service_id,staff_id,
                                   store_score,service_score,staff_score,composite_score,
                                   score_included,content,visibility_status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,NULL,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """;
        private static final String INSERT_USER = """
                INSERT INTO user_account(id,phone,nickname,avatar_url,password_hash,
                                         password_enabled,status,created_at,updated_at)
                VALUES(?,?,?,?,NULL,0,'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """;
        private final String name = "mrevview_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final ScheduleCapacityGuardApi guard = mock(ScheduleCapacityGuardApi.class);
        private final ReviewAppealPorts ports = mock(ReviewAppealPorts.class);
        private final UserQueryApi users = mock(UserQueryApi.class);
        /** The basics answer reads this live map, so seeds may land before or after stubbing. */
        private final Map<String, UserBasicDTO> basics = new LinkedHashMap<>();

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        "AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            when(users.listUsers(any())).thenAnswer(invocation -> {
                // The port takes the UserBasicsQuery record, not a raw list — getArgument with
                // List.class would ClassCastException (surfaced as the 503 fail-closed path).
                UserQueryApi.UserBasicsQuery query = invocation.getArgument(0, UserQueryApi.UserBasicsQuery.class);
                List<String> requested = query.userIds();
                List<UserBasicDTO> out = new ArrayList<>();
                for (String id : requested) {
                    UserBasicDTO basic = basics.get(id);
                    if (basic != null) out.add(basic);
                }
                return out;
            });
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root().resolve(
                                    "docs/03-database/06-核心数据库Schema-v0.1.sql")),
                            StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        ReviewApiImpl api() {
            return api(ports, users);
        }

        ReviewApiImpl api(ReviewAppealPorts appealPorts, UserQueryApi userApi) {
            return new ReviewApiImpl(source, () -> 1L, mock(OrderQueryApi.class),
                    java.time.Clock.systemUTC(), guard, appealPorts, userApi);
        }

        ReviewAppealPorts ports() {
            return ports;
        }

        UserQueryApi users() {
            return users;
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        void review(long id, long storeId, long userId, String composite, boolean included,
                String visibility) {
            jdbc.update(INSERT_REVIEW, id, 9_000_000_000_000_000L + id, userId, MERCHANT,
                    storeId, 7_500L, composite, composite, composite, composite,
                    included ? 1 : 0, "评价内容-" + id, visibility);
        }

        void reviews(int firstId, long storeId, long userId, String composite, boolean included,
                int count) {
            List<Object[]> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = firstId + i;
                rows.add(new Object[] {id, 9_000_000_000_000_000L + id, userId, MERCHANT,
                        storeId, 7_500L, composite, composite, composite, composite,
                        included ? 1 : 0, "评价内容-" + id, "PUBLISHED"});
            }
            jdbc.batchUpdate(INSERT_REVIEW, rows);
        }

        /** Seeds one account row and its batched-basics answer (masked phone per 07号). */
        void user(long id, String nickname, String avatarUrl) {
            jdbc.update(INSERT_USER, id,
                    "138" + String.format("%08d", (int) (id % 100_000_000L)), nickname, avatarUrl);
            basics.put(Long.toUnsignedString(id), new UserBasicDTO(Long.toUnsignedString(id),
                    "138****0000", nickname, null, avatarUrl));
        }

        private static Path root() {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("Repository root with schema 06 was not found");
            }
            return root;
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
