package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi.StoreOrderListQuery;
import com.petplatform.order.api.query.MerchantOrderQueryApi.Summary;
import com.petplatform.order.biz.apiimpl.MerchantOrderQueryApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
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
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * CCR-M-ORDER-CARD-001 acceptance over isolated real MySQL (approved 2026-11-06): the §4.1
 * merchant list items carry the six additive card keys — nickname/avatar through the user
 * domain's masked batch read, service/pet names through the order domain's own immutable
 * creation snapshots — while pagination, the displayStatus filter, the OWNER re-proof 403 and
 * the no-phone privacy ruling all stay exactly as before. Snapshot-less rows and absent
 * accounts degrade to null and are never fabricated.
 */
class MerchantOrderCardMySqlTest {

    private static final long MERCHANT = 7_400_000_000_000_001L;
    private static final long STORE = 7_400_000_000_000_002L;
    private static final long OTHER_STORE = 7_400_000_000_000_003L;
    private static final long ALICE = 8_400_000_000_000_101L;
    private static final long BOB = 8_400_000_000_000_102L;
    /** An order whose buyer account is gone from the user domain: facts stay, identity nulls. */
    private static final long GONE = 8_400_000_000_000_103L;
    private static final String ALICE_STR = "8400000000000101";
    private static final String BOB_STR = "8400000000000102";

    @Test
    void listCarriesCardFieldsWithNullDegradationAndImmutableSnapshotCaliber() throws Exception {
        try (Database db = new Database()) {
            MerchantOrderQueryApi api = db.orders();
            // ALICE has nickname + avatar; BOB has neither (both nullable per the CCR).
            db.order(9_200L, ALICE, "2026-11-05 09:00:00", "300.00", "PENDING_CONFIRM");
            db.order(9_201L, BOB, "2026-11-05 10:00:00", "120.00", "PENDING_CONFIRM");
            db.order(9_202L, ALICE, "2026-11-05 11:00:00", "90.00", "PENDING_SERVICE");
            db.order(9_203L, GONE, "2026-11-05 12:00:00", "60.00", "PENDING_CONFIRM");
            db.serviceSnapshot(9_200L, 8_801L, "猫咪洗澡");
            db.petSnapshot(9_200L, 8_701L, "小橘", "CAT", "英短");
            db.serviceSnapshot(9_201L, 8_802L, "狗狗美容");
            // 9_201 deliberately has no pet snapshot row and 9_202 no service snapshot row:
            // the CCR's degradation contract — null, never a fabricated placeholder.
            // 9_203 belongs to an absent account: the batch read simply misses the id.
            // A sibling store's order must never widen the page even with the same buyer.
            db.orderInStore(9_299L, OTHER_STORE, ALICE, "2026-11-05 13:00:00", "999.00");
            db.serviceSnapshot(9_299L, 8_801L, "他店专属服务");

            PageResult<Summary> page = api.listStoreOrders(query(null));
            assertEquals(4, page.total());
            List<Summary> items = page.items();
            assertEquals(4, items.size());
            // Fixed sort created_at DESC, id DESC — unchanged by the projection.
            assertEquals(List.of("9203", "9202", "9201", "9200"),
                    items.stream().map(Summary::orderId).toList());

            Summary alice = items.get(3);
            assertEquals("爱丽丝", alice.customerNickname());
            assertEquals("https://wx.qlogo.cn/alice.png", alice.customerAvatarUrl());
            // Snapshot caliber: the snapshot names win even though the live service_id/pet_id
            // point elsewhere — the card fact is the creation-time immutable copy.
            assertEquals("猫咪洗澡", alice.serviceName());
            assertEquals("小橘", alice.petName());
            assertEquals("CAT", alice.petType());
            assertEquals("英短", alice.petBreed());

            Summary bob = items.get(2);
            assertNull(bob.customerNickname());
            assertNull(bob.customerAvatarUrl());
            assertEquals("狗狗美容", bob.serviceName());
            assertNull(bob.petName());
            assertNull(bob.petType());
            assertNull(bob.petBreed());

            Summary noService = items.get(1);
            assertEquals("爱丽丝", noService.customerNickname());
            assertNull(noService.serviceName());

            Summary gone = items.get(0);
            assertNull(gone.customerNickname());
            assertNull(gone.customerAvatarUrl());
            // The order facts themselves stay factual for the absent account.
            assertEquals(0, gone.payAmount().compareTo(new BigDecimal("60.00")));
            assertEquals("PENDING_CONFIRM", gone.displayStatus());

            // The displayStatus filter keeps its own truth table with the projection attached.
            PageResult<Summary> pending = api.listStoreOrders(query("PENDING_CONFIRM"));
            assertEquals(3, pending.total());
            assertTrue(pending.items().stream().allMatch(item -> "PENDING_CONFIRM".equals(item.displayStatus())));
            assertEquals("小橘", pending.items().get(2).petName());

            // Privacy ruling: no Summary component is phone-shaped and no component value
            // ever carries the raw or masked credential of the seeded accounts.
            List<String> componentNames = Arrays.stream(Summary.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            assertTrue(componentNames.stream().noneMatch(name -> name.toLowerCase().contains("phone")),
                    componentNames.toString());
            for (Summary item : items) {
                for (RecordComponent component : Summary.class.getRecordComponents()) {
                    Object value;
                    try {
                        value = component.getAccessor().invoke(item);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                    assertFalse(value instanceof String text && (text.contains("13800000100")
                            || text.contains("138****0100") || text.contains("13900000200")),
                            component.getName() + " leaked a phone fact: " + value);
                }
            }

            // Cross-store isolation: the sibling store's rows never enter this page, and the
            // double-keyed snapshot reads cannot pick its names up either.
            assertFalse(items.stream().anyMatch(item -> "9299".equals(item.orderId())));
            assertFalse(items.stream().anyMatch(item -> "他店专属服务".equals(item.serviceName())));
        }
    }

    /** The snapshot write happens once at creation; later catalog edits never drift the card. */
    @Test
    void snapshotNamesAreTheCreationFactNotTheLiveCatalog() throws Exception {
        try (Database db = new Database()) {
            MerchantOrderQueryApi api = db.orders();
            db.order(9_210L, ALICE, "2026-11-05 09:00:00", "80.00", "PENDING_CONFIRM");
            db.serviceSnapshot(9_210L, 8_801L, "老版服务名");
            db.petSnapshot(9_210L, 8_701L, "旧宠物名", "DOG", "柯基");
            // The "catalog" moves on (a new snapshot row would only exist for a NEW order):
            // editing nothing here, the projection must still read the one immutable row.
            Summary item = api.listStoreOrders(query(null)).items().get(0);
            assertEquals("老版服务名", item.serviceName());
            assertEquals("旧宠物名", item.petName());
            assertEquals("柯基", item.petBreed());
        }
    }

    /** The OWNER re-proof still fails foreign coordinates closed before any card fact exists. */
    @Test
    void foreignCoordinatesKeepTheAntiEnumerationForbiddenAnswer() throws Exception {
        try (Database db = new Database()) {
            MerchantOrderQueryApi api = db.orders();
            db.order(9_220L, ALICE, "2026-11-05 09:00:00", "50.00", "PENDING_CONFIRM");
            db.serviceSnapshot(9_220L, 8_801L, "猫咪洗澡");
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                    () -> api.listStoreOrders(new StoreOrderListQuery(Long.toString(MERCHANT),
                            Long.toString(OTHER_STORE), null, 1, 20, context()))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.listStoreOrders(new StoreOrderListQuery(Long.toString(MERCHANT),
                            Long.toString(STORE), "PENDING", 1, 20, context()))).code());
            // A missing subject stays a 400 before any projection read.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> api.listStoreOrders(new StoreOrderListQuery(Long.toString(MERCHANT),
                            Long.toString(STORE), null, 1, 20,
                            new QueryContext("t", OperatorType.SYSTEM, null)))).code());
        }
    }

    private static StoreOrderListQuery query(String displayStatus) {
        return new StoreOrderListQuery(Long.toString(MERCHANT), Long.toString(STORE),
                displayStatus, 1, 20, context());
    }

    private static QueryContext context() {
        return new QueryContext("test", OperatorType.USER, "8400000000000099");
    }

    /** Isolated real-MySQL fixture; mirrors MerchantCustomerStatsMySqlTest's harness. */
    private static final class Database implements AutoCloseable {
        private final String name = "ordcard_" + UUID.randomUUID().toString().replace("-", "");
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
                        new FileSystemResource(root().resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                // Same companion set as the contract-69/70 suite: the 48/49号 projections the
                // DisplayOrderStatus truth table reads; 40/42/45 keep the loader's order stable.
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/40-Payment-Foundation-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/42-Late-Refund-Execution-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/45-Merchant-Order-Actions-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/48-Verification-Completion-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root().resolve("docs/03-database/49-Refund-Application-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
            } catch (Exception failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            // ALICE carries nickname + avatar; BOB carries neither; GONE has no row at all.
            jdbc.update("INSERT INTO user_account(id,phone,nickname,avatar_url,status,version,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    ALICE, "13800000100", "爱丽丝", "https://wx.qlogo.cn/alice.png");
            jdbc.update("INSERT INTO user_account(id,phone,nickname,avatar_url,status,version,created_at,updated_at) VALUES(?,?,NULL,NULL,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    BOB, "13900000200");
        }

        MerchantOrderQueryApi orders() {
            // Contract-69 batch semantics: masked basics from the user table, absent ids simply
            // missing from the answer (never fabricated), 1..200 ids per call.
            UserQueryApi users = new UserQueryApi() {
                @Override
                public UserBasicDTO getUser(com.petplatform.user.api.query.UserIdQuery query) { return null; }
                @Override
                public com.petplatform.user.api.dto.PetSnapshotDTO getPetSnapshot(
                        com.petplatform.user.api.query.PetSnapshotQuery query) { return null; }
                @Override
                public boolean existsEnabledUser(com.petplatform.user.api.query.UserIdQuery query) { return false; }
                @Override
                public List<UserBasicDTO> listUsers(UserBasicsQuery query) {
                    List<UserBasicDTO> found = new ArrayList<>();
                    for (String id : query.userIds()) {
                        var rows = jdbc.queryForList(
                                "SELECT id, phone, nickname, avatar_url FROM user_account WHERE id = ?",
                                Long.parseLong(id));
                        for (var row : rows) {
                            String phone = (String) row.get("phone");
                            found.add(new UserBasicDTO(id,
                                    phone == null ? null : phone.substring(0, 3) + "****" + phone.substring(7),
                                    (String) row.get("nickname"), null, (String) row.get("avatar_url")));
                        }
                    }
                    return found;
                }
            };
            return new MerchantOrderQueryApiImpl(source, guard(), authority(), users);
        }

        private static ScheduleCapacityGuardApi guard() {
            return new ScheduleCapacityGuardApi() {
                @Override
                public void acquire(List<String> storeIds, QueryContext context) {}
                @Override
                public void requireHeld(String storeId, DataSource callerSource) {}
            };
        }

        private static MerchantOrderAuthorityApi authority() {
            return new MerchantOrderAuthorityApi() {
                void check(String merchantId, String storeId) {
                    if (!Long.toString(STORE).equals(storeId)
                            || !Long.toString(MERCHANT).equals(merchantId)) {
                        throw new ApiException(CommonApiCodes.FORBIDDEN, "foreign coordinate");
                    }
                }
                @Override
                public void requireOwner(String merchantId, String storeId, QueryContext context) {
                    check(merchantId, storeId);
                }
                @Override
                public void requireOwnerRead(String merchantId, String storeId, QueryContext context) {
                    check(merchantId, storeId);
                }
            };
        }

        void order(long id, long userId, String createdAt, String pay, String stage) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,
                     created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?, 'PAID','UNVERIFIED','IN_STORE',
                           ?,0.00,?,0.00, '2030-01-01 09:00:00','2030-01-01 10:00:00','2026-11-01 08:00:00',
                           ?, UTC_TIMESTAMP(3))
                    """, id, id, userId, MERCHANT, STORE, 8_801L, 8_701L, id + 5000,
                    stage, new BigDecimal(pay), new BigDecimal(pay), createdAt);
        }

        void orderInStore(long id, long storeId, long userId, String createdAt, String pay) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     order_stage,payment_status,verification_status,fulfillment_type,
                     original_amount,discount_amount,pay_amount,refunded_amount,
                     appointment_start_at,appointment_end_at,paid_at,
                     created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?, 'PAID','UNVERIFIED','IN_STORE',
                           ?,0.00,?,0.00, '2030-01-01 09:00:00','2030-01-01 10:00:00','2026-11-01 08:00:00',
                           ?, UTC_TIMESTAMP(3))
                    """, id, id, userId, MERCHANT, storeId, 8_801L, 8_701L, id + 5000,
                    "PENDING_CONFIRM", new BigDecimal(pay), new BigDecimal(pay), createdAt);
        }

        void serviceSnapshot(long orderId, long serviceId, String name) {
            jdbc.update("""
                    INSERT INTO order_service_snapshot
                    (id,order_id,service_id,service_name,service_price,service_duration_minutes,
                     merchant_name,store_name,store_address,created_at)
                    VALUES(?,?,?,?,100.00,60,'商家','门店','地址',UTC_TIMESTAMP(3))
                    """, orderId * 10, orderId, serviceId, name);
        }

        void petSnapshot(long orderId, long petId, String name, String type, String breed) {
            jdbc.update("INSERT INTO order_pet_snapshot(id,order_id,pet_id,pet_name,pet_type,breed_name,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                    orderId * 10, orderId, petId, name, type, breed);
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
