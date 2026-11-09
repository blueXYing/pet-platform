package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.DisplayLabel;
import com.petplatform.order.biz.apiimpl.OrderAfterSaleApiImpl;
import com.petplatform.order.biz.apiimpl.OrderRefundApplicationApiImpl;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06) ORDER read port over isolated real MySQL: the
 * merchant aftersale read side gets the immutable order-time service name and pet
 * name/type/breed of one store's orders only — breed level (sex/weight/health never selected),
 * snapshot-less orders read null (never fabricated), foreign-store orders are absent — and the
 * read fails closed outside the caller's guarded READ_COMMITTED transaction, for a non-SYSTEM
 * context, another DataSource, or an unbounded/duplicate/malformed id set.
 */
class OrderAfterSaleDisplayLabelsMySqlTest {

    private static final long MERCHANT = 7_500_000_000_000_001L;
    private static final long STORE = 7_500_000_000_000_002L;
    private static final long OTHER_STORE = 7_500_000_000_000_003L;
    private static final long BUYER = 8_500_000_000_000_101L;
    private static final String STORE_ID = Long.toString(STORE);

    @Test
    void labelsCarryOnlyTheStoresOwnCreationSnapshotsWithNullDegradation() throws Exception {
        try (Database db = new Database()) {
            db.order(9_300L, STORE);
            db.serviceSnapshot(9_300L, "E2E到店精细洗护");
            db.petSnapshot(9_300L, "豆豆", "DOG", "柯基");
            db.order(9_301L, STORE);
            db.serviceSnapshot(9_301L, "猫咪洗澡"); // no pet snapshot row
            db.order(9_302L, STORE); // neither snapshot row
            db.order(9_303L, OTHER_STORE); // a sibling store's order must never be answered
            db.serviceSnapshot(9_303L, "他店专属服务");
            db.petSnapshot(9_303L, "他店宠物", "CAT", "英短");

            List<DisplayLabel> labels = db.guarded(STORE_ID, api -> api.displayLabels(
                    List.of("9300", "9301", "9302", "9303", "9399"), STORE_ID, system(), db.source));
            assertEquals(List.of("9300", "9301", "9302"), labels.stream().map(DisplayLabel::orderId).toList());

            DisplayLabel full = labels.get(0);
            assertEquals("E2E到店精细洗护", full.serviceName());
            assertEquals("豆豆", full.petName());
            assertEquals("DOG", full.petType());
            assertEquals("柯基", full.petBreed());

            DisplayLabel noPet = labels.get(1);
            assertEquals("猫咪洗澡", noPet.serviceName());
            assertNull(noPet.petName());
            assertNull(noPet.petType());
            assertNull(noPet.petBreed());

            DisplayLabel none = labels.get(2);
            assertNull(none.serviceName());
            assertNull(none.petName());

            assertFalse(labels.stream().anyMatch(label -> "他店专属服务".equals(label.serviceName())
                    || "他店宠物".equals(label.petName())));
        }
    }

    @Test
    void breedLevelOnlyNoPhoneSexWeightOrHealthEverCrosses() throws Exception {
        assertEquals(List.of("orderId", "serviceName", "petName", "petType", "petBreed"),
                Arrays.stream(DisplayLabel.class.getRecordComponents()).map(RecordComponent::getName).toList());
        try (Database db = new Database()) {
            db.order(9_310L, STORE);
            db.petSnapshot(9_310L, "阿黄", "DOG", "柯基");
            List<DisplayLabel> labels = db.guarded(STORE_ID, api -> api.displayLabels(
                    List.of("9310"), STORE_ID, system(), db.source));
            for (DisplayLabel label : labels) {
                for (RecordComponent component : DisplayLabel.class.getRecordComponents()) {
                    Object value = component.getAccessor().invoke(label);
                    // The seeded sex/weight/health columns and the buyer phone never reach a label.
                    assertFalse(value instanceof String text && (text.contains("怕生") || text.contains("12.50")
                            || text.contains("MALE") || text.contains("13800000100")),
                            component.getName() + " leaked a non-label fact: " + value);
                }
            }
        }
    }

    @Test
    void readFailsClosedOutsideTheCallersGuardedReadCommittedTransaction() throws Exception {
        try (Database db = new Database()) {
            db.order(9_320L, STORE);
            db.serviceSnapshot(9_320L, "E2E到店精细洗护");
            OrderAfterSaleApiImpl api = db.api();
            // No transaction at all.
            unavailable(() -> api.displayLabels(List.of("9320"), STORE_ID, system(), db.source));
            // A transaction whose store guard is not held.
            unavailable(() -> db.transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(
                    status -> api.displayLabels(List.of("9320"), STORE_ID, system(), db.source)));
            // A stronger isolation level than the caller contract.
            unavailable(() -> db.transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status -> {
                db.held.add(STORE_ID);
                return api.displayLabels(List.of("9320"), STORE_ID, system(), db.source);
            }));
            // Non-SYSTEM context, another DataSource, and unbounded/duplicate/malformed id sets.
            List<String> tooMany = new ArrayList<>();
            LongStream.rangeClosed(1, 51).forEach(n -> tooMany.add(Long.toString(9_000 + n)));
            for (Function<OrderAfterSaleApiImpl, List<DisplayLabel>> bad : List.<Function<OrderAfterSaleApiImpl, List<DisplayLabel>>>of(
                    a -> a.displayLabels(List.of("9320"), STORE_ID, new QueryContext("t", OperatorType.USER, "8500000000000101"), db.source),
                    a -> a.displayLabels(List.of("9320"), STORE_ID, system(), mock(DataSource.class)),
                    a -> a.displayLabels(List.of(), STORE_ID, system(), db.source),
                    a -> a.displayLabels(null, STORE_ID, system(), db.source),
                    a -> a.displayLabels(tooMany, STORE_ID, system(), db.source),
                    a -> a.displayLabels(List.of("9320", "9320"), STORE_ID, system(), db.source),
                    a -> a.displayLabels(List.of("09320"), STORE_ID, system(), db.source))) {
                unavailable(() -> db.guarded(STORE_ID, bad));
            }
            // The guarded happy path on the same rows still answers.
            assertEquals("E2E到店精细洗护", db.guarded(STORE_ID, a -> a.displayLabels(
                    List.of("9320"), STORE_ID, system(), db.source)).get(0).serviceName());
        }
    }

    private static QueryContext system() {
        return new QueryContext("aftersale-view-test", OperatorType.SYSTEM, null);
    }

    private static void unavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class, action).code());
    }

    /** Isolated real-MySQL fixture over the authoritative 06 schema (AUTH_MYSQL_* only); dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "ordafslabel_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final Set<String> held = ConcurrentHashMap.newKeySet();

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("AUTH_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            jdbc.update("INSERT INTO user_account(id,phone,nickname,status,version,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    BUYER, "13800000100", "E2E体验用户");
        }

        /** Only the label read is exercised; every other collaborator is an inert boundary mock. */
        OrderAfterSaleApiImpl api() {
            ScheduleCapacityGuardApi guard = new ScheduleCapacityGuardApi() {
                @Override
                public void acquire(List<String> storeIds, QueryContext context) { held.addAll(storeIds); }
                @Override
                public void requireHeld(String storeId, DataSource callerSource) {
                    if (callerSource != source || !held.contains(storeId)) {
                        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "store guard not held");
                    }
                }
            };
            return new OrderAfterSaleApiImpl(source, guard, () -> 1L, mock(OrderRefundApplicationApiImpl.class),
                    mock(PaymentSuccessFactsApi.class), () -> null, () -> null, () -> null, (merchant, store, context) -> null);
        }

        TransactionTemplate transaction(int isolation) {
            TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(source));
            template.setIsolationLevel(isolation);
            return template;
        }

        /** The caller discipline: READ_COMMITTED transaction on the same source with the store guard held. */
        <T> T guarded(String storeId, Function<OrderAfterSaleApiImpl, T> read) {
            OrderAfterSaleApiImpl api = api();
            try {
                return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
                    held.add(storeId);
                    return read.apply(api);
                });
            } finally {
                held.clear();
            }
        }

        void order(long id, long storeId) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?, 'COMPLETED','PAID','VERIFIED','IN_STORE',
                           ?,0.00,?,0.00, '2030-01-01 09:00:00','2030-01-01 10:00:00','2026-11-01 08:00:00',
                           UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, id, id, BUYER, MERCHANT, storeId, 8_801L, 8_701L, id + 5000,
                    new BigDecimal("60.00"), new BigDecimal("60.00"));
        }

        void serviceSnapshot(long orderId, String name) {
            jdbc.update("""
                    INSERT INTO order_service_snapshot
                    (id,order_id,service_id,service_name,service_price,service_duration_minutes,
                     merchant_name,store_name,store_address,created_at)
                    VALUES(?,?,?,?,60.00,60,'商家','门店','成都市锦江区测试路1号',UTC_TIMESTAMP(3))
                    """, orderId * 10, orderId, 8_801L, name);
        }

        void petSnapshot(long orderId, String name, String type, String breed) {
            jdbc.update("""
                    INSERT INTO order_pet_snapshot
                    (id,order_id,pet_id,pet_name,pet_type,breed_name,sex,weight_kg,health_note,created_at)
                    VALUES(?,?,?,?,?,?,'MALE',12.50,'怕生',UTC_TIMESTAMP(3))
                    """, orderId * 10 + 1, orderId, 8_701L, name, type, breed);
        }

        private static Path root() {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("Repository root with schema 06 was not found");
            return root;
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password) {
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
