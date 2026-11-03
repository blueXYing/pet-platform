package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSold;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.ServiceSoldQuery;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySold;
import com.petplatform.order.api.query.ServiceCatalogSalesFactsApi.StoreMonthlySoldQuery;
import com.petplatform.order.biz.apiimpl.ServiceCatalogSalesFactsApiImpl;
import com.petplatform.order.biz.infrastructure.persistence.entity.ServiceCatalogSalesCountEntity;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * B-STAT-02 acceptance over isolated real MySQL: the order-owned public sales facts of the
 * service catalog page (74号 §6.2 / CCR-SVC-CARD-001 §3.1). Covers the effective-order state
 * set (paid, not canceled, refunded_amount = 0; completion/verification not required),
 * partial/full refund exclusion, the Asia/Shanghai natural-month boundary over UTC storage,
 * all-time service sold, batch/dedupe/empty results, the no-PII surface and the
 * store/service-prefix indexes.
 */
class ServiceCatalogSalesFactsMySqlTest {

    private static final long MERCHANT = 7_400_000_000_000_001L;
    private static final long STORE_A = 7_400_000_000_000_002L;
    private static final long STORE_B = 7_400_000_000_000_003L;
    private static final long STORE_C = 7_400_000_000_000_004L;
    private static final long USER = 8_400_000_000_000_101L;
    private static final long SERVICE_A = 501L;
    private static final long SERVICE_B = 502L;
    private static final long SERVICE_C = 503L;
    private static final long PET = 9_400_000_000_000_001L;
    private static final long RESERVATION_BASE = 9_500_000_000_000_000L;

    /** 2026-10-31 20:00 Asia/Shanghai: the current natural month is October 2026. */
    private static final Clock OCTOBER =
            Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void storeMonthlySoldCountsOnlyEffectiveOrdersOfTheMonth() throws Exception {
        try (Database db = new Database()) {
            // Effective: paid, not canceled, refunded_amount = 0. Completion/verification is
            // explicitly not required (PENDING_SERVICE still counts).
            db.order(9_001L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-05 00:00:00", "0.00");
            db.order(9_002L, STORE_A, SERVICE_A, "PENDING_SERVICE", "PAID", "2026-10-06 00:00:00", "0.00");
            // Partial refund and full refund both do not count.
            db.order(9_003L, STORE_A, SERVICE_A, "PENDING_SERVICE", "PAID", "2026-10-07 00:00:00", "50.00");
            db.order(9_004L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-08 00:00:00", "100.00");
            // Late payment keeps the order canceled even while the refund is still pending:
            // canceled never counts, even with refunded_amount = 0.
            db.order(9_005L, STORE_A, SERVICE_A, "CANCELED", "PAID", "2026-10-09 00:00:00", "0.00");
            db.order(9_006L, STORE_A, SERVICE_A, "CANCELED", "PAID", "2026-10-10 00:00:00", "100.00");
            // Never paid: paid_at IS NULL.
            db.order(9_007L, STORE_A, SERVICE_A, "PENDING_PAYMENT", "INIT", null, "0.00");
            db.order(9_008L, STORE_B, SERVICE_B, "PENDING_CONFIRM", "PAID", "2026-10-05 00:00:00", "0.00");

            ServiceCatalogSalesFactsApi api = db.api(OCTOBER);
            // Batch with duplicate ids; STORE_C has no orders and must be absent (zero).
            Map<String, Long> counts = monthly(api, STORE_A, STORE_B, STORE_A, STORE_C);
            assertEquals(Map.of(Long.toString(STORE_A), 2L, Long.toString(STORE_B), 1L), counts);
            assertFalse(counts.containsKey(Long.toString(STORE_C)));
            // A single-store batch answers the same fact.
            assertEquals(Map.of(Long.toString(STORE_A), 2L), monthly(api, STORE_A));
        }
    }

    @Test
    void monthlyWindowFollowsTheAsiaShanghaiNaturalMonth() throws Exception {
        try (Database db = new Database()) {
            // Month of October 2026 in Asia/Shanghai = [2026-09-30T16:00Z, 2026-10-31T16:00Z).
            db.order(9_011L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-09-30 15:59:59.999", "0.00");
            db.order(9_012L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-09-30 16:00:00.000", "0.00");
            db.order(9_013L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-01 00:00:00.000", "0.00");
            db.order(9_014L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-31 15:59:59.999", "0.00");
            db.order(9_015L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-31 16:00:00.000", "0.00");

            assertEquals(Map.of(Long.toString(STORE_A), 3L),
                    monthly(db.api(OCTOBER), STORE_A));
            // 2026-09-30 23:59:59 Asia/Shanghai is still September: only the first row counts.
            Clock september = Clock.fixed(Instant.parse("2026-09-30T15:59:59Z"), ZoneOffset.UTC);
            assertEquals(Map.of(Long.toString(STORE_A), 1L),
                    monthly(db.api(september), STORE_A));
            // 2026-11-01 08:00 Asia/Shanghai is November: only the 16:00Z row counts.
            Clock november = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
            assertEquals(Map.of(Long.toString(STORE_A), 1L),
                    monthly(db.api(november), STORE_A));
        }
    }

    @Test
    void serviceSoldCountsAllTimeEffectiveOrdersAcrossStores() throws Exception {
        try (Database db = new Database()) {
            // All-time: a 2025 order still counts, and service sold is global across stores.
            db.order(9_021L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2025-01-05 00:00:00", "0.00");
            db.order(9_022L, STORE_A, SERVICE_A, "PENDING_SERVICE", "PAID", "2026-10-05 00:00:00", "0.00");
            db.order(9_023L, STORE_B, SERVICE_A, "COMPLETED", "PAID", "2026-02-05 00:00:00", "0.00");
            // Excluded families: partial refund, full refund, canceled, never paid.
            db.order(9_024L, STORE_A, SERVICE_A, "PENDING_SERVICE", "PAID", "2026-03-05 00:00:00", "50.00");
            db.order(9_025L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-04-05 00:00:00", "100.00");
            db.order(9_026L, STORE_A, SERVICE_A, "CANCELED", "PAID", "2026-05-05 00:00:00", "0.00");
            db.order(9_027L, STORE_A, SERVICE_A, "PENDING_PAYMENT", "INIT", null, "0.00");
            // SERVICE_B has one effective order; SERVICE_C only an excluded one.
            db.order(9_028L, STORE_A, SERVICE_B, "COMPLETED", "PAID", "2026-06-05 00:00:00", "0.00");
            db.order(9_029L, STORE_A, SERVICE_C, "COMPLETED", "PAID", "2026-07-05 00:00:00", "100.00");

            ServiceCatalogSalesFactsApi api = db.api(OCTOBER);
            List<ServiceSold> rows = api.serviceSold(new ServiceSoldQuery(List.of(
                    Long.toString(SERVICE_A), Long.toString(SERVICE_B),
                    Long.toString(SERVICE_C), Long.toString(SERVICE_A), "999")));
            assertEquals(Map.of(Long.toString(SERVICE_A), 3L, Long.toString(SERVICE_B), 1L),
                    soldCounts(rows));
            assertFalse(soldCounts(rows).containsKey(Long.toString(SERVICE_C)));
            assertFalse(soldCounts(rows).containsKey("999"));
        }
    }

    @Test
    void emptyBatchAndUnusedCoordinatesReadAsEmpty() throws Exception {
        try (Database db = new Database()) {
            ServiceCatalogSalesFactsApi api = db.api(OCTOBER);
            assertTrue(api.storeMonthlySold(new StoreMonthlySoldQuery(List.of())).isEmpty());
            assertTrue(api.serviceSold(new ServiceSoldQuery(List.of())).isEmpty());
            assertTrue(api.storeMonthlySold(new StoreMonthlySoldQuery(null)).isEmpty());
            assertTrue(api.serviceSold(new ServiceSoldQuery(null)).isEmpty());
            // Valid ids that simply have no qualifying order are absent, not fabricated zeros.
            assertTrue(monthly(api, STORE_C).isEmpty());
            assertTrue(api.serviceSold(new ServiceSoldQuery(List.of("999"))).isEmpty());
        }
    }

    @Test
    void malformedOrOversizedBatchesAreRejected() throws Exception {
        try (Database db = new Database()) {
            ServiceCatalogSalesFactsApi api = db.api(OCTOBER);
            for (String malformed : List.of("", "0", "-1", "abc", "1.0", "9223372036854775808")) {
                assertThrows(IllegalArgumentException.class,
                        () -> api.storeMonthlySold(new StoreMonthlySoldQuery(List.of(malformed))));
                assertThrows(IllegalArgumentException.class,
                        () -> api.serviceSold(new ServiceSoldQuery(List.of(malformed))));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> api.serviceSold(new ServiceSoldQuery(Arrays.asList((String) null))));
            List<String> oversized = IntStream.rangeClosed(1, 201)
                    .mapToObj(Integer::toString).toList();
            assertThrows(IllegalArgumentException.class,
                    () -> api.storeMonthlySold(new StoreMonthlySoldQuery(oversized)));
            assertThrows(IllegalArgumentException.class,
                    () -> api.serviceSold(new ServiceSoldQuery(oversized)));
        }
    }

    @Test
    void surfaceCarriesOnlyIdsAndCounts() {
        assertEquals(List.of("storeId", "orderCount"), componentNames(StoreMonthlySold.class));
        assertEquals(List.of("serviceId", "orderCount"), componentNames(ServiceSold.class));
        assertEquals(List.of("storeIds"), componentNames(StoreMonthlySoldQuery.class));
        assertEquals(List.of("serviceIds"), componentNames(ServiceSoldQuery.class));
        // The mapper row can physically carry nothing else.
        Set<String> fields = Arrays.stream(ServiceCatalogSalesCountEntity.class.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());
        assertEquals(Set.of("id", "orderCount"), fields);
        // The SQL project list holds no order/user identity column.
        String xml = readMapperXml();
        for (String forbidden : List.of("user_id", "order_id", "order_no", "phone", "address")) {
            assertFalse(xml.contains(forbidden), "sales facts SQL must not project " + forbidden);
        }
    }

    @Test
    void statementsKeepTheStoreAndServicePrefixIndexes() throws Exception {
        try (Database db = new Database()) {
            db.order(9_031L, STORE_A, SERVICE_A, "COMPLETED", "PAID", "2026-10-05 00:00:00", "0.00");
            db.order(9_032L, STORE_B, SERVICE_B, "COMPLETED", "PAID", "2026-10-06 00:00:00", "0.00");

            List<Map<String, Object>> storePlan = db.jdbc.queryForList("""
                    EXPLAIN SELECT store_id AS id, COUNT(*) AS order_count
                    FROM pet_order FORCE INDEX (idx_order_store_stage_created)
                    WHERE store_id IN (?,?)
                      AND paid_at >= ? AND paid_at < ?
                      AND paid_at IS NOT NULL AND order_stage <> 'CANCELED' AND refunded_amount = 0
                    GROUP BY store_id ORDER BY store_id ASC
                    """, STORE_A, STORE_B, "2026-09-30 16:00:00", "2026-10-31 16:00:00");
            assertEquals("idx_order_store_stage_created", storePlan.get(0).get("key"));
            List<Map<String, Object>> storePossible = db.jdbc.queryForList("""
                    EXPLAIN SELECT store_id AS id, COUNT(*) AS order_count
                    FROM pet_order
                    WHERE store_id IN (?,?)
                      AND paid_at >= ? AND paid_at < ?
                      AND paid_at IS NOT NULL AND order_stage <> 'CANCELED' AND refunded_amount = 0
                    GROUP BY store_id ORDER BY store_id ASC
                    """, STORE_A, STORE_B, "2026-09-30 16:00:00", "2026-10-31 16:00:00");
            assertTrue(String.valueOf(storePossible.get(0).get("possible_keys"))
                    .contains("idx_order_store_stage_created"));

            List<Map<String, Object>> servicePlan = db.jdbc.queryForList("""
                    EXPLAIN SELECT service_id AS id, COUNT(*) AS order_count
                    FROM pet_order FORCE INDEX (idx_order_service_time)
                    WHERE service_id IN (?,?)
                      AND paid_at IS NOT NULL AND order_stage <> 'CANCELED' AND refunded_amount = 0
                    GROUP BY service_id ORDER BY service_id ASC
                    """, SERVICE_A, SERVICE_B);
            assertEquals("idx_order_service_time", servicePlan.get(0).get("key"));
            List<Map<String, Object>> servicePossible = db.jdbc.queryForList("""
                    EXPLAIN SELECT service_id AS id, COUNT(*) AS order_count
                    FROM pet_order
                    WHERE service_id IN (?,?)
                      AND paid_at IS NOT NULL AND order_stage <> 'CANCELED' AND refunded_amount = 0
                    GROUP BY service_id ORDER BY service_id ASC
                    """, SERVICE_A, SERVICE_B);
            assertTrue(String.valueOf(servicePossible.get(0).get("possible_keys"))
                    .contains("idx_order_service_time"));
        }
    }

    private static Map<String, Long> monthly(ServiceCatalogSalesFactsApi api, long... storeIds) {
        List<StoreMonthlySold> rows = api.storeMonthlySold(new StoreMonthlySoldQuery(
                Arrays.stream(storeIds).mapToObj(Long::toString).toList()));
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(row.storeId(), row.orderCount()));
        return counts;
    }

    private static Map<String, Long> soldCounts(List<ServiceSold> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(row.serviceId(), row.orderCount()));
        return counts;
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String readMapperXml() {
        try {
            return Files.readString(root().resolve(
                    "backend/pet-order-biz/src/main/resources/mapper/ServiceCatalogSalesFactsMapper.xml"));
        } catch (Exception failure) {
            throw new IllegalStateException("mapper XML is not readable", failure);
        }
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

    /** Isolated real-MySQL fixture over the authoritative 06 schema; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "svccatsales_" + UUID.randomUUID().toString().replace("-", "");
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
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve(
                                "docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        ServiceCatalogSalesFactsApi api(Clock clock) {
            return new ServiceCatalogSalesFactsApiImpl(source, clock);
        }

        /**
         * One pet_order row. paidAt is the UTC DATETIME(3) string (null = never paid); stage,
         * payment status and refunded amount are the facts under test.
         */
        void order(long id, long storeId, long serviceId, String stage, String paymentStatus,
                String paidAt, String refundedAmount) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'UNVERIFIED','IN_STORE',
                           ?,0.00,?,?, '2030-01-01 09:00:00','2030-01-01 10:00:00',
                           ?, '2026-10-01 08:00:00', UTC_TIMESTAMP(3))
                    """, id, id, USER, MERCHANT, storeId, serviceId, PET,
                    RESERVATION_BASE + id, stage, paymentStatus, new BigDecimal("100.00"),
                    new BigDecimal("100.00"), new BigDecimal(refundedAmount), paidAt);
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
