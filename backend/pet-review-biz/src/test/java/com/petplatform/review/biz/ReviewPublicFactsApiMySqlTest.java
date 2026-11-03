package com.petplatform.review.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.api.query.ReviewPublicFactsApi;
import com.petplatform.review.api.query.ReviewPublicFactsApi.StoreRatingFact;
import com.petplatform.review.api.query.ReviewPublicFactsApi.StoreRatingQuery;
import com.petplatform.review.biz.apiimpl.ReviewApiImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * B-STAT-01 public rating facts over isolated real MySQL (74号 服务目录页契约 §6.1 /
 * CCR-SVC-CARD-001): batch store aggregates over ALL score_included=1 composite scores,
 * one-decimal 0.0..5.0 averages, explicit count=0/avg=null for stores without included
 * reviews, no OWNER/session context and no review content/user/order facts in the projection.
 */
class ReviewPublicFactsApiMySqlTest {

    private static final long STORE_A = 7_410_000_000_000_101L;
    private static final long STORE_B = 7_410_000_000_000_102L;
    private static final long STORE_C = 7_410_000_000_000_103L;
    private static final long NEVER_SEEDED = 7_410_000_000_000_199L;
    private static final long USER = 8_410_000_000_000_101L;

    @Test
    void batchCoversRequestedStoresAndOnlyIncludedScores() throws Exception {
        try (Database db = new Database()) {
            int seq = 0;
            db.review(++seq, STORE_A, "4.0", true, "PUBLISHED");
            db.review(++seq, STORE_A, "5.0", true, "PUBLISHED");
            db.review(++seq, STORE_A, "1.0", false, "PUBLISHED"); // score excluded
            db.review(++seq, STORE_B, "1.0", false, "PUBLISHED"); // only an excluded review

            List<StoreRatingFact> facts = db.api().getStoreRatings(
                    new StoreRatingQuery(ids(STORE_A, STORE_B, STORE_C, NEVER_SEEDED)));

            assertEquals(4, facts.size(), "one fact per requested store");
            assertEquals(ids(STORE_A, STORE_B, STORE_C, NEVER_SEEDED),
                    facts.stream().map(StoreRatingFact::storeId).toList(), "request order");
            StoreRatingFact a = facts.get(0);
            assertEquals(STORE_A, Long.parseLong(a.storeId()));
            assertEquals(2L, a.reviewCount(), "excluded review is not counted");
            assertEquals("4.5", a.avgScore());
            for (StoreRatingFact empty : List.of(facts.get(1), facts.get(2), facts.get(3))) {
                assertEquals(0L, empty.reviewCount());
                assertNull(empty.avgScore(), "no included review returns null, never a fake 0 score");
            }
        }
    }

    @Test
    void averagesKeepOneDecimalZeroToFive() throws Exception {
        try (Database db = new Database()) {
            int seq = 0;
            // 4.45 -> HALF_UP -> 4.5 (the stored composite is DECIMAL(3,1)).
            db.review(++seq, STORE_A, "4.4", true, "PUBLISHED");
            db.review(++seq, STORE_A, "4.5", true, "PUBLISHED");
            db.review(++seq, STORE_B, "5.0", true, "PUBLISHED");
            db.review(++seq, STORE_B, "4.9", true, "PUBLISHED");
            db.review(++seq, STORE_B, "4.8", true, "PUBLISHED");
            db.review(++seq, STORE_C, "1.0", true, "PUBLISHED");

            List<StoreRatingFact> facts = db.api().getStoreRatings(
                    new StoreRatingQuery(ids(STORE_A, STORE_B, STORE_C)));

            assertEquals("4.5", facts.get(0).avgScore());
            assertEquals("4.9", facts.get(1).avgScore());
            assertEquals("1.0", facts.get(2).avgScore(), "one decimal is kept");
            for (StoreRatingFact fact : facts) {
                assertTrue(fact.avgScore() != null && fact.avgScore().matches("[0-5]\\.[0-9]"),
                        "wire average must be one decimal 0.0..5.0: " + fact.avgScore());
            }
        }
    }

    @Test
    void emptyAndDuplicateRequestsStayTight() throws Exception {
        try (Database db = new Database()) {
            ReviewPublicFactsApi api = db.api();
            assertEquals(List.of(), api.getStoreRatings(new StoreRatingQuery(List.of())));

            db.review(1, STORE_A, "4.0", true, "PUBLISHED");
            List<StoreRatingFact> duplicated = api.getStoreRatings(
                    new StoreRatingQuery(ids(STORE_A, STORE_A, STORE_A)));
            assertEquals(1, duplicated.size(), "duplicates collapse to one fact");
            assertEquals(1L, duplicated.get(0).reviewCount());

            for (String malformed : List.of("", "0", "abc", "12345678901234567890")) {
                ApiException failure = assertThrows(ApiException.class,
                        () -> api.getStoreRatings(new StoreRatingQuery(List.of(malformed))));
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, failure.code());
                if (!malformed.isEmpty()) {
                    assertFalse(failure.getMessage().contains(malformed),
                            "error must not echo the rejected value");
                }
            }
        }
    }

    @Test
    void hiddenReviewsStillCountAndFactsCarryNoPii() throws Exception {
        try (Database db = new Database()) {
            // 56号 §4: an APPROVED appeal hides the review from display but keeps the
            // score_included=1 aggregate semantics (56号 §4 note).
            db.review(1, STORE_A, "4.0", true, "HIDDEN");
            db.review(2, STORE_A, "5.0", true, "PUBLISHED");

            StoreRatingFact fact = db.api().getStoreRatings(
                    new StoreRatingQuery(ids(STORE_A))).get(0);

            assertEquals(2L, fact.reviewCount());
            assertEquals("4.5", fact.avgScore());

            // The public fact shape is exactly storeId/reviewCount/avgScore: no content,
            // user id, order id or any other review coordinate.
            assertEquals(List.of("storeId", "reviewCount", "avgScore"),
                    Arrays.stream(StoreRatingFact.class.getRecordComponents())
                            .map(c -> c.getName()).toList());
        }
    }

    @Test
    void batchAggregateHitsTheStoreScoreIndex() throws Exception {
        try (Database db = new Database()) {
            // A realistic table size so the optimizer keeps the covering prefix index for
            // the IN + score_included predicate and the GROUP BY store_id order.
            db.reviews(1_000, STORE_A, "4.0", true, 400);
            db.reviews(2_000, STORE_B, "3.0", true, 100);
            db.reviews(3_000, STORE_C, "2.0", false, 100);
            db.jdbc().execute("ANALYZE TABLE review");

            Map<String, Object> plan = db.jdbc().queryForMap(
                    "EXPLAIN SELECT store_id, COUNT(*), AVG(composite_score) FROM review "
                            + "WHERE store_id IN (?,?,?) AND score_included = 1 GROUP BY store_id",
                    STORE_A, STORE_B, STORE_C);
            assertEquals("idx_review_store_score", String.valueOf(plan.get("key")),
                    "the batch aggregate must ride (store_id, score_included, created_at): " + plan);
        }
    }

    @Test
    void damagedFactReadFailsClosedInsteadOfFakeData() throws Exception {
        try (Database db = new Database()) {
            db.jdbc().execute("DROP TABLE review");
            ApiException failure = assertThrows(ApiException.class,
                    () -> db.api().getStoreRatings(new StoreRatingQuery(ids(STORE_A))));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code(),
                    "an unreadable aggregate is a 503, never count=0/avg=null");
        }
    }

    @Test
    void factsFacadeIsTheAssembledReviewApi() throws Exception {
        try (Database db = new Database()) {
            ReviewPublicFactsApi api = db.api();
            assertTrue(api instanceof ReviewApiImpl,
                    "the assembled review bean must expose the public facts port");
            assertTrue(Arrays.stream(ReviewApiImpl.class.getInterfaces())
                            .anyMatch(type -> type == ReviewPublicFactsApi.class),
                    "ReviewApiImpl implements ReviewPublicFactsApi for bean exposure");
        }
    }

    private static List<String> ids(long... values) {
        return Arrays.stream(values).mapToObj(Long::toString).toList();
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private static final String INSERT_REVIEW = """
                INSERT INTO review(id,order_id,user_id,merchant_id,store_id,service_id,staff_id,
                                   store_score,service_score,staff_score,composite_score,
                                   score_included,content,visibility_status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,NULL,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """;
        private final String name = "revpublfact_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33307/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        "AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "e2e");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "e2e");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root().resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
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

        ReviewPublicFactsApi api() {
            return new ReviewApiImpl(source, () -> 1L, mock(OrderQueryApi.class));
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        void review(long id, long storeId, String composite, boolean included, String visibility) {
            jdbc.update(INSERT_REVIEW, reviewArgs(id, storeId, composite, included, visibility));
        }

        void reviews(int firstId, long storeId, String composite, boolean included, int count) {
            List<Object[]> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(reviewArgs(firstId + i, storeId, composite, included, "PUBLISHED"));
            }
            jdbc.batchUpdate(INSERT_REVIEW, rows);
        }

        private static Object[] reviewArgs(
                long id, long storeId, String composite, boolean included, String visibility) {
            long orderId = 9_000_000_000_000_000L + id;
            return new Object[] {id, orderId, USER, 7_400L, storeId, 7_500L,
                    composite, composite, composite, composite, included ? 1 : 0,
                    "评价内容-" + id, visibility};
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
