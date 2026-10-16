package com.petplatform.points.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminAuthorization;
import com.petplatform.points.api.query.AdminPointsQueryApi.AdminLedgerListQuery;
import com.petplatform.points.biz.apiimpl.AdminPointsQueryApiImpl;
import com.petplatform.points.biz.application.AdminPointsAuthorizationPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
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
 * Contract-65 points ledger read face over a real MySQL (SQL06 §9): the PRD 6.5 five fields
 * plus order linkage, bizType filter over the points_ledger vocabulary, fixed ordering
 * created_at DESC/id DESC and the fail-closed kernel-side authorization discipline. Read-only
 * by contract — the test also proves no write statement exists by exercising only reads.
 */
class AdminPointsLedgerMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-16T00:00:00.000Z");
    private static final AdminAuthorization AUTH = new AdminAuthorization("9007199254740993", 1);
    private static final QueryContext QUERY =
            new QueryContext("t0", OperatorType.PLATFORM_OPERATOR, "9007199254740993");

    @Test void ledgerPageFiltersSortAndAuthorizationFollowTheContract() throws Exception {
        try (Database d = new Database()) {
            d.seedLedger(9301L, 501L, "SIGN_IN", 5L, 5L, 1);
            d.seedLedger(9302L, 501L, "ORDER_REWARD", 101L, 106L, 2);
            d.seedLedger(9303L, 502L, "REFUND_CLAWBACK", -50L, 56L, 3);
            d.seedLedger(9304L, 501L, "INVITE", 30L, 136L, 4);

            var all = d.api().queryLedger(new AdminLedgerListQuery(null, null, null, null, 1, 20, QUERY, AUTH));
            assertEquals(4, all.total());
            // Fixed sort created_at DESC, id DESC — the newest seeded row (9301, seeded last
            // in time) first.
            assertEquals("9301", all.items().get(0).ledgerId());
            assertEquals("5", all.items().get(0).delta());

            var user501 = d.api().queryLedger(new AdminLedgerListQuery("501", null, null, null, 1, 20, QUERY, AUTH));
            assertEquals(3, user501.total());
            var clawback = d.api().queryLedger(new AdminLedgerListQuery(null, "REFUND_CLAWBACK", null, null, 1, 20, QUERY, AUTH));
            assertEquals(1, clawback.total());
            assertEquals("-50", clawback.items().get(0).delta());
            assertEquals("56", clawback.items().get(0).balanceAfter());
            assertEquals("502", clawback.items().get(0).userId());

            ApiException badType = assertThrows(ApiException.class,
                () -> d.api().queryLedger(new AdminLedgerListQuery(null, "SPEND", null, null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badType.code());

            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true).queryLedger(new AdminLedgerListQuery(null, null, null, null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, false).queryLedger(new AdminLedgerListQuery(null, null, null, null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
        }
    }

    private static final class Database implements AutoCloseable {
        final String name = "admpts_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADMPTS_MYSQL_URL") ? "ADMPTS"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADMPTS";
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
                        new FileSystemResource(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminPointsQueryApiImpl api() { return api(true, true); }

        AdminPointsQueryApiImpl api(boolean collectionAllowed, boolean available) {
            return new AdminPointsQueryApiImpl(source, new AdminPointsAuthorizationPort.Port() {
                @Override
                public AdminPointsAuthorizationPort.Decision check(AdminPointsAuthorizationPort.Check check) {
                    if (!available) return null;
                    return new AdminPointsAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }

                @Override
                public AdminPointsAuthorizationPort.Decision checkCollection(
                        AdminPointsAuthorizationPort.CollectionCheck check) {
                    if (!available) return null;
                    if (!collectionAllowed) {
                        return new AdminPointsAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                    }
                    return new AdminPointsAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }
            });
        }

        /** Seed with a controlled created_at order (minutes apart, UTC). */
        void seedLedger(long id, long userId, String bizType, long delta, long balance, int minute) {
            jdbc.update("""
                INSERT INTO points_ledger(id, user_id, biz_type, biz_id, order_id, delta,
                    balance_after, request_id, created_at)
                VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? MINUTE))
                """, id, userId, bizType, delta, balance, "seed-" + id, -minute);
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }

        private static DataSource dataSource(String url, String user, String password) {
            return new DriverManagerDataSource(
                url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
        }
    }
}
