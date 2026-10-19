package com.petplatform.community.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.BreedStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CategoryStatusCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CreateBreedCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.CreateCategoryCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateBreedCommand;
import com.petplatform.community.api.command.AdminEncyclopediaCommandApi.UpdateCategoryCommand;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedDetailQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminBreedListQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminCategoryListQuery;
import com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminExportQuery;
import com.petplatform.community.biz.application.AdminEncyclopediaAuthorizationPort;
import com.petplatform.community.biz.application.AdminEncyclopediaService;
import com.petplatform.community.biz.infrastructure.persistence.EncyclopediaAdminStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-65 encyclopedia governance kernel over a real MySQL (SQL 62 tables + 14 shared
 * idempotency): all-status reads with counters, version-guarded maintenance writes, state
 * transition guards (409 on re-transition), requestId idempotency (same-parameter replay
 * returns the first receipt, cross-parameter answers IDEMPOTENCY_KEY_CONFLICT) and the
 * fail-closed authorization discipline. Test-only JDBC here; production SQL stays in
 * EncyclopediaAdminMapper.xml.
 */
class AdminEncyclopediaMySqlTest {
    private static final java.time.OffsetDateTime CHECKED =
            java.time.OffsetDateTime.parse("2026-10-16T00:00:00.000Z");
    /** Query-surface authorization (AdminEncyclopediaQueryApi record component). */
    private static final com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization AUTH =
            new com.petplatform.community.api.query.AdminEncyclopediaQueryApi.AdminAuthorization(
                    "9007199254740993", 1);
    /** Command-surface authorization (AdminEncyclopediaCommandApi record component). */
    private static final com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization WRITE_AUTH =
            new com.petplatform.community.api.command.AdminEncyclopediaCommandApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final QueryContext QUERY = new QueryContext("t0", OperatorType.PLATFORM_OPERATOR, "9007199254740993");

    @Test void categoriesListCountersAndMaintenanceRoundTrip() throws Exception {
        try (Database d = new Database()) {
            var page = d.api().listCategories(new AdminCategoryListQuery(1, 20, QUERY, AUTH));
            assertEquals(5, page.total());
            var dog = page.items().get(0);
            assertEquals("DOG", dog.categoryCode());
            assertEquals(0, dog.breedCount());
            assertEquals(0L, dog.version());

            d.seedBreed(8101L, 710001L, "贵宾犬", "PUBLISHED");
            d.seedBreed(8102L, 710001L, "金毛寻回犬", "OFFLINE");
            var withCounts = d.api().listCategories(new AdminCategoryListQuery(1, 20, QUERY, AUTH)).items().get(0);
            assertEquals(2, withCounts.breedCount());
            assertEquals(1, withCounts.publishedBreedCount());

            var updated = d.commands().updateCategory(new UpdateCategoryCommand("DOG", "狗狗", 9,
                    0, command("r1"), WRITE_AUTH));
            assertEquals("狗狗", updated.categoryName());
            assertEquals(9, updated.sortNo());
            assertEquals(1L, updated.version());

            // expectedVersion guard: a stale version answers 409.
            ApiException stale = assertThrows(ApiException.class,
                    () -> d.commands().updateCategory(new UpdateCategoryCommand("DOG", "狗狗", 1,
                            0, command("r2"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, stale.code());

            var disabled = d.commands().disableCategory(new CategoryStatusCommand("DOG", 1, command("r3"), WRITE_AUTH));
            assertEquals("DISABLED", disabled.status());
            assertEquals(2L, disabled.version());
            // Re-disable is a state-guard miss → 409 (version moved but status guard fires).
            ApiException repeat = assertThrows(ApiException.class,
                    () -> d.commands().disableCategory(new CategoryStatusCommand("DOG", 2, command("r4"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, repeat.code());
            var enabled = d.commands().enableCategory(new CategoryStatusCommand("DOG", 2, command("r5"), WRITE_AUTH));
            assertEquals("ENABLED", enabled.status());
        }
    }

    @Test void breedReadsWritesAndOfflineSemanticsFollowTheContract() throws Exception {
        try (Database d = new Database()) {
            d.seedBreed(8101L, 710001L, "贵宾犬", "PUBLISHED");
            d.seedBreed(8102L, 710001L, "金毛寻回犬", "OFFLINE");

            var all = d.api().listBreeds(new AdminBreedListQuery(null, "DOG", null, 1, 20, QUERY, AUTH));
            assertEquals(2, all.total());
            assertEquals(1, d.api().listBreeds(new AdminBreedListQuery("PUBLISHED", "DOG", null, 1, 20, QUERY, AUTH)).total());
            assertEquals(1, d.api().listBreeds(new AdminBreedListQuery("OFFLINE", "DOG", "金毛", 1, 20, QUERY, AUTH)).total());

            // Admin detail shows OFFLINE rows (unlike the C-end 404 semantics).
            var offline = d.api().getBreed(new AdminBreedDetailQuery("8102", QUERY, AUTH));
            assertEquals("OFFLINE", offline.status());
            assertEquals("金毛寻回犬", offline.name());

            var maintained = d.commands().updateBreed(new UpdateBreedCommand("8101",
                    "贵宾犬简介更新", null, "小型", List.of("泰迪"), List.of("新手友好"), 5, 0,
                    command("b1"), WRITE_AUTH));
            assertEquals(1L, maintained.version());
            assertEquals(5, maintained.sortNo());

            var offlineNow = d.commands().offlineBreed(new BreedStatusCommand("8101", 1, command("b2"), WRITE_AUTH));
            assertEquals("OFFLINE", offlineNow.status());
            // C-end semantics stay untouched: the admin face moved the row, the C read face
            // answers 404 for offline ids — covered by the contract-62 tests, not re-proven here.

            var published = d.commands().publishBreed(new BreedStatusCommand("8101", 2, command("b3"), WRITE_AUTH));
            assertEquals("PUBLISHED", published.status());

            ApiException badVersion = assertThrows(ApiException.class,
                    () -> d.commands().publishBreed(new BreedStatusCommand("8101", 6, command("b4"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, badVersion.code());
        }
    }

    @Test void requestIdIdempotencyBindsSameParamsAndConflictsCrossParams() throws Exception {
        try (Database d = new Database()) {
            d.seedBreed(8101L, 710001L, "贵宾犬", "PUBLISHED");
            var first = d.commands().offlineBreed(new BreedStatusCommand("8101", 0, command("same-1"), WRITE_AUTH));
            assertEquals("OFFLINE", first.status());
            // Same requestId + same params → the first receipt replays verbatim.
            var replay = d.commands().offlineBreed(new BreedStatusCommand("8101", 0, command("same-1"), WRITE_AUTH));
            assertEquals(first, replay);
            // Same requestId + different params → 409 IDEMPOTENCY_KEY_CONFLICT.
            ApiException conflict = assertThrows(ApiException.class,
                    () -> d.commands().offlineBreed(new BreedStatusCommand("8101", 1, command("same-1"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
            // The binding stored exactly one row for the namespace+scope+requestId.
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency WHERE request_key LIKE 'rk-v1:encyclopedia.breed.offline:%same-1'",
                    Integer.class));
        }
    }

    @Test void creationAndExportFollowTheV04Contract() throws Exception {
        try (Database d = new Database()) {
            // Category creation: a new dictionary code, born ENABLED with version 0.
            var created = d.commands().createCategory(new CreateCategoryCommand("RABBIT", "兔子", 6,
                    command("c1"), WRITE_AUTH));
            assertEquals("RABBIT", created.categoryCode());
            assertEquals("ENABLED", created.status());
            assertEquals(0L, created.version());
            // Same requestId + same params replays the first receipt.
            assertEquals(created, d.commands().createCategory(new CreateCategoryCommand("RABBIT",
                    "兔子", 6, command("c1"), WRITE_AUTH)));
            // Duplicate code and bad lexemes are rejected before any write.
            ApiException duplicate = assertThrows(ApiException.class,
                    () -> d.commands().createCategory(new CreateCategoryCommand("RABBIT", "兔兔", 7,
                            command("c2"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, duplicate.code());
            ApiException lexeme = assertThrows(ApiException.class,
                    () -> d.commands().createCategory(new CreateCategoryCommand("rabbit", "兔兔", 7,
                            command("c3"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, lexeme.code());
            // The enum is open: the edit face addresses the new code directly.
            var updated = d.commands().updateCategory(new UpdateCategoryCommand("RABBIT", "兔宝宝", 7,
                    0, command("c4"), WRITE_AUTH));
            assertEquals(1L, updated.version());

            // Breed creation under an ENABLED category: born OFFLINE (maintain-then-publish).
            var breed = d.commands().createBreed(new CreateBreedCommand("RABBIT", "荷兰垂耳兔",
                    "垂耳兔简介", null, "小型", List.of("垂耳"), List.of("新手友好"), 1,
                    command("b1"), WRITE_AUTH));
            assertEquals("OFFLINE", breed.status());
            assertEquals(0L, breed.version());
            // Duplicate name within the same category → 409; disabled host category → 409.
            ApiException dupBreed = assertThrows(ApiException.class,
                    () -> d.commands().createBreed(new CreateBreedCommand("RABBIT", "荷兰垂耳兔",
                            "另一简介", null, null, List.of(), List.of(), 2, command("b2"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, dupBreed.code());
            d.commands().disableCategory(new CategoryStatusCommand("RABBIT", 1, command("c5"), WRITE_AUTH));
            ApiException hostDisabled = assertThrows(ApiException.class,
                    () -> d.commands().createBreed(new CreateBreedCommand("RABBIT", "侏儒兔",
                            "侏儒兔简介", null, null, List.of(), List.of(), 3, command("b3"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, hostDisabled.code());

            // Export: the bounded CSV carries both sections and the new dictionary rows.
            var export = d.api().exportAll(new AdminExportQuery(QUERY, AUTH));
            assertTrue(export.fileName().matches("encyclopedia-export-.*Z[.]csv"));
            assertTrue(export.csv().length <= AdminEncyclopediaService.EXPORT_MAX_BYTES);
            String csv = new String(export.csv(), StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("# "));
            assertTrue(csv.contains("categoryCode,categoryName,status,sortNo,breedCount,publishedBreedCount,"));
            assertTrue(csv.contains("breedId,categoryCode,breedName,aliases,tags,summary,"));
            assertTrue(csv.contains("RABBIT"));
            assertTrue(csv.contains("荷兰垂耳兔"));
            assertTrue(csv.contains("DOG"));
        }
    }

    @Test void authorizationPortReprovesAndFailsClosed() throws Exception {
        try (Database d = new Database()) {
            d.seedBreed(8101L, 710001L, "贵宾犬", "PUBLISHED");
            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true).listCategories(new AdminCategoryListQuery(1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, false).listBreeds(new AdminBreedListQuery(null, null, null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
        }
    }

    private static CommandContext command(String requestId) {
        return new CommandContext(requestId, "t-" + requestId, OperatorType.PLATFORM_OPERATOR,
                "9007199254740993", "ADMIN_WEB");
    }

    private static final class Database implements AutoCloseable {
        final String name = "admency_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(9_500_000_000_000_000L);

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADMENCY_MYSQL_URL") ? "ADMENCY"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADMENCY";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/62-Baike-Content-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                }
                // SQL62 ships the real V1 seed content (five categories + curated breeds);
                // drop the breeds so every test starts from a deterministic empty table while
                // the closed five-code category dictionary stays seeded.
                jdbc.update("DELETE FROM encyclopedia_breed");
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminEncyclopediaService api() { return api(true, true); }

        /** Collection entry / availability switches for the authorization port. */
        AdminEncyclopediaService api(boolean collectionAllowed, boolean available) {
            return new AdminEncyclopediaService(
                new EncyclopediaAdminStore(source, ids::incrementAndGet),
                new AdminEncyclopediaAuthorizationPort.Port() {
                    @Override
                    public AdminEncyclopediaAuthorizationPort.Decision check(
                            AdminEncyclopediaAuthorizationPort.Check check) {
                        if (!available) return null;
                        return new AdminEncyclopediaAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                    }

                    @Override
                    public AdminEncyclopediaAuthorizationPort.Decision checkCollection(
                            AdminEncyclopediaAuthorizationPort.CollectionCheck check) {
                        if (!available) return null;
                        if (!collectionAllowed) {
                            return new AdminEncyclopediaAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                        }
                        return new AdminEncyclopediaAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                    }
                },
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
        }

        AdminEncyclopediaService commands() { return api(); }

        void seedBreed(long id, long categoryId, String name, String status) {
            jdbc.update("""
                INSERT INTO encyclopedia_breed(id, category_id, name, aliases, tags, summary,
                    cover_url, body_type, facts, radar, description_html, care_advice_html,
                    health_tips_html, status, sort_no, version, created_at, updated_at)
                VALUES (?, ?, ?, '[]', '[]', '简介', NULL, NULL, '[]', '{}', '<p>描述</p>', NULL, NULL,
                    ?, 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, id, categoryId, name, status);
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }

        private static DataSource dataSource(String url, String user, String password) {
            String effective = url.endsWith("/") ? url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
                    : url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC";
            return new DriverManagerDataSource(effective, user, password);
        }
    }
}
