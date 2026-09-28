package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.boot.config.BookingCreationConfiguration;
import com.petplatform.boot.config.ReservationProtectionFoundationConfiguration;
import com.petplatform.merchant.biz.apiimpl.BookingMerchantFactsApiImpl;
import com.petplatform.merchant.biz.application.MerchantAgreementEligibilityFactsAdapter;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.order.api.command.OrderCreationApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import com.petplatform.order.biz.application.OrderCreationInputProtection;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Independent booking acceptance against real MySQL. All created order, reservation,
 * claim, snapshot, audit and receipt rows must come from the production internal API.
 */
class BookingCreateAcceptanceTest {
    private static final long USER_A = 710100L;
    private static final long USER_B = 710101L;
    private static final long PET_A = 710200L;
    private static final long PET_B = 710201L;
    private static final long MERCHANT_OWNER = 710300L;
    private static final long MERCHANT = 710301L;
    private static final long STORE = 710302L;
    private static final long STAFF = 710303L;
    private static final long CATEGORY = 710400L;
    private static final long IN_STORE_SERVICE = 710401L;
    private static final long PICKUP_SERVICE = 710402L;
    private static final long GENERAL_WINDOW = 710500L;
    private static final long PICKUP_WINDOW = 710501L;
    private static final long RETURN_WINDOW = 710502L;
    private static final AtomicLong GENERATED_IDS = new AtomicLong(7_100_000_000_000_000L);
    private static final Instant NOW = Instant.parse("2029-12-31T00:00:00Z");
    private static final OffsetDateTime APPOINTMENT = OffsetDateTime.parse("2030-01-01T09:00:00Z");
    private static final OffsetDateTime APPOINTMENT_END = APPOINTMENT.plusMinutes(90);
    private static final OffsetDateTime PICKUP_START = OffsetDateTime.parse("2030-01-01T09:00:00Z");
    private static final OffsetDateTime RETURN_START = OffsetDateTime.parse("2030-01-01T11:30:00Z");

    @Test
    void fixtureSuppliesCompleteApprovedAndSignedMerchantFacts() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            var approved = new PersistentApplicationReviewFactsReader(db.source,
                    BookingCreateAcceptanceTest::id);
            var guard = new ScheduleCapacityGuardApiImpl(db.source);
            var merchant = new BookingMerchantFactsApiImpl(db.source, guard, approved);
            var service = new BookingServiceFactsApiImpl(db.source, guard);
            var bookingUser = new BookingUserFactsApiImpl(db.source, guard);
            QueryContext context = new QueryContext("booking-facts", OperatorType.USER,
                    Long.toString(USER_A));
            db.inTransaction(() -> {
                guard.acquire(List.of(Long.toString(STORE)), context);
                assertEquals("APPROVED", approved.read(MERCHANT).applicationStatus());
                var signed = new MerchantAgreementEligibilityFactsAdapter(db.source, approved)
                        .read(MERCHANT, STORE);
                assertEquals("SIGNED", signed.signingStatus());
                assertEquals("真实审核商家", merchant.readCurrentStore(Long.toString(STORE), context)
                        .merchantName());
                assertEquals("128.00", service.readCurrentService(Long.toString(STORE),
                        Long.toString(IN_STORE_SERVICE), context).price().toPlainString());
                assertEquals("阿黄", bookingUser.readCurrentPet(Long.toString(USER_A),
                        Long.toString(PET_A), Long.toString(STORE), context).name());
                return null;
            });
        }
    }

    @Test
    void bootWiringIsDefaultOffAndDoubleOptInRunsTheRealInternalKernel() throws Exception {
        var runner = new ApplicationContextRunner().withUserConfiguration(
                ReservationProtectionFoundationConfiguration.class, BookingCreationConfiguration.class);
        runner.run(context -> {
            assertFalse(context.containsBean("orderCreationApi"));
            assertFalse(context.containsBean("reservationHoldApi"));
        });
        try (Database db = new Database()) {
            db.seedBookableFacts();
            var withInfrastructure = runner
                    .withBean(DataSource.class, () -> db.source)
                    .withBean(com.petplatform.common.SnowflakeIdGenerator.class,
                            () -> BookingCreateAcceptanceTest::id)
                    .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
            withInfrastructure.withPropertyValues("pet.schedule.protection.enabled=true")
                    .run(context -> {
                        assertNotNull(context.getBean(com.petplatform.schedule.api.protection
                                .ScheduleCapacityGuardApi.class));
                        assertFalse(context.containsBean("orderCreationApi"));
                        assertFalse(context.containsBean("reservationHoldApi"));
                    });
            withInfrastructure.withPropertyValues("pet.schedule.protection.enabled=true",
                    "pet.order.creation.enabled=true").run(context -> {
                assertNotNull(context.getBean(ReservationHoldApi.class));
                OrderCreationApi creation = context.getBean(OrderCreationApi.class);
                CreateOrderResult receipt = creation.create(inStore(USER_A, PET_A, requestId()));
                assertTrue(receipt.created());
                assertEquals(1, db.count("pet_order"));
                assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> creation.create(
                        pickup(USER_B, PET_B, requestId(), "四川省成都市锦江区测试路1号")));
                CreateOrderCommand plain = inStore(USER_B, PET_B, requestId());
                CreateOrderCommand remarked = new CreateOrderCommand(plain.context(), plain.storeId(),
                        plain.serviceId(), plain.petId(), plain.fulfillmentType(),
                        plain.appointmentStart(), plain.appointmentEnd(), null, null,
                        plain.selectedGeneralWindowId(), null, null, null, "请联系我", null);
                assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> creation.create(remarked));
                assertEquals(1, db.count("pet_order"));
            });
        }
    }

    @Test
    void independentHoldCannotCommitWithoutMatchingOrderAndRollsBackClaims() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            var guard = new ScheduleCapacityGuardApiImpl(db.source);
            var schedule = new ScheduleProtectionFactsApiImpl(db.source, guard);
            var staff = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var orders = new OrderProtectionFactsApiImpl(db.source, guard, schedule, staff, clock);
            var proof = new ScheduleCapacityProofApiImpl(db.source, guard, schedule, staff,
                    orders, clock, 10_000);
            var hold = new ReservationHoldApiImpl(db.source, BookingCreateAcceptanceTest::id,
                    guard, schedule, proof, orders, clock);
            String request = requestId();
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> db.inTransaction(() -> {
                QueryContext query = new QueryContext("independent-hold", OperatorType.USER,
                        Long.toString(USER_A));
                guard.acquire(List.of(Long.toString(STORE)), query);
                hold.hold(new HoldCommand(user(USER_A, request), Long.toString(id()),
                        Long.toString(USER_A), Long.toString(MERCHANT), Long.toString(STORE),
                        Long.toString(IN_STORE_SERVICE), "IN_STORE", APPOINTMENT, APPOINTMENT_END,
                        null, null, Long.toString(GENERAL_WINDOW), null, null));
                return null;
            }));
            db.assertNoCreatedBusinessRows();
        }
    }

    @Test
    void realGeneralCreationBindsEveryRowAndPreservesCurrentOwnerSnapshots() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CreateOrderResult receipt = app.creation.create(inStore(USER_A, PET_A, requestId()));
            assertReceiptShape(receipt);
            assertTrue(receipt.created());
            assertEquals("128.00", receipt.payAmount());
            assertEquals(NOW.plusSeconds(600), receipt.paymentExpireAt().toInstant());
            for (String table : List.of("schedule_reservation", "schedule_reservation_claim",
                    "pet_order", "order_service_snapshot", "order_pet_snapshot",
                    "order_booking_input_snapshot", "order_status_log", "order_creation_audit",
                    "schedule_reservation_audit")) {
                assertEquals(1, db.count(table), table);
            }
            assertEquals(1, db.count("order_creation_request"));
            assertEquals("SUCCEEDED", db.jdbc.queryForObject(
                    "SELECT status FROM order_creation_request", String.class));
            assertEquals(Long.parseLong(receipt.orderId()), db.jdbc.queryForObject(
                    "SELECT order_id FROM schedule_reservation", Long.class));
            assertEquals(db.jdbc.queryForObject("SELECT reservation_id FROM pet_order", Long.class),
                    db.jdbc.queryForObject("SELECT id FROM schedule_reservation", Long.class));
            assertEquals(USER_A, db.jdbc.queryForObject("SELECT user_id FROM pet_order", Long.class));
            assertEquals(USER_A, db.jdbc.queryForObject("SELECT user_id FROM schedule_reservation", Long.class));
            assertEquals(MERCHANT, db.jdbc.queryForObject("SELECT merchant_id FROM pet_order", Long.class));
            assertEquals(STORE, db.jdbc.queryForObject("SELECT store_id FROM pet_order", Long.class));
            assertEquals(IN_STORE_SERVICE, db.jdbc.queryForObject("SELECT service_id FROM pet_order", Long.class));
            assertEquals(GENERAL_WINDOW, db.jdbc.queryForObject(
                    "SELECT window_id FROM schedule_reservation_claim", Long.class));
            assertEquals("GENERAL", db.jdbc.queryForObject(
                    "SELECT kind FROM schedule_reservation_claim", String.class));
            assertEquals(APPOINTMENT.toInstant(), db.jdbc.queryForObject(
                    "SELECT start_at FROM schedule_reservation_claim", java.sql.Timestamp.class).toInstant());
            assertEquals(APPOINTMENT_END.toInstant(), db.jdbc.queryForObject(
                    "SELECT end_at FROM schedule_reservation_claim", java.sql.Timestamp.class).toInstant());
            assertEquals(receipt.paymentExpireAt().toInstant(), db.jdbc.queryForObject(
                    "SELECT payment_expire_at FROM pet_order", java.sql.Timestamp.class).toInstant());
            assertEquals(receipt.paymentExpireAt().toInstant(), db.jdbc.queryForObject(
                    "SELECT lock_expire_at FROM schedule_reservation", java.sql.Timestamp.class).toInstant());
            assertEquals("真实审核商家", db.jdbc.queryForObject(
                    "SELECT merchant_name FROM order_service_snapshot", String.class));
            assertEquals("首店", db.jdbc.queryForObject(
                    "SELECT store_name FROM order_service_snapshot", String.class));
            assertEquals("128.00", db.jdbc.queryForObject(
                    "SELECT service_price FROM order_service_snapshot", java.math.BigDecimal.class)
                    .toPlainString());
            assertEquals(90, db.jdbc.queryForObject(
                    "SELECT service_duration_minutes FROM order_service_snapshot", Integer.class));
            assertEquals("0", db.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT("
                    + "snapshot_json,'$.serviceVersion')) FROM order_service_snapshot", String.class));
            assertEquals("DOG", db.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT("
                    + "snapshot_json,'$.applicablePetTypes[0]')) FROM order_service_snapshot", String.class));
            assertEquals("true", db.jdbc.queryForObject("SELECT JSON_EXTRACT("
                    + "snapshot_json,'$.verificationRequired') FROM order_service_snapshot", String.class));
            assertEquals("阿黄", db.jdbc.queryForObject(
                    "SELECT pet_name FROM order_pet_snapshot", String.class));
            assertEquals("DOG", db.jdbc.queryForObject(
                    "SELECT pet_type FROM order_pet_snapshot", String.class));
            assertEquals("HOLD", db.jdbc.queryForObject(
                    "SELECT action FROM schedule_reservation_audit", String.class));
            assertEquals("PENDING_PAYMENT", db.jdbc.queryForObject(
                    "SELECT to_status FROM order_status_log", String.class));
        }
    }

    @Test
    void twoDifferentUsersCompeteForLastCapacityAndOnlyOneBundleCommits() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                Future<Object> first = workers.submit(() -> {
                    start.await();
                    return attempt(app, inStore(USER_A, PET_A, requestId()));
                });
                Future<Object> second = workers.submit(() -> {
                    start.await();
                    return attempt(app, inStore(USER_B, PET_B, requestId()));
                });
                start.countDown();
                Object left = first.get(20, TimeUnit.SECONDS);
                Object right = second.get(20, TimeUnit.SECONDS);
                assertEquals(1, List.of(left, right).stream()
                        .filter(CreateOrderResult.class::isInstance).count());
                assertEquals(1, List.of(left, right).stream()
                        .filter(ApiException.class::isInstance).count());
                ApiException loser = (ApiException) (left instanceof ApiException ? left : right);
                assertEquals("SCHEDULE_CAPACITY_EXCEEDED", loser.code());
                CreateOrderResult winner = (CreateOrderResult)
                        (left instanceof CreateOrderResult ? left : right);
                assertReceiptShape(winner);
            }
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("order_service_snapshot"));
            assertEquals(1, db.count("order_pet_snapshot"));
            assertEquals(1, db.count("schedule_reservation_audit"));
            assertEquals(1, db.count("order_creation_audit"));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM order_creation_request "
                    + "WHERE status='SUCCEEDED'", Integer.class));
        }
    }

    @Test
    void sameKeyConcurrentCallsCreateOnceThenReplayWithoutConsumingMoreCapacity() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CreateOrderCommand command = pickup(USER_A, PET_A, requestId(),
                    "四川省成都市锦江区测试路1号");
            CountDownLatch start = new CountDownLatch(1);
            Object left, right;
            try (var workers = Executors.newFixedThreadPool(2)) {
                Future<Object> first = workers.submit(() -> {
                    start.await();
                    return attempt(app, command);
                });
                Future<Object> second = workers.submit(() -> {
                    start.await();
                    return attempt(app, command);
                });
                start.countDown();
                left = first.get(20, TimeUnit.SECONDS);
                right = second.get(20, TimeUnit.SECONDS);
            }
            if (left instanceof ApiException failure) {
                assertEquals("COMMON_CONFLICT", failure.code());
                left = app.creation.create(command);
            }
            if (right instanceof ApiException failure) {
                assertEquals("COMMON_CONFLICT", failure.code());
                right = app.creation.create(command);
            }
            CreateOrderResult a = (CreateOrderResult) left;
            CreateOrderResult b = (CreateOrderResult) right;
            assertEquals(a.orderId(), b.orderId());
            assertEquals(a.orderNo(), b.orderNo());
            assertEquals(a.paymentExpireAt(), b.paymentExpireAt());
            assertEquals(1, List.of(a, b).stream().filter(CreateOrderResult::created).count());
            assertEquals(1, List.of(a, b).stream().filter(CreateOrderResult::replayed).count());
            for (String table : List.of("schedule_reservation",
                    "pet_order", "order_service_snapshot", "order_pet_snapshot",
                    "order_booking_input_snapshot", "schedule_reservation_audit",
                    "order_creation_audit", "order_status_log", "order_creation_request")) {
                assertEquals(1, db.count(table), table);
            }
            assertEquals(2, db.count("schedule_reservation_claim"));
            CreateOrderCommand changed = new CreateOrderCommand(command.context(), command.storeId(),
                    command.serviceId(), command.petId(), command.fulfillmentType(),
                    null, null, command.pickupStart(), command.returnStart().plusMinutes(1),
                    null, command.selectedPickupWindowId(), command.selectedReturnWindowId(),
                    null, null, command.serviceAddress());
            assertCode("IDEMPOTENCY_KEY_CONFLICT", () -> app.creation.create(changed));
            db.jdbc.update("UPDATE service_item SET price=199.00 WHERE id=?", PICKUP_SERVICE);
            db.jdbc.update("UPDATE user_pet SET name='新名字' WHERE id=?", PET_A);
            CreateOrderResult replay = app.creation.create(command);
            assertTrue(replay.replayed());
            assertEquals(a.orderId(), replay.orderId());
            assertEquals("168.00", replay.payAmount());
            assertEquals("168.00", db.jdbc.queryForObject(
                    "SELECT service_price FROM order_service_snapshot", java.math.BigDecimal.class)
                    .toPlainString());
            assertEquals("阿黄", db.jdbc.queryForObject(
                    "SELECT pet_name FROM order_pet_snapshot", String.class));
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void everyFailureAfterHoldRollsBackTheWholeBundleAndOriginalKeyCanRetry() throws Exception {
        for (String failurePoint : List.of("pet_order", "order_service_snapshot",
                "order_pet_snapshot", "order_booking_input_snapshot", "order_status_log",
                "order_creation_audit", "success_receipt")) {
            try (Database db = new Database()) {
                db.seedBookableFacts();
                Components app = new Components(db);
                CreateOrderCommand command = inStore(USER_A, PET_A, requestId());
                if ("success_receipt".equals(failurePoint)) db.injectSuccessReceiptFailure();
                else db.injectFailureBeforeInsert(failurePoint);
                assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> app.creation.create(command));
                db.assertNoCreatedBusinessRows();
                assertEquals(1, db.count("order_creation_request"), failurePoint);
                assertEquals("RESERVED", db.jdbc.queryForObject(
                        "SELECT status FROM order_creation_request", String.class), failurePoint);
                CreateOrderCommand changed = new CreateOrderCommand(command.context(),
                        command.storeId(), command.serviceId(), command.petId(),
                        command.fulfillmentType(), command.appointmentStart(),
                        command.appointmentEnd().plusMinutes(1), null, null,
                        command.selectedGeneralWindowId(), null, null, null, null, null);
                assertCode("IDEMPOTENCY_KEY_CONFLICT", () -> app.creation.create(changed));
                db.removeInjectedFailure();
                CreateOrderResult recovered = app.creation.create(command);
                assertTrue(recovered.created(), failurePoint);
                assertEquals(1, db.count("schedule_reservation"), failurePoint);
                assertEquals(1, db.count("order_creation_audit"), failurePoint);
                assertEquals("SUCCEEDED", db.jdbc.queryForObject(
                        "SELECT status FROM order_creation_request", String.class), failurePoint);
            }
        }
    }

    @Test
    void pickupUsesTwoCompleteSelectedWindowsAndEncryptsTheServiceAddress() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            String address = "四川省成都市锦江区测试路1号";
            CreateOrderResult receipt = app.creation.create(pickup(USER_A, PET_A, requestId(), address));
            assertReceiptShape(receipt);
            assertTrue(receipt.created());
            assertEquals("168.00", receipt.payAmount());
            assertEquals(2, db.count("schedule_reservation_claim"));
            assertEquals(List.of("PICKUP", "RETURN"), db.jdbc.queryForList(
                    "SELECT kind FROM schedule_reservation_claim ORDER BY start_at", String.class));
            assertEquals(List.of(PICKUP_WINDOW, RETURN_WINDOW), db.jdbc.queryForList(
                    "SELECT window_id FROM schedule_reservation_claim ORDER BY start_at", Long.class));
            assertEquals(List.of("2030-01-01 09:00", "2030-01-01 11:30"), db.jdbc.queryForList(
                    "SELECT DATE_FORMAT(start_at,'%Y-%m-%d %H:%i') FROM schedule_reservation_claim "
                    + "ORDER BY start_at", String.class));
            assertEquals(List.of("2030-01-01 09:35", "2030-01-01 12:20"), db.jdbc.queryForList(
                    "SELECT DATE_FORMAT(end_at,'%Y-%m-%d %H:%i') FROM schedule_reservation_claim "
                    + "ORDER BY start_at", String.class));
            assertEquals(PICKUP_START.toInstant(), db.jdbc.queryForObject(
                    "SELECT appointment_start_at FROM pet_order", java.sql.Timestamp.class).toInstant());
            assertEquals(OffsetDateTime.parse("2030-01-01T12:20:00Z").toInstant(),
                    db.jdbc.queryForObject("SELECT appointment_end_at FROM pet_order",
                            java.sql.Timestamp.class).toInstant());
            assertNull(db.jdbc.queryForObject("SELECT service_staff_id FROM pet_order", Long.class));
            byte[] encrypted = db.jdbc.queryForObject(
                    "SELECT service_address_ciphertext FROM order_booking_input_snapshot", byte[].class);
            assertNotNull(encrypted);
            assertEquals(address, app.protector.decrypt(encrypted));
            assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains(address));
            byte[] canonical = db.jdbc.queryForObject(
                    "SELECT params_canonical FROM order_creation_request", byte[].class);
            assertFalse(new String(canonical, StandardCharsets.UTF_8).contains(address));
        }
    }

    @Test
    void bookingOnlyOtherToExoticMappingKeepsOriginalPetSnapshotAndAllStillAccepts() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            db.jdbc.update("UPDATE user_pet SET pet_type='OTHER' WHERE id=?", PET_A);
            db.jdbc.update("UPDATE service_item SET applicable_pet_types='EXOTIC' WHERE id=?",
                    IN_STORE_SERVICE);
            CreateOrderResult receipt = new Components(db).creation.create(inStore(USER_A, PET_A, requestId()));
            assertTrue(receipt.created());
            assertEquals("OTHER", db.jdbc.queryForObject(
                    "SELECT pet_type FROM order_pet_snapshot", String.class));
        }
        try (Database db = new Database()) {
            db.seedBookableFacts();
            db.jdbc.update("UPDATE user_pet SET pet_type='CAT' WHERE id=?", PET_A);
            Components app = new Components(db);
            assertCode("SERVICE_NOT_BOOKABLE", () -> app.creation.create(inStore(USER_A, PET_A, requestId())));
            db.assertNoCreatedBusinessRows();
            db.jdbc.update("UPDATE service_item SET applicable_pet_types='ALL' WHERE id=?",
                    IN_STORE_SERVICE);
            assertTrue(app.creation.create(inStore(USER_A, PET_A, requestId())).created());
            assertEquals("CAT", db.jdbc.queryForObject(
                    "SELECT pet_type FROM order_pet_snapshot", String.class));
        }
    }

    @Test
    void currentUserAndPetOwnershipAreCheckedBeforeCreateAndBeforeReceiptReplay() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            String sameUuid = requestId();
            CreateOrderCommand owner = inStore(USER_A, PET_A, sameUuid);
            assertTrue(app.creation.create(owner).created());
            assertCode("PET_NOT_FOUND", () -> app.creation.create(inStore(USER_B, PET_A, sameUuid)));
            assertEquals(1, db.count("pet_order"));
            db.jdbc.update("UPDATE user_account SET status='CANCELED' WHERE id=?", USER_A);
            assertCode("COMMON_FORBIDDEN", () -> app.creation.create(owner));
            assertEquals(1, db.count("pet_order"));
        }
    }

    @Test
    void committedActiveReservationWithoutItsOrderIsDamageNotFreeCapacity() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            long orphanOrder = id(), orphanReservation = id();
            db.jdbc.update("INSERT INTO schedule_reservation(id,order_id,user_id,merchant_id,"
                    + "store_id,service_id,fulfillment_type,start_at,end_at,status,lock_expire_at,"
                    + "capacity_snapshot,qualified_staff_count_snapshot,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,'IN_STORE','2030-01-01 09:00:00',"
                    + "'2030-01-01 10:30:00','TEMP_LOCKED','2030-01-01 11:00:00',1,1,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", orphanReservation, orphanOrder, USER_A,
                    MERCHANT, STORE, IN_STORE_SERVICE);
            db.jdbc.update("INSERT INTO schedule_reservation_claim(id,reservation_id,window_id,"
                    + "store_id,service_id,kind,start_at,end_at) VALUES(?,?,?,?,?,'GENERAL',"
                    + "'2030-01-01 09:00:00','2030-01-01 10:30:00')", id(), orphanReservation,
                    GENERAL_WINDOW, STORE, IN_STORE_SERVICE);
            Components app = new Components(db);
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> app.creation.create(
                    inStore(USER_B, PET_B, requestId())));
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(0, db.count("pet_order"));
            assertEquals(0, db.count("order_creation_audit"));
        }
    }

    @Test
    void invalidDurationCouponAndMissingRemarkModerationNeverCommitBusinessRows() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CreateOrderCommand base = inStore(USER_A, PET_A, requestId());
            CreateOrderCommand shortService = new CreateOrderCommand(base.context(),
                    base.storeId(), base.serviceId(), base.petId(), base.fulfillmentType(),
                    base.appointmentStart(), base.appointmentStart().plusMinutes(60), null, null,
                    base.selectedGeneralWindowId(), null, null, null, null, null);
            assertCode("SERVICE_NOT_BOOKABLE", () -> app.creation.create(shortService));
            db.assertNoCreatedBusinessRows();
            CreateOrderCommand couponBase = inStore(USER_A, PET_A, requestId());
            CreateOrderCommand coupon = new CreateOrderCommand(couponBase.context(),
                    couponBase.storeId(), couponBase.serviceId(), couponBase.petId(),
                    couponBase.fulfillmentType(), couponBase.appointmentStart(),
                    couponBase.appointmentEnd(), null, null, couponBase.selectedGeneralWindowId(),
                    null, null, "710999", null, null);
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> app.creation.create(coupon));
            db.assertNoCreatedBusinessRows();
            CreateOrderCommand remarkBase = inStore(USER_A, PET_A, requestId());
            CreateOrderCommand remark = new CreateOrderCommand(remarkBase.context(),
                    remarkBase.storeId(), remarkBase.serviceId(), remarkBase.petId(),
                    remarkBase.fulfillmentType(), remarkBase.appointmentStart(),
                    remarkBase.appointmentEnd(), null, null, remarkBase.selectedGeneralWindowId(),
                    null, null, null, "请提前沟通", null);
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> app.creation.create(remark));
            db.assertNoCreatedBusinessRows();
        }
    }

    @Test
    void invalidSelectedWindowAndShortPickupReturnGapNeverCreateClaims() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CreateOrderCommand normal = inStore(USER_A, PET_A, requestId());
            CreateOrderCommand wrongWindow = new CreateOrderCommand(normal.context(),
                    normal.storeId(), normal.serviceId(), normal.petId(), normal.fulfillmentType(),
                    normal.appointmentStart(), normal.appointmentEnd(), null, null,
                    Long.toString(PICKUP_WINDOW), null, null, null, null, null);
            org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> app.creation.create(wrongWindow));
            db.assertNoCreatedBusinessRows();
            CreateOrderCommand pickup = pickup(USER_A, PET_A, requestId(),
                    "四川省成都市锦江区测试路1号");
            CreateOrderCommand tooEarlyReturn = new CreateOrderCommand(pickup.context(),
                    pickup.storeId(), pickup.serviceId(), pickup.petId(), pickup.fulfillmentType(),
                    null, null, pickup.pickupStart(), pickup.pickupStart().plusMinutes(119),
                    null, pickup.selectedPickupWindowId(), pickup.selectedReturnWindowId(),
                    null, null, pickup.serviceAddress());
            assertCode("COMMON_INVALID_ARGUMENT", () -> app.creation.create(tooEarlyReturn));
            db.assertNoCreatedBusinessRows();
        }
    }

    @Test
    void sharedAgreementVersionAndCategoryRowLocksDoNotBlockUnrelatedBookingOwnership() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            Components app = new Components(db);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                Future<?> holder = workers.submit(() -> db.inTransaction(() -> {
                    assertEquals(710800L, db.jdbc.queryForObject(
                            "SELECT id FROM merchant_agreement_version WHERE id=710800 FOR UPDATE",
                            Long.class));
                    assertEquals(CATEGORY, db.jdbc.queryForObject(
                            "SELECT id FROM service_category WHERE id=? FOR UPDATE",
                            Long.class, CATEGORY));
                    locked.countDown();
                    assertTrue(release.await(12, TimeUnit.SECONDS));
                    return null;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                Future<CreateOrderResult> creator = workers.submit(() -> app.creation.create(
                        inStore(USER_A, PET_A, requestId())));
                try {
                    CreateOrderResult receipt = creator.get(6, TimeUnit.SECONDS);
                    assertTrue(receipt.created());
                    assertFalse(holder.isDone(), "shared immutable row holder must still be open");
                } finally {
                    release.countDown();
                }
                holder.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void lostCommitAcknowledgementRechecksOriginalKeyWithoutMakingSecondReservation() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            CreateOrderCommand command = inStore(USER_A, PET_A, requestId());
            CreateOrderResult recovered = new Components(db,
                    new LostCommitAckSource(db.source, false)).creation.create(command);
            assertTrue(recovered.replayed());
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(1, db.count("order_creation_audit"));
            CreateOrderResult later = new Components(db).creation.create(command);
            assertTrue(later.replayed());
            assertEquals(recovered.orderId(), later.orderId());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void lostAckWithUnavailablePrimaryFailsClosedUntilOriginalKeyCanBeRead() throws Exception {
        try (Database db = new Database()) {
            db.seedBookableFacts();
            CreateOrderCommand command = inStore(USER_A, PET_A, requestId());
            Components disconnected = new Components(db, new LostCommitAckSource(db.source, true));
            assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> disconnected.creation.create(command));
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("order_creation_audit"));
            CreateOrderResult later = new Components(db).creation.create(command);
            assertTrue(later.replayed());
            assertEquals(1, db.count("pet_order"));
        }
    }

    private static Object attempt(Components app, CreateOrderCommand command) {
        try { return app.creation.create(command); }
        catch (ApiException failure) { return failure; }
    }

    private static CommandContext user(long userId, String requestId) {
        return new CommandContext(requestId, "booking-qa", OperatorType.USER,
                Long.toString(userId), "BOOKING_QA");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static CreateOrderCommand inStore(long userId, long petId, String requestId) {
        return new CreateOrderCommand(user(userId, requestId), Long.toString(STORE),
                Long.toString(IN_STORE_SERVICE), Long.toString(petId), "IN_STORE",
                APPOINTMENT, APPOINTMENT_END, null, null, Long.toString(GENERAL_WINDOW),
                null, null, null, null, null);
    }

    private static CreateOrderCommand pickup(long userId, long petId, String requestId,
            String serviceAddress) {
        return new CreateOrderCommand(user(userId, requestId), Long.toString(STORE),
                Long.toString(PICKUP_SERVICE), Long.toString(petId), "PICKUP_DELIVERY",
                null, null, PICKUP_START, RETURN_START, null, Long.toString(PICKUP_WINDOW),
                Long.toString(RETURN_WINDOW), null, null, serviceAddress);
    }

    private static void assertCode(String expected, Executable action) {
        ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, action);
        assertEquals(expected, failure.code());
    }

    private static void assertPublicId(String id) {
        assertNotNull(id);
        assertTrue(id.matches("[1-9][0-9]*"), id);
        assertTrue(new java.math.BigInteger(id).compareTo(
                java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0, id);
    }

    private static void assertReceiptShape(CreateOrderResult receipt) {
        assertPublicId(receipt.orderId());
        assertPublicId(receipt.orderNo());
        assertEquals("PENDING_PAYMENT", receipt.displayStatus());
        assertNotNull(receipt.paymentExpireAt());
    }

    private static long id() {
        return GENERATED_IDS.incrementAndGet();
    }

    static final class InputProtector implements OrderCreationInputProtection {
        private final SecureRandom random = new SecureRandom();
        private final byte[] encryptionKey = new byte[32];
        private final byte[] equalityKey = new byte[32];

        InputProtector() {
            java.util.Arrays.fill(encryptionKey, (byte) 0x31);
            java.util.Arrays.fill(equalityKey, (byte) 0x52);
        }

        @Override public ProtectedInput protect(String purpose, String value) {
            try {
                byte[] nonce = new byte[12];
                random.nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                        new GCMParameterSpec(128, nonce));
                byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(equalityKey, "HmacSHA256"));
                byte[] token = mac.doFinal((purpose + "\0" + value).getBytes(StandardCharsets.UTF_8));
                return new ProtectedInput(ByteBuffer.allocate(nonce.length + encrypted.length)
                        .put(nonce).put(encrypted).array(), token);
            } catch (Exception failure) {
                throw new IllegalStateException("test protector failed", failure);
            }
        }

        private String decrypt(byte[] ciphertext) {
            try {
                byte[] nonce = java.util.Arrays.copyOfRange(ciphertext, 0, 12);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                        new GCMParameterSpec(128, nonce));
                return new String(cipher.doFinal(ciphertext, 12, ciphertext.length - 12),
                        StandardCharsets.UTF_8);
            } catch (Exception failure) {
                throw new IllegalStateException("test decrypt failed", failure);
            }
        }
    }

    private static final class Components {
        private final ScheduleCapacityGuardApiImpl guard;
        private final ReservationHoldApiImpl hold;
        private final InputProtector protector;
        private final OrderCreationApiImpl creation;

        private Components(Database db) {
            this(db, db.source);
        }

        private Components(Database db, DataSource source) {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            guard = new ScheduleCapacityGuardApiImpl(source);
            var schedule = new ScheduleProtectionFactsApiImpl(source, guard);
            var staff = new MerchantCurrentStaffFactsApiImpl(source, guard);
            var order = new OrderProtectionFactsApiImpl(source, guard, schedule, staff, clock);
            var proof = new ScheduleCapacityProofApiImpl(source, guard, schedule, staff,
                    order, clock, 10_000);
            hold = new ReservationHoldApiImpl(source, BookingCreateAcceptanceTest::id,
                    guard, schedule, proof, order, clock);
            var approved = new PersistentApplicationReviewFactsReader(source,
                    BookingCreateAcceptanceTest::id);
            var users = new BookingUserFactsApiImpl(source, guard);
            var merchants = new BookingMerchantFactsApiImpl(source, guard, approved);
            var services = new BookingServiceFactsApiImpl(source, guard);
            protector = new InputProtector();
            creation = new OrderCreationApiImpl(source, BookingCreateAcceptanceTest::id,
                    users, merchants, services, guard, hold, protector, null, clock);
        }
    }

    /** The actual MySQL commit completes, then the client loses its acknowledgement. */
    private static final class LostCommitAckSource extends DelegatingDataSource {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final boolean denyRecoveryReads;

        private LostCommitAckSource(DataSource target, boolean denyRecoveryReads) {
            super(target);
            this.denyRecoveryReads = denyRecoveryReads;
        }

        @Override public Connection getConnection() throws SQLException {
            if (denyRecoveryReads && disconnected.get()) throw new SQLException("primary unavailable", "08006");
            return wrap(super.getConnection());
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            if (denyRecoveryReads && disconnected.get()) throw new SQLException("primary unavailable", "08006");
            return wrap(super.getConnection(username, password));
        }

        private Connection wrap(Connection delegate) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if ("commit".equals(method.getName()) && commits.incrementAndGet() == 2) {
                                disconnected.set(true);
                                throw new SQLException("commit ACK lost", "08006");
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
        }
    }

    /** Isolated schema and durable result oracle; never creates business outcome rows. */
    static final class Database implements AutoCloseable {
        private static final List<String> SCHEMA = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "13-Async-Infra-Schema-v0.1.sql",
                "28-Merchant-Agreement-Schema-v0.1.sql",
                "29-Merchant-Application-Schema-v0.1.sql",
                "33-Service-Write-Schema-v0.1.sql",
                "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                "38-Booking-Create-Schema-v0.1.sql",
                "39-Booking-Expiry-Schema-v0.1.sql");
        private final String name = "qa_booking_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        private final DataSourceTransactionManager manager;

        Database() throws Exception {
            var environment = System.getenv();
            String prefix = environment.containsKey("BOOKING_MYSQL_URL") ? "BOOKING" : "AUTH";
            String url = environment.get(prefix + "_MYSQL_URL");
            String username = environment.get(prefix + "_MYSQL_USER");
            String password = environment.get(prefix + "_MYSQL_PASSWORD");
            if (url == null || username == null || password == null) {
                throw new IllegalStateException("set BOOKING_MYSQL_URL/USER/PASSWORD or "
                        + "AUTH_MYSQL_URL/USER/PASSWORD for the isolated MySQL test");
            }
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must be a local server root");
            }
            admin = new JdbcTemplate(source(url, username, password));
            source = source(url + name, username, password);
            jdbc = new JdbcTemplate(source);
            manager = new DataSourceTransactionManager(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                for (String schema : SCHEMA) script(schema);
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private void script(String filename) throws Exception {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("docs/03-database/" + filename))) {
                root = root.getParent();
            }
            assertNotNull(root, "schema not found: " + filename);
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                        root.resolve("docs/03-database/" + filename)), StandardCharsets.UTF_8));
            }
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password) {
                @Override public Connection getConnection() throws SQLException {
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

        private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
            TransactionTemplate transaction = new TransactionTemplate(manager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return transaction.execute(status -> {
                try { return action.call(); }
                catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
        }

        void seedBookableFacts() {
            for (long userId : List.of(USER_A, USER_B, MERCHANT_OWNER)) {
                jdbc.update("INSERT INTO user_account(id,nickname,status,created_at,updated_at)"
                        + " VALUES(?,'Booking QA','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", userId);
            }
            jdbc.update("INSERT INTO user_pet(id,user_id,name,pet_type,breed_name,sex,weight_kg,"
                    + "health_note,status,created_at,updated_at) VALUES(?,?,'阿黄','DOG','柯基','MALE',"
                    + "12.50,'怕生','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", PET_A, USER_A);
            jdbc.update("INSERT INTO user_pet(id,user_id,name,pet_type,breed_name,sex,weight_kg,"
                    + "health_note,status,created_at,updated_at) VALUES(?,?,'阿白','DOG','比熊','FEMALE',"
                    + "5.20,'无','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", PET_B, USER_B);
            jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,created_at,updated_at)"
                    + " VALUES(?,?,'真实审核商家','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    MERCHANT, MERCHANT_OWNER);
            jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,created_at,updated_at)"
                    + " VALUES(?,?,'首店','成都市锦江区测试路1号','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    STORE, MERCHANT);
            seedApprovedApplicationAndAgreement();
            jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,"
                    + "employment_status,service_enabled,created_at,updated_at)"
                    + " VALUES(?,?,?,'服务员工','ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    STAFF, MERCHANT, STORE);
            jdbc.update("INSERT INTO service_category(id,category_name,status,created_at,updated_at)"
                    + " VALUES(?,'预约验收类目','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", CATEGORY);
            seedService(IN_STORE_SERVICE, "IN_STORE", "到店护理", 128, 90);
            seedService(PICKUP_SERVICE, "PICKUP_DELIVERY", "上门接送", 168, 90);
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at)"
                    + " VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id(), STAFF, IN_STORE_SERVICE);
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at)"
                    + " VALUES(?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id(), STAFF, PICKUP_SERVICE);
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,"
                    + "created_at,updated_at) VALUES(?,?,?,'2030-01-01 08:00:00','2030-01-01 14:00:00',"
                    + "'AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id(), STORE, STAFF);
            seedWindow(GENERAL_WINDOW, IN_STORE_SERVICE, "GENERAL",
                    "2030-01-01 09:00:00", "2030-01-01 11:00:00");
            seedWindow(PICKUP_WINDOW, PICKUP_SERVICE, "PICKUP",
                    "2030-01-01 09:00:00", "2030-01-01 09:35:00");
            seedWindow(RETURN_WINDOW, PICKUP_SERVICE, "RETURN",
                    "2030-01-01 11:30:00", "2030-01-01 12:20:00");
        }

        private void seedService(long serviceId, String fulfillment, String name, int price,
                int durationMinutes) {
            jdbc.update("INSERT INTO service_item(id,merchant_id,store_id,category_id,service_name,"
                    + "description,price,duration_minutes,fulfillment_type,status,cover_asset_id,"
                    + "applicable_pet_types,verification_required,submission_no,owner_user_id,submitted_at,"
                    + "created_at,updated_at) VALUES(?,?,?,?,?,'真实服务',?,?,?,'ACTIVE',?,"
                    + "'DOG',1,1,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    serviceId, MERCHANT, STORE, CATEGORY, name, price, durationMinutes,
                    fulfillment, serviceId + 100_000, MERCHANT_OWNER);
            jdbc.update("INSERT INTO service_review_decision(id,service_id,submission_no,decision_type,"
                    + "decided_by_operator_id,decided_at,authz_version,scope_version,request_id,created_at)"
                    + " VALUES(?,?,1,'APPROVE',9001,UTC_TIMESTAMP(3),'qa-authz','qa-scope',?,UTC_TIMESTAMP(3))",
                    id(), serviceId, ("service-approve-" + serviceId).getBytes(StandardCharsets.UTF_8));
        }

        private void seedWindow(long windowId, long serviceId, String kind, String start, String end) {
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,"
                    + "start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,1,'OPEN',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    windowId, MERCHANT, STORE, serviceId, start, end, kind);
        }

        private void seedApprovedApplicationAndAgreement() {
            // A complete SQL29 projection proves the current MER fact source; this test
            // does not claim to exercise the application review or signing write flows.
            long application = 710600L, revision = 710601L, creditMaterial = 710602L;
            long identityMaterial = 710603L, creditEvidence = 710604L;
            long identityEvidence = 710605L, creditClaim = 710606L;
            long identityClaim = 710607L, task = 710608L, decision = 710609L, audit = 710610L;
            byte[] creditDigest = digest(1), identityDigest = digest(2);
            jdbc.update("INSERT INTO merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)"
                    + " VALUES(1,'qa-v1','HMAC-SHA-256',UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,"
                    + "subject_verification_status,created_at,updated_at)"
                    + " VALUES(?,?,?,'DRAFT','NOT_STARTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    application, MERCHANT_OWNER, MERCHANT);
            jdbc.update("INSERT INTO merchant_application_revision(id,application_id,revision_no,"
                    + "merchant_name,created_by_user_id,created_at) VALUES(?,?,1,'真实审核商家',?,UTC_TIMESTAMP(3))",
                    revision, application, MERCHANT_OWNER);
            jdbc.update("UPDATE merchant_application SET current_revision_id=? WHERE id=?", revision, application);
            seedMaterial(application, creditMaterial, 710700L, "BUSINESS_LICENSE", "a".repeat(64));
            seedMaterial(application, identityMaterial, 710701L, "ID_CARD_FRONT", "b".repeat(64));
            for (Object[] row : List.of(new Object[] {creditMaterial, "BUSINESS_LICENSE"},
                    new Object[] {identityMaterial, "ID_CARD_FRONT"})) {
                jdbc.update("INSERT INTO merchant_application_revision_material(application_id,revision_id,"
                        + "material_id,material_type,position) VALUES(?,?,?,?,1)", application, revision,
                        row[0], row[1]);
            }
            seedEvidence(application, revision, creditMaterial, creditEvidence,
                    "BUSINESS_LICENSE", "a".repeat(64), "CREDIT_CODE", creditDigest);
            seedEvidence(application, revision, identityMaterial, identityEvidence,
                    "ID_CARD_FRONT", "b".repeat(64), "IDENTITY_NUMBER", identityDigest);
            seedClaim(application, revision, creditEvidence, creditClaim, "CREDIT_CODE", creditDigest);
            seedClaim(application, revision, identityEvidence, identityClaim,
                    "IDENTITY_NUMBER", identityDigest);
            jdbc.update("INSERT INTO merchant_application_review_task(id,application_id,submitted_revision_id,"
                    + "submission_no,status,version,updated_at) VALUES(?,?,?,1,'AVAILABLE',0,UTC_TIMESTAMP(3))",
                    task, application, revision);
            jdbc.update("UPDATE merchant_application_review_task SET status='CLAIMED',"
                    + "claimed_by_operator_id=9001,claimed_at=UTC_TIMESTAMP(3),version=1 WHERE id=?", task);
            jdbc.update("UPDATE merchant_application SET application_no='SQ20260927ABCDEFGH',"
                    + "status='REVIEWING',current_revision_id=?,submitted_revision_id=?,"
                    + "current_review_task_id=?,subject_verification_status='VERIFIED',"
                    + "submitted_at=UTC_TIMESTAMP(3),version=1 WHERE id=?",
                    revision, revision, task, application);
            jdbc.update("INSERT INTO merchant_application_review_decision(id,application_id,"
                    + "submitted_revision_id,task_id,decision_type,decided_by_operator_id,decided_at,"
                    + "authz_version,scope_version,request_id,credit_evidence_id,credit_evidence_type,"
                    + "credit_evidence_status,credit_evidence_digest,credit_evidence_policy_slot,"
                    + "credit_evidence_key_version,identity_evidence_id,identity_evidence_type,"
                    + "identity_evidence_status,identity_evidence_digest,identity_evidence_policy_slot,"
                    + "identity_evidence_key_version,credit_claim_id,credit_claim_type,credit_claim_status,"
                    + "identity_claim_id,identity_claim_type,identity_claim_status) VALUES(?,?,?,?,"
                    + "'APPROVE',9001,UTC_TIMESTAMP(3),'qa-authz','qa-scope',?,"
                    + "?,'CREDIT_CODE','VERIFIED',?,1,'qa-v1',"
                    + "?,'IDENTITY_NUMBER','VERIFIED',?,1,'qa-v1',"
                    + "?,'CREDIT_CODE','ACTIVE',?,'IDENTITY_NUMBER','ACTIVE')",
                    decision, application, revision, task, "booking-decision".getBytes(StandardCharsets.UTF_8),
                    creditEvidence, creditDigest, identityEvidence, identityDigest, creditClaim, identityClaim);
            jdbc.update("UPDATE merchant_application_review_task SET status='CLOSED',"
                    + "closed_at=UTC_TIMESTAMP(3),version=2 WHERE id=?", task);
            jdbc.update("INSERT INTO merchant_application_audit(id,application_id,revision_id,actor_type,"
                    + "actor_id,action_code,from_status,to_status,request_id,occurred_at,decision_id)"
                    + " VALUES(?,?,?,'PLATFORM_OPERATOR',9001,'DECISION','REVIEWING','APPROVED',?,"
                    + "UTC_TIMESTAMP(3),?)", audit, application, revision,
                    "booking-review-audit".getBytes(StandardCharsets.UTF_8), decision);
            jdbc.update("UPDATE merchant_application SET status='APPROVED',current_decision_id=?,"
                    + "current_decision_type='APPROVE',review_audit_id=?,current_credit_claim_id=?,"
                    + "current_credit_claim_type='CREDIT_CODE',current_credit_claim_status='ACTIVE',"
                    + "current_identity_claim_id=?,current_identity_claim_type='IDENTITY_NUMBER',"
                    + "current_identity_claim_status='ACTIVE',reviewed_at=UTC_TIMESTAMP(3),version=2"
                    + " WHERE id=?", decision, audit, creditClaim, identityClaim, application);
            jdbc.update("INSERT INTO merchant_profile_compat(merchant_id,application_id,source_revision_id,"
                    + "merchant_type_code,city_code,source_kind,version,created_at,updated_at)"
                    + " VALUES(?,?,?,'PET_SHOP','510100','APPLICATION',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    MERCHANT, application, revision);
            String agreementContent = "验收服务协议";
            String agreementHash;
            try {
                agreementHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(agreementContent.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            jdbc.update("INSERT INTO merchant_agreement_version(id,agreement_version,content,content_sha256,"
                    + "published_at,published_by_operator_id) VALUES(710800,'qa-v1','验收服务协议',?,"
                    + "UTC_TIMESTAMP(3),9001)", agreementHash);
            jdbc.update("INSERT INTO merchant_agreement_current(agreement_key,agreement_version_id,"
                    + "updated_at) VALUES('MERCHANT',710800,UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO merchant_agreement_acceptance(id,merchant_id,agreement_version_id,"
                    + "accepted_by_user_id,accepted_at,content_sha256)"
                    + " VALUES(710801,?,710800,?,UTC_TIMESTAMP(3),?)",
                    MERCHANT, MERCHANT_OWNER, agreementHash);
        }

        private void seedMaterial(long application, long material, long asset, String type, String hash) {
            jdbc.update("INSERT INTO merchant_application_material(id,application_id,material_type,"
                    + "private_asset_id,sha256,media_type,bytes,uploaded_by_user_id,created_at)"
                    + " VALUES(?,?,?,?,?,'image/jpeg',1024,?,UTC_TIMESTAMP(3))",
                    material, application, type, asset, hash, MERCHANT_OWNER);
        }

        private void seedEvidence(long application, long revision, long material, long evidence,
                String materialType, String hash, String credentialType, byte[] digest) {
            jdbc.update("INSERT INTO merchant_credential_evidence(id,application_id,revision_id,"
                    + "material_id,material_type,material_sha256,evidence_source,evidence_status,"
                    + "credential_type,subject_name_protected,identifier_protected,identifier_lookup_digest,"
                    + "lookup_policy_slot,lookup_key_version,valid_from,valid_to,validity_kind,"
                    + "verified_by_operator_id,verification_reason,observed_at)"
                    + " VALUES(?,?,?,?,?,?,'MANUAL','VERIFIED',?,?,?, ?,1,'qa-v1',"
                    + "'2020-01-01','2035-01-01','DATED',9001,'人工核验原件与主体一致',UTC_TIMESTAMP(3))",
                    evidence, application, revision, material, materialType, hash, credentialType,
                    new byte[] {1}, new byte[] {2}, digest);
        }

        private void seedClaim(long application, long revision, long evidence, long claim,
                String type, byte[] digest) {
            jdbc.update("INSERT INTO merchant_subject_claim(id,claim_type,lookup_digest,"
                    + "lookup_policy_slot,lookup_key_version,application_id,revision_id,evidence_id,"
                    + "evidence_status,status,claimed_at) VALUES(?,?,?,1,'qa-v1',?,?,?,'VERIFIED',"
                    + "'ACTIVE',UTC_TIMESTAMP(3))", claim, type, digest, application, revision, evidence);
        }

        private byte[] digest(int endByte) {
            byte[] digest = new byte[32];
            digest[31] = (byte) endByte;
            return digest;
        }

        private int count(String table) {
            if (!List.of("schedule_reservation", "schedule_reservation_claim", "pet_order",
                    "order_service_snapshot", "order_pet_snapshot", "order_booking_input_snapshot",
                    "order_status_log", "order_creation_audit", "schedule_reservation_audit",
                    "order_creation_request").contains(table)) {
                throw new IllegalArgumentException("unsupported oracle table");
            }
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        private void assertNoCreatedBusinessRows() {
            for (String table : List.of("schedule_reservation", "schedule_reservation_claim",
                    "pet_order", "order_service_snapshot", "order_pet_snapshot",
                    "order_booking_input_snapshot", "order_status_log", "order_creation_audit",
                    "schedule_reservation_audit")) {
                assertEquals(0, count(table), table);
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_creation_request "
                    + "WHERE status='SUCCEEDED'", Integer.class));
        }

        private void injectFailureBeforeInsert(String table) {
            if (!List.of("pet_order", "order_service_snapshot", "order_pet_snapshot",
                    "order_booking_input_snapshot", "order_creation_audit", "order_status_log")
                    .contains(table)) {
                throw new IllegalArgumentException("unsupported injection table");
            }
            jdbc.execute("CREATE TRIGGER qa_fail_booking BEFORE INSERT ON " + table
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_INJECTED_FAILURE'");
        }

        private void injectSuccessReceiptFailure() {
            jdbc.execute("CREATE TRIGGER qa_fail_booking BEFORE UPDATE ON order_creation_request "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_INJECTED_FAILURE'");
        }

        private void removeInjectedFailure() {
            jdbc.execute("DROP TRIGGER IF EXISTS qa_fail_booking");
        }

        @Override public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
