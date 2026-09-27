package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.order.biz.application.OrderCreationInputProtection;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HeldClaim;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.service.api.dto.BookingServiceFacts;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** SQL06/37/38 order-side integration. SCH's solver and real Owner adapters have separate tests. */
class OrderCreationMySqlTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2030-01-01T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2029-12-31T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsSnapshotsAndReplaysFirstReceiptWithoutConsumingCurrentEligibility() throws Exception {
        try (Database db = new Database()) {
            var command = db.inStore("u-" + UUID.randomUUID());
            var first = db.api().create(command);
            assertTrue(first.created());
            assertFalse(first.replayed());
            assertEquals("PENDING_PAYMENT", first.displayStatus());
            assertEquals("128.00", first.payAmount());
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(1, db.count("order_service_snapshot"));
            String initialServiceFacts = db.jdbc.queryForObject(
                    "SELECT snapshot_json FROM order_service_snapshot", String.class);
            assertTrue(initialServiceFacts.contains("verificationRequired"));
            assertEquals(1, db.count("order_pet_snapshot"));
            assertEquals(1, db.count("order_booking_input_snapshot"));
            assertEquals(1, db.count("order_status_log"));
            assertEquals(1, db.count("order_creation_audit"));
            assertEquals(first.paymentExpireAt().toInstant(), db.jdbc.queryForObject(
                    "SELECT payment_expire_at FROM pet_order", Timestamp.class).toInstant());
            db.price = "999.00";
            db.verificationRequired = false;
            db.serviceVersion = "1";
            db.petCurrent = false;
            var replay = db.api().create(command);
            assertTrue(replay.replayed());
            assertFalse(replay.created());
            assertEquals(first.orderId(), replay.orderId());
            assertEquals("128.00", replay.payAmount());
            assertEquals(initialServiceFacts, db.jdbc.queryForObject(
                    "SELECT snapshot_json FROM order_service_snapshot", String.class));
            assertEquals(1, db.count("schedule_reservation"));
            var changed = new CreateOrderCommand(command.context(), command.storeId(), command.serviceId(),
                    command.petId(), command.fulfillmentType(), command.appointmentStart(),
                    command.appointmentEnd().plusMinutes(1), null, null, null, null, null,
                    null, null, null);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    assertThrows(ApiException.class, () -> db.api().create(changed)).code());
            db.userEnabled = false;
            assertThrows(ApiException.class, () -> db.api().create(command));
        }
    }

    @Test
    void failureAfterHoldRollsBackAllBusinessRowsButRetainsBindingForOriginalRetry() throws Exception {
        try (Database db = new Database()) {
            var command = db.inStore("rollback-" + UUID.randomUUID());
            db.admin.execute("CREATE TRIGGER `" + db.name + "`.fail_snapshot BEFORE INSERT ON `"
                    + db.name + "`.order_service_snapshot FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced'");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> db.api().create(command)).code());
            assertEquals(0, db.count("pet_order"));
            assertEquals(0, db.count("schedule_reservation"));
            assertEquals(0, db.count("schedule_reservation_claim"));
            assertEquals(0, db.count("order_creation_audit"));
            assertEquals("RESERVED", db.jdbc.queryForObject(
                    "SELECT status FROM order_creation_request", String.class));
            db.admin.execute("DROP TRIGGER `" + db.name + "`.fail_snapshot");
            assertTrue(db.api().create(command).created());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void pickupPersistsEncryptedAddressAndTwoClaimsWhileMissingCouponFailsClosed() throws Exception {
        try (Database db = new Database()) {
            var pickup = db.pickup("pickup-" + UUID.randomUUID(), null);
            var created = db.api().create(pickup);
            assertTrue(created.created());
            assertEquals(2, db.count("schedule_reservation_claim"));
            assertEquals(START.toInstant(), db.jdbc.queryForObject(
                    "SELECT appointment_start_at FROM pet_order", Timestamp.class).toInstant());
            assertEquals(START.plusHours(3).toInstant(), db.jdbc.queryForObject(
                    "SELECT appointment_end_at FROM pet_order", Timestamp.class).toInstant());
            byte[] stored = db.jdbc.queryForObject(
                    "SELECT service_address_ciphertext FROM order_booking_input_snapshot", byte[].class);
            assertFalse(new String(stored, StandardCharsets.UTF_8).contains("虹桥路"));
            assertNotEquals(0, stored.length);
            assertEquals("OTHER", db.jdbc.queryForObject("SELECT pet_type FROM order_pet_snapshot", String.class));
            var coupon = db.pickup("coupon-" + UUID.randomUUID(), "456");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> db.api().create(coupon)).code());
            assertEquals(1, db.count("pet_order"));
            assertEquals(2, db.count("order_creation_request"));
            var changed = db.pickup(coupon.context().requestId(), null);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    assertThrows(ApiException.class, () -> db.api().create(changed)).code());
        }
    }

    @Test
    void successfulRemarkReplayDoesNotRerunConsumedModeration() throws Exception {
        try (Database db = new Database()) {
            var base = db.inStore("remark-" + UUID.randomUUID());
            var withRemark = new CreateOrderCommand(base.context(), base.storeId(), base.serviceId(),
                    base.petId(), base.fulfillmentType(), base.appointmentStart(),
                    base.appointmentEnd(), null, null, base.selectedGeneralWindowId(), null, null,
                    null, "请提前沟通", null);
            var first = db.api().create(withRemark);
            assertTrue(first.created());
            db.policyAvailable = false;
            var replay = db.api().create(withRemark);
            assertTrue(replay.replayed());
            assertEquals(first.orderId(), replay.orderId());
            assertEquals(1, db.count("order_creation_audit"));
            byte[] stored = db.jdbc.queryForObject(
                    "SELECT customer_remark_ciphertext FROM order_booking_input_snapshot", byte[].class);
            assertFalse(new String(stored, StandardCharsets.UTF_8).contains("请提前沟通"));
        }
    }

    @Test
    void concurrentSameKeyProducesOneReceiptAndOneReservation() throws Exception {
        try (Database db = new Database(); var workers = Executors.newFixedThreadPool(2)) {
            var command = db.inStore("race-" + UUID.randomUUID());
            CountDownLatch start = new CountDownLatch(1);
            var one = workers.submit(() -> { start.await(); return db.api().create(command); });
            var two = workers.submit(() -> { start.await(); return db.api().create(command); });
            start.countDown();
            var a = one.get(10, TimeUnit.SECONDS);
            var b = two.get(10, TimeUnit.SECONDS);
            assertEquals(a.orderId(), b.orderId());
            assertNotEquals(a.created(), b.created());
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    private static final class Database implements AutoCloseable {
        final String name = "ordcreate_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(10000);
        volatile String price = "128.00";
        volatile boolean userEnabled = true;
        volatile boolean petCurrent = true;
        volatile boolean policyAvailable = true;
        volatile boolean verificationRequired = true;
        volatile String serviceVersion = "0";

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ORDER_CREATE_MYSQL_URL") ? "ORDER_CREATE"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ORDER_CREATE";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33459/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ORDER_CREATE_MYSQL_URL must target local server root");
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
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                            "38-Booking-Create-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        OrderCreationApiImpl api() {
            ScheduleCapacityGuardApi guard = new TestGuard(jdbc);
            ReservationHoldApi hold = command -> {
                long reservationId = ids.incrementAndGet();
                OffsetDateTime begin = command.appointmentStart() == null ? command.pickupStart()
                        : command.appointmentStart();
                OffsetDateTime end = command.appointmentEnd() == null ? command.returnStart().plusHours(1)
                        : command.appointmentEnd();
                OffsetDateTime expires = OffsetDateTime.now(CLOCK).plusMinutes(10);
                jdbc.update("""
                        INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,service_id,
                          fulfillment_type,start_at,end_at,pickup_start_at,return_start_at,status,
                          lock_token,lock_expire_at,capacity_snapshot,qualified_staff_count_snapshot,
                          created_at,updated_at,user_id)
                        VALUES(?,?,?,?,?,?,?,?,?,?,'TEMP_LOCKED',?,?,1,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)
                        """, reservationId, Long.parseLong(command.orderId()), 200L, 300L, 400L,
                        command.fulfillmentType(), Timestamp.from(begin.toInstant()),
                        Timestamp.from(end.toInstant()), stamp(command.pickupStart()),
                        stamp(command.returnStart()), UUID.randomUUID().toString(),
                        Timestamp.from(expires.toInstant()), Long.parseLong(command.userId()));
                if (command.appointmentStart() != null) {
                    claim(reservationId, "GENERAL", "901", begin, end);
                    return new HoldResult(Long.toString(reservationId), command.orderId(), begin, end,
                            expires, List.of(new HeldClaim(Long.toString(reservationId + 1000), "901",
                                    "GENERAL", begin, end)));
                }
                OffsetDateTime pickupEnd = begin.plusHours(1);
                OffsetDateTime returnStart = command.returnStart();
                claim(reservationId, "PICKUP", "902", begin, pickupEnd);
                claim(reservationId, "RETURN", "903", returnStart, end);
                return new HoldResult(Long.toString(reservationId), command.orderId(), begin, end,
                        expires, List.of(new HeldClaim(Long.toString(reservationId + 1000), "902",
                                "PICKUP", begin, pickupEnd),
                                new HeldClaim(Long.toString(reservationId + 1001), "903",
                                        "RETURN", returnStart, end)));
            };
            OrderCreationInputProtection protection = (purpose, text) -> {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest((purpose + text).getBytes(StandardCharsets.UTF_8));
                    return new OrderCreationInputProtection.ProtectedInput(
                            ("encrypted:" + purpose).getBytes(StandardCharsets.UTF_8), digest);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            return new OrderCreationApiImpl(source, ids::incrementAndGet,
                    new com.petplatform.user.api.query.BookingUserFactsApi() {
                        @Override public void checkActor(String userId, QueryContext context) {
                            if (!userEnabled) throw new ApiException(CommonApiCodes.FORBIDDEN, "frozen");
                        }
                        @Override public void requireCurrentActor(String userId, String storeId, QueryContext context) {
                            checkActor(userId, context);
                        }
                        @Override public PetSnapshotDTO readCurrentPet(String userId, String petId,
                                String storeId, QueryContext context) {
                            if (!petCurrent) throw new ApiException("PET_NOT_FOUND", "pet unavailable");
                            return new PetSnapshotDTO(petId, userId, "Momo", "OTHER", null,
                                    "UNKNOWN", null, null);
                        }
                    }, (storeId, context) -> new BookingMerchantFacts("200", storeId,
                            "Merchant", "Store", "Street"),
                    (storeId, serviceId, context) -> new BookingServiceFacts(serviceId, "200", storeId,
                            "Wash", null, null, new java.math.BigDecimal(price), 60,
                            serviceId.equals("401") ? "PICKUP_DELIVERY" : "IN_STORE",
                            "Description", Set.of("ALL"), verificationRequired, serviceVersion),
                    guard, hold, protection, remark -> {
                        if (!policyAvailable) throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                                "moderation unavailable");
                    }, CLOCK);
        }

        private void claim(long reservationId, String kind, String windowId,
                OffsetDateTime start, OffsetDateTime end) {
            long claimId = reservationId + ("RETURN".equals(kind) ? 1001 : 1000);
            jdbc.update("""
                    INSERT INTO schedule_reservation_claim
                      (id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, claimId, reservationId, Long.parseLong(windowId), 300L,
                    "GENERAL".equals(kind) ? 400L : 401L, kind,
                    Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()));
        }

        CreateOrderCommand inStore(String requestId) {
            return new CreateOrderCommand(new CommandContext(requestId, "test", OperatorType.USER,
                    "100", "test"), "300", "400", "500", "IN_STORE", START, START.plusHours(1),
                    null, null, "901", null, null, null, null, null);
        }

        CreateOrderCommand pickup(String requestId, String couponId) {
            return new CreateOrderCommand(new CommandContext(requestId, "test", OperatorType.USER,
                    "100", "test"), "300", "401", "500", "PICKUP_DELIVERY", null, null,
                    START, START.plusHours(2), null, "902", "903", couponId, null,
                    "上海市长宁区虹桥路1号");
        }

        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        @Override public void close() { if (admin != null) admin.execute("DROP DATABASE IF EXISTS `" + name + "`"); }
    }

    private static final class TestGuard implements ScheduleCapacityGuardApi {
        private final JdbcTemplate jdbc;
        TestGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void acquire(List<String> storeIds, QueryContext context) {
            for (String store : storeIds) {
                long id = Long.parseLong(store);
                jdbc.update("INSERT INTO schedule_store_capacity_guard(store_id,version,updated_at) "
                        + "VALUES(?,0,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE store_id=store_id", id);
                jdbc.queryForObject("SELECT store_id FROM schedule_store_capacity_guard WHERE store_id=? FOR UPDATE",
                        Long.class, id);
            }
        }
        @Override public void requireHeld(String storeId, DataSource callerSource) {}
    }

    private static Timestamp stamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static DataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }
}
