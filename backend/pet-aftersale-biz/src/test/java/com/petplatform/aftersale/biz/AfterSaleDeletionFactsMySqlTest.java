package com.petplatform.aftersale.biz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.aftersale.biz.apiimpl.AfterSaleDeletionFactsApiImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-66 deletion-blocking fact over isolated real MySQL (PRD 5.1.29 precheck;
 * CCR-USR-DELETION-001): only the domain's own active case set (active_flag=1 with status
 * PENDING / PROCESSING / WAITING_SUPPLEMENT) blocks; terminal and deactivated cases never
 * do; the store scope reads owner stores only; malformed subjects read as "nothing blocks".
 */
class AfterSaleDeletionFactsMySqlTest {

    private static final long USER = 9_510_000_000_005_001L;
    private static final long OTHER = 9_510_000_000_005_002L;
    private static final long STORE = 7001L;
    private static final long OTHER_STORE = 7002L;

    @Test
    void onlyActiveCasesBlock() throws Exception {
        try (Database db = new Database()) {
            AfterSaleDeletionFactsApi api = db.api();
            assertFalse(api.userHasOpenCase(user(USER)), "empty history does not block");
            db.caseRow(7_300_000_000_000_101L, USER, "PENDING", 1, STORE);
            assertTrue(api.userHasOpenCase(user(USER)));
            db.caseRow(7_300_000_000_000_102L, USER, "PROCESSING", 1, STORE);
            db.caseRow(7_300_000_000_000_103L, USER, "WAITING_SUPPLEMENT", 1, STORE);
            assertTrue(api.userHasOpenCase(user(USER)));

            // Terminal and deactivated rows never block, whoever owns them.
            db.caseRow(7_300_000_000_000_201L, OTHER, "RESOLVED", 1, OTHER_STORE);
            db.caseRow(7_300_000_000_000_202L, OTHER, "CLOSED", 1, OTHER_STORE);
            db.caseRow(7_300_000_000_000_203L, OTHER, "WITHDRAWN", 1, OTHER_STORE);
            db.caseRow(7_300_000_000_000_204L, OTHER, "INVALIDATED", 0, OTHER_STORE);
            assertFalse(api.userHasOpenCase(user(OTHER)));
        }
    }

    @Test
    void storeScopeAndMalformedSubjectsStayTight() throws Exception {
        try (Database db = new Database()) {
            AfterSaleDeletionFactsApi api = db.api();
            db.caseRow(7_300_000_000_000_301L, USER, "PENDING", 1, STORE);
            assertTrue(api.storesHaveOpenCase(new AfterSaleDeletionFactsApi.StoreDeletionFactQuery(
                    List.of(Long.toString(STORE)))));
            assertFalse(api.storesHaveOpenCase(new AfterSaleDeletionFactsApi.StoreDeletionFactQuery(
                    List.of(Long.toString(OTHER_STORE)))));
            assertFalse(api.storesHaveOpenCase(new AfterSaleDeletionFactsApi.StoreDeletionFactQuery(
                    List.of())));
            assertFalse(api.storesHaveOpenCase(new AfterSaleDeletionFactsApi.StoreDeletionFactQuery(
                    List.of("not-a-number"))));
            assertFalse(api.userHasOpenCase(new AfterSaleDeletionFactsApi.UserDeletionFactQuery("")));
        }
    }

    private static AfterSaleDeletionFactsApi.UserDeletionFactQuery user(long id) {
        return new AfterSaleDeletionFactsApi.UserDeletionFactQuery(Long.toString(id));
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "afsdelfact_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

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

        AfterSaleDeletionFactsApi api() {
            return new AfterSaleDeletionFactsApiImpl(source);
        }

        void caseRow(long id, long userId, String status, int activeFlag, long storeId) {
            jdbc.update("""
                    INSERT INTO aftersale_case
                    (id,aftersale_no,order_id,user_id,merchant_id,store_id,status,source_stage,
                     description,active_flag,created_at,version)
                    VALUES(?,?,?,?,?,?,?,'VERIFIED','测试售后',?,UTC_TIMESTAMP(3),0)
                    """, id, id, id + 9000, userId, 7100L, storeId, status, activeFlag);
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
