package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.petplatform.order.api.dto.OrderFirstPaidFact;
import com.petplatform.order.api.query.OrderFirstPaidFactsApi;
import com.petplatform.order.biz.apiimpl.OrderFirstPaidFactsApiImpl;
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
 * 07号 §7.1 amendment acceptance over isolated real MySQL (contract 60 §7 /
 * CCR-POINTS-ORDER-001 O(1) closure): the order-owned first-paid-order fact reads null without
 * any paid order (empty history, unpaid-only history, malformed subject), picks the earliest
 * paid instant with the smallest canonical id as the tie-break, keeps other users' orders out,
 * includes late-payment orders kept closed yet marked PAID, and agrees with the firstPaidAt
 * kernel fact on the same rows.
 */
class OrderFirstPaidFactsMySqlTest {

    private static final long USER = 9_400_000_000_003_001L;
    private static final long OTHER = 9_400_000_000_003_002L;

    @Test
    void emptyAndUnpaidHistoriesReadNullIncludingMalformedSubject() throws Exception {
        try (Database db = new Database()) {
            OrderFirstPaidFactsApi api = db.factsApi();
            assertNull(api.firstPaidOrder(user(USER)));
            db.order(6_100_000_000_000_101L, USER, "PENDING_PAYMENT", "INIT", null);
            assertNull(api.firstPaidOrder(user(USER)));
            assertNull(api.firstPaidOrder(user(OTHER)));
            assertNull(api.firstPaidOrder(new OrderFirstPaidFactsApi.UserOrderFactQuery("not-a-number")));
            assertNull(api.firstPaidOrder(new OrderFirstPaidFactsApi.UserOrderFactQuery("")));
        }
    }

    @Test
    void earliestPaidInstantWinsAndSmallestIdBreaksTies() throws Exception {
        try (Database db = new Database()) {
            OrderFirstPaidFactsApi api = db.factsApi();
            db.order(6_100_000_000_000_201L, USER, "PENDING_SERVICE", "PAID",
                    "2026-10-12 08:00:00.000");
            db.order(6_100_000_000_000_202L, USER, "PENDING_SERVICE", "PAID",
                    "2026-10-11 09:30:00.000");
            OrderFirstPaidFact fact = api.firstPaidOrder(user(USER));
            assertEquals("6100000000000202", fact.orderId());
            assertEquals("2026-10-11T09:30", fact.paidAt().toString().substring(0, 16));

            // Same earliest instant: the smaller canonical id stays the answer (stable tie-break).
            db.order(6_100_000_000_000_203L, USER, "PENDING_SERVICE", "PAID",
                    "2026-10-11 09:30:00.000");
            assertEquals("6100000000000202", api.firstPaidOrder(user(USER)).orderId());
            db.order(6_100_000_000_000_199L, USER, "PENDING_SERVICE", "PAID",
                    "2026-10-11 09:30:00.000");
            assertEquals("6100000000000199", api.firstPaidOrder(user(USER)).orderId());

            // A strictly earlier instant always wins, regardless of insertion order.
            db.order(6_100_000_000_000_205L, USER, "COMPLETED", "PAID", "2026-10-10 10:00:00.000");
            assertEquals("6100000000000205", api.firstPaidOrder(user(USER)).orderId());
        }
    }

    @Test
    void otherUsersNeverLeakAndLatePaidClosuresCount() throws Exception {
        try (Database db = new Database()) {
            OrderFirstPaidFactsApi api = db.factsApi();
            // Late payment: the order stays closed (CANCELED) but is marked PAID — inside the fact.
            db.order(6_100_000_000_000_301L, USER, "CANCELED", "PAID", "2026-10-12 10:00:00.000");
            // Another user's strictly earlier paid order must not leak into the subject's fact.
            db.order(6_100_000_000_000_302L, OTHER, "PENDING_SERVICE", "PAID",
                    "2026-10-01 10:00:00.000");
            OrderFirstPaidFact fact = api.firstPaidOrder(user(USER));
            assertEquals("6100000000000301", fact.orderId());
            assertEquals(db.jdbc.queryForObject(
                    "SELECT MIN(paid_at) FROM pet_order WHERE user_id=? AND payment_status='PAID'",
                    java.time.LocalDateTime.class, USER).toInstant(java.time.ZoneOffset.UTC),
                    fact.paidAt().toInstant());
            assertEquals("6100000000000302", api.firstPaidOrder(user(OTHER)).orderId());
        }
    }

    private static OrderFirstPaidFactsApi.UserOrderFactQuery user(long id) {
        return new OrderFirstPaidFactsApi.UserOrderFactQuery(Long.toString(id));
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "ordfirstpaid_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ORDER_FIRST_PAID_MYSQL_URL")
                    ? "ORDER_FIRST_PAID"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ORDER_FIRST_PAID";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        prefix + "_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                try (Connection connection = source.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql")) {
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

        OrderFirstPaidFactsApi factsApi() {
            return new OrderFirstPaidFactsApiImpl(source);
        }

        void order(long id, long userId, String stage, String paymentStatus, String paidAt) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,fulfillment_type,original_amount,pay_amount,
                     appointment_start_at,appointment_end_at,paid_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?, 'IN_STORE', 100.00, 100.00,
                           '2030-01-01 09:00:00', '2030-01-01 10:00:00', ?, UTC_TIMESTAMP(3),
                           UTC_TIMESTAMP(3))
                    """, id, id, userId, 7100L, 7001L, 7200L, 7500L, id + 5000, stage,
                    paymentStatus, paidAt);
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
