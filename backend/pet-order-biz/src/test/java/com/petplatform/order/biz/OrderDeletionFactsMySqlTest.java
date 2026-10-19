package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderDeletionFactsApiImpl;
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
 * CCR-USR-DELETION-001): each of the five PRD-named display states blocks, the settled ones
 * do not, the store scope reads owner stores only, malformed subjects read as "nothing
 * blocks" and the predicate stays row-per-order (or-branches count one row once).
 */
class OrderDeletionFactsMySqlTest {

    private static final long USER = 9_500_000_000_004_001L;
    private static final long OTHER = 9_500_000_000_004_002L;
    private static final long STORE = 7001L;
    private static final long OTHER_STORE = 7002L;

    @Test
    void everyBlockingDisplayStateBlocksAndSettledOnesDoNot() throws Exception {
        try (Database db = new Database()) {
            OrderDeletionFactsApi api = db.api();
            // The five PRD-named blocking states.
            db.order(6_200_000_000_000_101L, USER, "PENDING_PAYMENT", null, null, null, STORE);
            assertTrue(api.userHasBlockingOrder(user(USER)));
            db.order(6_200_000_000_000_102L, USER, "PENDING_CONFIRM", null, null, null, STORE);
            db.order(6_200_000_000_000_103L, USER, "PENDING_SERVICE", null, null, null, STORE);
            db.order(6_200_000_000_000_104L, USER, "COMPLETED", "PENDING_MERCHANT", null, null, STORE);
            assertTrue(api.userHasBlockingOrder(user(USER)), "REFUND_PENDING_CONFIRM blocks");
            db.order(6_200_000_000_000_105L, USER, "PENDING_SERVICE", null, 9_910_001L, "0", STORE);
            assertTrue(api.userHasBlockingOrder(user(USER)), "REFUNDING blocks");

            // Settled states never block by themselves.
            assertFalse(api.userHasBlockingOrder(user(OTHER)), "empty history does not block");
            db.order(6_200_000_000_000_201L, OTHER, "COMPLETED", null, null, null, OTHER_STORE);
            db.order(6_200_000_000_000_202L, OTHER, "CANCELED", null, null, null, OTHER_STORE);
            db.order(6_200_000_000_000_203L, OTHER, "COMPLETED", null, 9_910_002L, "100.00", OTHER_STORE);
            db.order(6_200_000_000_000_204L, OTHER, "COMPLETED", null, 9_910_003L, "40.00", OTHER_STORE);
            assertFalse(api.userHasBlockingOrder(user(OTHER)), "COMPLETED/CANCELED/REFUNDED/PARTIAL do not block");
        }
    }

    @Test
    void storeScopeAndMalformedSubjectsStayTight() throws Exception {
        try (Database db = new Database()) {
            OrderDeletionFactsApi api = db.api();
            db.order(6_200_000_000_000_301L, USER, "PENDING_SERVICE", null, null, null, STORE);
            assertTrue(api.storesHaveBlockingOrder(new OrderDeletionFactsApi.StoreDeletionFactQuery(
                    List.of(Long.toString(STORE)))));
            assertFalse(api.storesHaveBlockingOrder(new OrderDeletionFactsApi.StoreDeletionFactQuery(
                    List.of(Long.toString(OTHER_STORE)))));
            // Empty or malformed store lists read as "nothing blocks" (never an error).
            assertFalse(api.storesHaveBlockingOrder(new OrderDeletionFactsApi.StoreDeletionFactQuery(
                    List.of())));
            assertFalse(api.storesHaveBlockingOrder(new OrderDeletionFactsApi.StoreDeletionFactQuery(
                    List.of("not-a-number"))));
            // Malformed user reads like an empty history.
            assertFalse(api.userHasBlockingOrder(new OrderDeletionFactsApi.UserDeletionFactQuery("")));
            assertFalse(api.userHasBlockingOrder(new OrderDeletionFactsApi.UserDeletionFactQuery("x")));
        }
    }

    private static OrderDeletionFactsApi.UserDeletionFactQuery user(long id) {
        return new OrderDeletionFactsApi.UserDeletionFactQuery(Long.toString(id));
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "orddelfact_" + UUID.randomUUID().toString().replace("-", "");
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
                    // 06 base + 48/49 projections: the blocking predicate reads the
                    // refund_application_status (49) and refund_order_id/refunded_amount (06)
                    // projection columns, so the fixture needs the same ALTER chain as the
                    // production truth table.
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "42-Late-Refund-Execution-Schema-v0.1.sql",
                            "45-Merchant-Order-Actions-Schema-v0.1.sql",
                            "48-Verification-Completion-Schema-v0.1.sql",
                            "49-Refund-Application-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
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

        OrderDeletionFactsApi api() {
            return new OrderDeletionFactsApiImpl(source);
        }

        void order(long id, long userId, String stage, String refundApplicationStatus,
                   Long refundOrderId, String refundedAmount, long storeId) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,fulfillment_type,original_amount,pay_amount,
                     refund_application_status,refund_order_id,refunded_amount,
                     appointment_start_at,appointment_end_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?+5000,?,'PAID','IN_STORE',100.00,100.00,?,?,?,?,?,
                           UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, id, id, userId, 7100L, storeId, 7200L, 7500L, id, stage,
                    refundApplicationStatus, refundOrderId,
                    // 42/45 projections carry NOT NULL refunded_amount; production defaults it to 0.
                    refundedAmount == null ? "0.00" : refundedAmount,
                    "2030-01-01 09:00:00", "2030-01-01 10:00:00");
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
