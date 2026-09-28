package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.BookingMerchantFacts;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ReconcileExpiryTasksCommand;
import com.petplatform.order.api.dto.OrderPaymentResultTypes.ConsumePaymentResult;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.order.biz.apiimpl.OrderExpiryApiImpl;
import com.petplatform.order.biz.apiimpl.OrderPaymentResultApiImpl;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.order.biz.application.OrderCreationInputProtection;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** SQL06/37/38 order-side integration. SCH's solver and real Owner adapters have separate tests. */
class OrderCreationMySqlTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2030-01-01T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2029-12-31T00:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime PAID_AT = OffsetDateTime.parse("2029-12-31T00:05:00Z");

    @Test
    void paymentEventAtomicallyMarksOrderAndHoldPaidAndRepeatsWithoutNewOutbox() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("paid-" + UUID.randomUUID()));
            var consumer = db.paymentResultApi(created.orderId());
            consumer.consume(db.paymentEvent(created.orderId()));
            assertEquals("PENDING_CONFIRM", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
            assertEquals("PAID", db.jdbc.queryForObject(
                    "SELECT payment_status FROM pet_order", String.class));
            assertEquals("CONFIRMED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation", String.class));
            assertEquals(PAID_AT.plusMinutes(30).toInstant(), db.utcInstant(
                    "SELECT confirm_deadline FROM pet_order"));
            assertEquals("NORMAL", db.jdbc.queryForObject(
                    "SELECT result_type FROM order_payment_result", String.class));
            assertEquals("OrderPaidEvent.v1", db.jdbc.queryForObject(
                    "SELECT event_type FROM integration_event_outbox", String.class));
            assertEquals(1, db.count("integration_event_consume_log"));
            consumer.consume(db.paymentEvent(created.orderId()));
            assertEquals(1, db.count("order_payment_result"));
            assertEquals(1, db.count("integration_event_outbox"));
            assertEquals(1, db.count("integration_event_consume_log"));
        }
    }

    @Test
    void failedReservationConfirmRollsBackPaymentResultAndConsumeClaim() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("paid-fail-" + UUID.randomUUID()));
            db.holdConfirmFails = true;
            var consumer = db.paymentResultApi(created.orderId());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> consumer.consume(db.paymentEvent(created.orderId())))
                            .code());
            assertEquals("PENDING_PAYMENT", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
            assertEquals("TEMP_LOCKED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation", String.class));
            assertEquals(0, db.count("order_payment_result"));
            assertEquals(0, db.count("integration_event_outbox"));
            assertEquals(0, db.count("integration_event_consume_log"));
        }
    }

    @Test
    void mismatchedSourceEventCannotAuthorizePaymentTransition() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("paid-mismatch-" + UUID.randomUUID()));
            var legitimate = db.paymentEvent(created.orderId());
            var forged = new DispatchedEvent("9002", legitimate.eventType(),
                    legitimate.eventVersion(), legitimate.occurredAt(),
                    legitimate.aggregateType(), legitimate.aggregateId(),
                    legitimate.traceId(), legitimate.payloadJson());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class,
                            () -> db.paymentResultApi(created.orderId()).consume(forged)).code());
            assertEquals("PENDING_PAYMENT", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
            assertEquals(0, db.count("order_payment_result"));
            assertEquals(0, db.count("integration_event_consume_log"));
        }
    }

    @Test
    void latePaidEventKeepsCanceledOrderAndExpiredClaimAndEmitsOnlyLateEvent() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("late-" + UUID.randomUUID()));
            var expiry = db.expiryCommand(created.orderId(), db.makeDue());
            assertEquals(ExpireOrderResult.CLOSED, db.expiryApi().expire(expiry));
            var consumer = db.paymentResultApi(created.orderId());
            consumer.consume(db.paymentEvent(created.orderId()));
            assertEquals("CANCELED", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
            assertEquals("PAID", db.jdbc.queryForObject(
                    "SELECT payment_status FROM pet_order", String.class));
            assertEquals("EXPIRED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation", String.class));
            assertEquals("LATE", db.jdbc.queryForObject(
                    "SELECT result_type FROM order_payment_result", String.class));
            assertEquals("LatePaymentSucceededAfterTimeoutEvent.v1", db.jdbc.queryForObject(
                    "SELECT event_type FROM integration_event_outbox", String.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='OrderPaidEvent.v1'", Integer.class));
            assertEquals(ExpireOrderResult.NOOP, db.expiryApi().expire(expiry));
            consumer.consume(db.paymentEvent(created.orderId()));
            assertEquals(1, db.count("order_payment_result"));
            assertEquals(1, db.count("integration_event_outbox"));
        }
    }

    @Test
    void expiryClosesOrderAndHoldInOneTransactionAndReplayKeepsClaims() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("expire-" + UUID.randomUUID()));
            OffsetDateTime deadline = db.makeDue();
            var command = db.expiryCommand(created.orderId(), deadline);
            assertEquals(ExpireOrderResult.CLOSED, db.expiryApi().expire(command));
            assertEquals("CANCELED", db.jdbc.queryForObject("SELECT order_stage FROM pet_order", String.class));
            assertEquals("EXPIRED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation", String.class));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(2, db.count("order_status_log"));
            assertEquals(ExpireOrderResult.NOOP, db.expiryApi().expire(command));
            assertEquals(2, db.count("order_status_log"));
        }
    }

    @Test
    void unknownPaymentAndFailedHoldMutationKeepOrderAndClaimProtected() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("unknown-pay-" + UUID.randomUUID()));
            var command = db.expiryCommand(created.orderId(), db.makeDue());
            db.paymentExposed = true;
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> db.expiryApi().expire(command)).code());
            db.paymentExposed = false;
            db.holdExpiryFails = true;
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> db.expiryApi().expire(command)).code());
            assertEquals("PENDING_PAYMENT", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
            assertEquals("TEMP_LOCKED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation", String.class));
            assertEquals(1, db.count("order_status_log"));
            assertEquals(1, db.count("schedule_reservation_claim"));
        }
    }

    @Test
    void earlyTaskDoesNotCloseAndBoundedRecoveryRestoresMissingTask() throws Exception {
        try (Database db = new Database()) {
            var created = db.api().create(db.inStore("recover-" + UUID.randomUUID()));
            var reservationId = db.jdbc.queryForObject(
                    "SELECT reservation_id FROM pet_order", Long.class).toString();
            var command = new ExpireOrderCommand(
                    new CommandContext("TASK:RESERVATION_HOLD_EXPIRE:" + reservationId + ":0",
                            "test", OperatorType.SYSTEM, "0", "async_task"),
                    created.orderId(), reservationId, 0, created.paymentExpireAt());
            assertEquals(ExpireOrderResult.NOT_DUE, db.expiryApi().expire(command));
            db.jdbc.update("DELETE FROM async_task");
            var scan = new ReconcileExpiryTasksCommand(new CommandContext(
                    "reconcile-" + UUID.randomUUID(), "test", OperatorType.SYSTEM,
                    "0", "maintenance"), 10, "0");
            assertEquals(1, db.expiryApi().reconcileMissingTasks(scan).scanned());
            assertEquals(1, db.count("async_task"));
            assertEquals(1, db.expiryApi().reconcileMissingTasks(scan).scanned());
            assertEquals(1, db.count("async_task"));
            assertEquals("PENDING_PAYMENT", db.jdbc.queryForObject(
                    "SELECT order_stage FROM pet_order", String.class));
        }
    }

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
            assertEquals(1, db.count("async_task"));
            assertEquals("RESERVATION_HOLD_EXPIRE", db.jdbc.queryForObject(
                    "SELECT task_type FROM async_task", String.class));
            assertEquals(first.paymentExpireAt().toInstant(), db.utcInstant(
                    "SELECT execute_at FROM async_task"));
            assertAll(
                    () -> assertEquals("2029-12-31T00:10:00.000", db.jdbc.queryForObject(
                            "SELECT DATE_FORMAT(execute_at,'%Y-%m-%dT%H:%i:%s.%f') FROM async_task",
                            String.class).substring(0, 23), "task"),
                    () -> assertEquals("2029-12-31T00:10:00.000", db.jdbc.queryForObject(
                            "SELECT DATE_FORMAT(payment_expire_at,'%Y-%m-%dT%H:%i:%s.%f') FROM pet_order",
                            String.class).substring(0, 23), "order"),
                    () -> assertEquals("2029-12-31T00:10:00.000", db.jdbc.queryForObject(
                            "SELECT DATE_FORMAT(lock_expire_at,'%Y-%m-%dT%H:%i:%s.%f') FROM schedule_reservation",
                            String.class).substring(0, 23), "reservation"));
            assertEquals(first.paymentExpireAt().toInstant(), db.utcInstant(
                    "SELECT payment_expire_at FROM pet_order"));
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
            assertEquals(0, db.count("async_task"));
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
            assertEquals(START.toInstant(), db.utcInstant(
                    "SELECT appointment_start_at FROM pet_order"));
            assertEquals(START.plusHours(3).toInstant(), db.utcInstant(
                    "SELECT appointment_end_at FROM pet_order"));
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

    @Test
    void committedBusinessTransactionWithLostAckReturnsOriginalReceipt() throws Exception {
        try (Database db = new Database()) {
            DataSource faulting = new LostCommitAckSource(db.source, 2);
            var command = db.inStore("unknown-ack-" + UUID.randomUUID());
            var recovered = db.api(faulting).create(command);
            assertTrue(recovered.replayed());
            assertEquals(1, db.count("pet_order"));
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals("SUCCEEDED", db.jdbc.queryForObject(
                    "SELECT status FROM order_creation_request", String.class));
            assertEquals(recovered.orderId(), db.api().create(command).orderId());
        }
    }

    @Test
    void lostAckAndUnavailablePrimaryReadReturns503UntilOriginalKeyCanBeResolved() throws Exception {
        try (Database db = new Database()) {
            var command = db.inStore("unresolved-ack-" + UUID.randomUUID());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class,
                            () -> db.api(new LostCommitAckSource(db.source, 2, true)).create(command)).code());
            assertEquals(1, db.count("pet_order"));
            assertTrue(db.api().create(command).replayed());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void rejectsInvalidTraceBeforeBindingAndAcceptsNullInternalTrace() throws Exception {
        try (Database db = new Database()) {
            var base = db.inStore("trace-" + UUID.randomUUID());
            var bad = new CreateOrderCommand(new CommandContext(base.context().requestId(),
                    "broken\ntrace", OperatorType.USER, "100", "test"), base.storeId(),
                    base.serviceId(), base.petId(), base.fulfillmentType(), base.appointmentStart(),
                    base.appointmentEnd(), null, null, base.selectedGeneralWindowId(), null, null,
                    null, null, null);
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    assertThrows(ApiException.class, () -> db.api().create(bad)).code());
            assertEquals(0, db.count("order_creation_request"));
            var valid = new CreateOrderCommand(new CommandContext(base.context().requestId(),
                    null, OperatorType.USER, "100", "test"), base.storeId(), base.serviceId(),
                    base.petId(), base.fulfillmentType(), base.appointmentStart(), base.appointmentEnd(),
                    null, null, base.selectedGeneralWindowId(), null, null, null, null, null);
            assertTrue(db.api().create(valid).created());
            assertEquals(1, db.count("order_creation_request"));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_creation_audit WHERE trace_id IS NOT NULL", Integer.class));
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
        volatile boolean paymentExposed = false;
        volatile boolean couponExposed = false;
        volatile boolean holdExpiryFails = false;
        volatile boolean holdConfirmFails = false;
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
                            "13-Async-Infra-Schema-v0.1.sql",
                            "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                            "38-Booking-Create-Schema-v0.1.sql",
                            "39-Booking-Expiry-Schema-v0.1.sql",
                            "40-Payment-Foundation-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        OrderCreationApiImpl api() { return api(source); }

        OffsetDateTime makeDue() {
            OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
                    .withNano(0);
            jdbc.update("UPDATE pet_order SET payment_expire_at=?",
                    stamp(deadline));
            jdbc.update("UPDATE schedule_reservation SET lock_expire_at=?",
                    stamp(deadline));
            return deadline;
        }

        ExpireOrderCommand expiryCommand(String orderId, OffsetDateTime deadline) {
            String reservationId = jdbc.queryForObject(
                    "SELECT reservation_id FROM pet_order", Long.class).toString();
            return new ExpireOrderCommand(new CommandContext(
                    "TASK:RESERVATION_HOLD_EXPIRE:" + reservationId + ":0",
                    "test", OperatorType.SYSTEM, "0", "async_task"),
                    orderId, reservationId, 0, deadline);
        }

        OrderExpiryApiImpl expiryApi() {
            JdbcTemplate transactionalJdbc = new JdbcTemplate(source);
            ReservationExpiryApi expiry = new ReservationExpiryApi() {
                @Override public void expire(ExpireHoldCommand command) {
                    if (holdExpiryFails) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "forced SCH failure");
                    int updated = transactionalJdbc.update("""
                            UPDATE schedule_reservation SET status='EXPIRED',version=version+1,
                                   updated_at=UTC_TIMESTAMP(3)
                            WHERE id=? AND order_id=? AND store_id=? AND status='TEMP_LOCKED'
                              AND version=? AND lock_expire_at=? AND lock_expire_at<=?
                            """, Long.parseLong(command.reservationId()),
                            Long.parseLong(command.orderId()), Long.parseLong(command.storeId()),
                            command.expectedVersion(), stamp(command.expectedExpireAt()),
                            stamp(command.observedNow()));
                    if (updated != 1) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "SCH CAS failed");
                }
                @Override public void assertExpired(String orderId, String reservationId,
                        String storeId, QueryContext context) {
                    String status = transactionalJdbc.queryForObject("""
                            SELECT status FROM schedule_reservation
                            WHERE id=? AND order_id=? AND store_id=?
                            """, String.class, Long.parseLong(reservationId),
                            Long.parseLong(orderId), Long.parseLong(storeId));
                    if (!"EXPIRED".equals(status)) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "SCH replay is inconsistent");
                }
            };
            return new OrderExpiryApiImpl(source, ids::incrementAndGet,
                    new TestGuard(transactionalJdbc), expiry,
                    (orderId, storeId, context) -> {
                        if (paymentExposed) throw new ApiException(
                                CommonApiCodes.DEPENDENCY_UNAVAILABLE, "payment uncertain");
                    }, (orderId, storeId, context) -> {
                        if (couponExposed) throw new ApiException(
                                CommonApiCodes.DEPENDENCY_UNAVAILABLE, "coupon uncertain");
                    });
        }

        DispatchedEvent paymentEvent(String orderId) {
            String payload;
            try {
                payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                        "paymentOrderId", "7001", "orderId", orderId,
                        "channelTradeNo", "trade-7001", "paidAmount", new BigDecimal("128.00"),
                        "paidAt", PAID_AT.toString()));
            } catch (Exception invalid) { throw new IllegalStateException(invalid); }
            return new DispatchedEvent("9001", "PaymentSucceededEvent.v1", 1, PAID_AT,
                    "PAYMENT", 7001L, "test", payload);
        }

        OrderPaymentResultApiImpl paymentResultApi(String orderId) {
            JdbcTemplate transactionalJdbc = new JdbcTemplate(source);
            ScheduleCapacityGuardApi guard = new TestGuard(transactionalJdbc);
            ReservationConfirmApi confirm = new ReservationConfirmApi() {
                @Override public void confirm(ConfirmReservationCommand command) {
                    if (holdConfirmFails) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "forced SCH confirm failure");
                    int changed = transactionalJdbc.update("""
                            UPDATE schedule_reservation SET status='CONFIRMED',version=version+1,
                                   updated_at=UTC_TIMESTAMP(3)
                            WHERE id=? AND order_id=? AND store_id=? AND status='TEMP_LOCKED'
                              AND version=? AND lock_expire_at=?
                            """, Long.parseLong(command.reservationId()),
                            Long.parseLong(command.orderId()), Long.parseLong(command.storeId()),
                            command.expectedVersion(), stamp(command.expectedExpireAt()));
                    if (changed != 1) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "SCH confirm CAS failed");
                }
                @Override public void assertConfirmed(String orderId, String reservationId,
                        String storeId, QueryContext context) {
                    if (!"CONFIRMED".equals(transactionalJdbc.queryForObject("""
                            SELECT status FROM schedule_reservation WHERE id=? AND order_id=?
                            """, String.class, Long.parseLong(reservationId), Long.parseLong(orderId))))
                        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                                "SCH confirmation is inconsistent");
                }
            };
            ReservationExpiryApi expired = new ReservationExpiryApi() {
                @Override public void expire(ExpireHoldCommand command) {
                    throw new UnsupportedOperationException();
                }
                @Override public void assertExpired(String orderId, String reservationId,
                        String storeId, QueryContext context) {
                    if (!"EXPIRED".equals(transactionalJdbc.queryForObject("""
                            SELECT status FROM schedule_reservation WHERE id=? AND order_id=?
                            """, String.class, Long.parseLong(reservationId), Long.parseLong(orderId))))
                        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                                "SCH expiry is inconsistent");
                }
            };
            return new OrderPaymentResultApiImpl(source, ids::incrementAndGet, guard,
                    (paymentId, targetOrderId, storeId, context) -> new PaymentSuccessFact(
                            "7001", "7002", orderId, "300", "200", "100", "trade-7001",
                            new BigDecimal("128.00"), PAID_AT, "9001", "CNY"),
                    confirm, expired, new TransactionalOutboxPublisher(source,
                            ids::incrementAndGet, new com.fasterxml.jackson.databind.ObjectMapper()));
        }

        OrderCreationApiImpl api(DataSource transactionalSource) {
            JdbcTemplate transactionalJdbc = new JdbcTemplate(transactionalSource);
            ScheduleCapacityGuardApi guard = new TestGuard(transactionalJdbc);
            ReservationHoldApi hold = command -> {
                long reservationId = ids.incrementAndGet();
                OffsetDateTime begin = command.appointmentStart() == null ? command.pickupStart()
                        : command.appointmentStart();
                OffsetDateTime end = command.appointmentEnd() == null ? command.returnStart().plusHours(1)
                        : command.appointmentEnd();
                OffsetDateTime expires = OffsetDateTime.now(CLOCK).plusMinutes(10);
                transactionalJdbc.update("""
                        INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,service_id,
                          fulfillment_type,start_at,end_at,pickup_start_at,return_start_at,status,
                          lock_token,lock_expire_at,capacity_snapshot,qualified_staff_count_snapshot,
                          created_at,updated_at,user_id)
                        VALUES(?,?,?,?,?,?,?,?,?,?,'TEMP_LOCKED',?,?,1,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)
                        """, reservationId, Long.parseLong(command.orderId()), 200L, 300L, 400L,
                        command.fulfillmentType(), stamp(begin),
                        stamp(end), stamp(command.pickupStart()),
                        stamp(command.returnStart()), UUID.randomUUID().toString(),
                        stamp(expires), Long.parseLong(command.userId()));
                if (command.appointmentStart() != null) {
                    claim(transactionalJdbc, reservationId, "GENERAL", "901", begin, end);
                    return new HoldResult(Long.toString(reservationId), command.orderId(), begin, end,
                            expires, List.of(new HeldClaim(Long.toString(reservationId + 1000), "901",
                                    "GENERAL", begin, end)));
                }
                OffsetDateTime pickupEnd = begin.plusHours(1);
                OffsetDateTime returnStart = command.returnStart();
                claim(transactionalJdbc, reservationId, "PICKUP", "902", begin, pickupEnd);
                claim(transactionalJdbc, reservationId, "RETURN", "903", returnStart, end);
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
            return new OrderCreationApiImpl(transactionalSource, ids::incrementAndGet,
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

        private void claim(JdbcTemplate target, long reservationId, String kind, String windowId,
                OffsetDateTime start, OffsetDateTime end) {
            long claimId = reservationId + ("RETURN".equals(kind) ? 1001 : 1000);
            target.update("""
                    INSERT INTO schedule_reservation_claim
                      (id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, claimId, reservationId, Long.parseLong(windowId), 300L,
                    "GENERAL".equals(kind) ? 400L : 401L, kind,
                    stamp(start), stamp(end));
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
        Instant utcInstant(String sql) {
            return jdbc.queryForObject(sql, LocalDateTime.class).toInstant(ZoneOffset.UTC);
        }
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

    /** Commits on the real MySQL connection, then loses exactly one acknowledgement. */
    private static final class LostCommitAckSource extends DelegatingDataSource {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final int lostAt;
        private final boolean denyRecoveryReads;

        LostCommitAckSource(DataSource target, int lostAt) {
            this(target, lostAt, false);
        }

        LostCommitAckSource(DataSource target, int lostAt, boolean denyRecoveryReads) {
            super(target);
            this.lostAt = lostAt;
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
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if ("commit".equals(method.getName())
                                    && commits.incrementAndGet() == lostAt) {
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

    private static LocalDateTime stamp(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static DataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }
}
