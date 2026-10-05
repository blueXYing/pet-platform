package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderExpiryFactsApi;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
import com.petplatform.schedule.api.error.ScheduleWriteApiCodes;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandService;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * Contract53 §7 blocker 1, ruling 2026-10-05 (plan A): the explicit system-derived SOLD_OUT
 * window state. Covers entry when claims fill a window, the release linkage (refund release
 * and hold expiry) back to bookable, the no-double-sell behaviour over two concurrent guarded
 * connections, the respect of SOLD_OUT by the booking capacity guard, the merchant
 * close/open/update semantics around the derived state and the 200-entry batch-close ceiling.
 */
class ScheduleSoldOutLinkMySqlTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:34:56.789Z");
    private static final OffsetDateTime S = OffsetDateTime.parse("2030-01-01T01:00:00Z");
    private static final OffsetDateTime E = OffsetDateTime.parse("2030-01-01T02:00:00Z");
    private static final OffsetDateTime S130 = OffsetDateTime.parse("2030-01-01T01:30:00Z");
    private static final OffsetDateTime E230 = OffsetDateTime.parse("2030-01-01T02:30:00Z");
    private static final String STORE = "201";

    @Test
    void filledWindowIsSoldOutAndRefundReleaseRestoresIt() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            db.seedGeneral(2);
            HoldResult first = app.hold("501");
            HoldResult second = app.hold("502");
            // Both original claims fill capacity 2: the window derives SOLD_OUT in the same
            // transaction as the holds (system version bump, no merchant action involved).
            assertEquals("SOLD_OUT", db.windowStatus(101));
            assertEquals("1", db.windowVersion(101));

            // The booking guard respects the derived state: a net-new candidate inside the same
            // window is refused by the capacity solver (3 active claims on capacity 2).
            assertEquals("SCHEDULE_CAPACITY_EXCEEDED", assertThrows(ApiException.class,
                    () -> app.checkBooking(S,
                            OffsetDateTime.parse("2030-01-01T01:30:00Z"))).code());

            // A sold-out window is fully occupied, so the merchant still cannot close it.
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.closeWindow(
                            new WindowCloseCommand(ctx(), "101", "10", STORE, 1L, "x"))).code());

            // Confirmation is occupancy-neutral: the window stays sold out.
            app.confirm(first);
            app.confirm(second);
            assertEquals("SOLD_OUT", db.windowStatus(101));

            // Refund release frees one confirmed occupancy: the window returns to bookable
            // inside the same transaction and a new hold can take the freed slot again.
            app.release(first, "701");
            assertEquals("OPEN", db.windowStatus(101));
            assertEquals("2", db.windowVersion(101));
            app.hold("503");
            assertEquals("SOLD_OUT", db.windowStatus(101));
            assertEquals("3", db.windowVersion(101));
        }
    }

    @Test
    void concurrentHoldsCannotOversellTheLastSlot() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            db.seedGeneral(1);
            CountDownLatch ready = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<HoldResult>> races = new ArrayList<>();
                for (String order : List.of("601", "602")) {
                    races.add(pool.submit(() -> app.raceHold(order, ready)));
                }
                ready.await(10, TimeUnit.SECONDS);
                long succeeded = 0;
                for (Future<HoldResult> race : races) {
                    try {
                        race.get(30, TimeUnit.SECONDS);
                        succeeded++;
                    } catch (ExecutionException oversold) {
                        // The loser is refused by the capacity proof, never oversold.
                        assertTrue(oversold.getCause() instanceof ApiException);
                    }
                }
                assertEquals(1, succeeded);
                assertEquals(1, db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM schedule_reservation WHERE status='TEMP_LOCKED'",
                        Integer.class));
                assertEquals("SOLD_OUT", db.windowStatus(101));
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void holdExpiryReturnsTheWindowAndMerchantKeepsOrdinaryPowers() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            db.seedGeneral(1);
            HoldResult held = app.hold("501");
            assertEquals("SOLD_OUT", db.windowStatus(101));

            // While occupied the merchant cannot move the window, but a capacity increase is
            // exactly the SCHW-D4 allowed edit and it re-derives the window as bookable in the
            // same transaction: update bumps 1→2, the derived flip back to OPEN bumps 2→3.
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.updateWindow(
                            new UpdateWindowCommand(ctx(), "101", "10", STORE, S130, E230,
                                    null, 1L, null))).code());
            WindowResult raised = app.commands.updateWindow(new UpdateWindowCommand(
                    ctx(), "101", "10", STORE, S, E, 2, 1L, null));
            assertEquals("OPEN", raised.status());
            assertEquals("3", raised.version());

            // The 10-minute hold expires: the same transaction re-derives the window and the
            // merchant keeps the ordinary close/reopen powers over the freed window.
            app.expire(held);
            assertEquals("OPEN", db.windowStatus(101));
            WindowResult closed = app.commands.closeWindow(
                    new WindowCloseCommand(ctx(), "101", "10", STORE, 3L, "停业检修"));
            assertEquals("CLOSED", closed.status());
            WindowResult reopened = app.commands.openWindow(
                    new WindowOpenCommand(ctx(), "101", "10", STORE, 4L));
            assertEquals("OPEN", reopened.status());
            assertEquals("5", reopened.version());
        }
    }

    @Test
    void batchCloseRejectsMoreThan200EntriesWhole() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            db.seedGeneral(2);
            // 200 seeded siblings plus window 101 make 201 intersecting entries.
            db.seedWindows(1001, 200);
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> app.commands.batchClose(batch(LocalDate.parse("2030-01-01"),
                            LocalDate.parse("2030-01-09")))).code());
            assertEquals(201, db.openCount());
            assertEquals(0, db.batchCloseAudits());

            // Removing one sibling brings the command back to exactly 200 entries: it passes
            // and closes every open target of the range.
            db.jdbc.update("DELETE FROM schedule_availability_window WHERE id=1200");
            BatchCloseResult result = app.commands.batchClose(
                    batch(LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-09")));
            assertEquals(200, result.closedWindows().size());
            assertEquals(0, result.blockedWindows().size());
            assertEquals(0, db.openCount());
            assertEquals(200, db.batchCloseAudits());
        }
    }

    @Test
    void batchCloseReportsSoldOutTargetsAsBlockedInsteadOfSkippingThem() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            db.seedGeneral(1);
            app.hold("501");
            assertEquals("SOLD_OUT", db.windowStatus(101));
            db.jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "service_id,start_at,end_at,configured_capacity,status,version,"
                    + "created_at,updated_at,window_kind) VALUES(105,10,201,301,"
                    + "'2030-01-01 03:00:00','2030-01-01 04:00:00',1,'OPEN',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL')");
            BatchCloseResult result = app.commands.batchClose(
                    batch(LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01")));
            assertEquals(List.of("105"), result.closedWindows().stream()
                    .map(WindowResult::windowId).toList());
            // The sold-out window is occupied, so it is explicitly reported, never silently
            // skipped (SCHW-D5: 未关闭时段必须明示).
            assertEquals(1, result.blockedWindows().size());
            assertEquals("101", result.blockedWindows().getFirst().window().windowId());
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    result.blockedWindows().getFirst().reasonCode());
            assertEquals("SOLD_OUT", db.windowStatus(101));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static CommandContext ctx() {
        return new CommandContext(UUID.randomUUID().toString(), "sch004-test",
                OperatorType.USER, "601", "test");
    }

    private static CommandContext systemContext(String requestId) {
        return new CommandContext(requestId, "sch004-test", OperatorType.SYSTEM, null, "OUTBOX");
    }

    private static BatchCloseCommand batch(LocalDate from, LocalDate to) {
        return new BatchCloseCommand(ctx(), "10", STORE, from, to, "临时停业一天");
    }

    private static SnowflakeIdGenerator seq(long start) {
        AtomicLong sequence = new AtomicLong(start);
        return sequence::incrementAndGet;
    }

    /** One assembled application: merchant commands plus the reservation lifecycle APIs. */
    private static final class App {
        private final Database db;
        private final ScheduleCapacityGuardApiImpl guard;
        private final ScheduleProtectionFactsApi facts;
        private final ScheduleCapacityProofApiImpl proof;
        private final ScheduleMerchantCommandApiImpl commands;
        private final ReservationHoldApiImpl holds;
        private final ReservationConfirmApiImpl confirms;
        private final ReservationExpiryApiImpl expiry;
        private final ReservationRefundReleaseApiImpl releases;
        private final FakePayments payments = new FakePayments();
        private final TransactionTemplate tx;

        private App(Database db) {
            this.db = db;
            guard = new ScheduleCapacityGuardApiImpl(db.source);
            facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
            FakeMerchant merchant = new FakeMerchant();
            FakeOrders orders = new FakeOrders(db);
            MerchantAdmissionQueryApi admission = new MerchantAdmissionQueryApi() {
                @Override
                public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                    return new MerchantAdmissionDTO(query.merchantId(), query.storeId(), "OWNER",
                            "ALLOWED", OffsetDateTime.parse("2026-09-27T12:00:00Z"), "1", null,
                            null, "ACTIVE", "ACTIVE", null, List.of(), List.of(), List.of());
                }
            };
            ServiceQueryApi services = new ServiceQueryApi() {
                @Override
                public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
                    return new ServiceSnapshotDTO(query.serviceId(), "10", STORE, "洗澡", "cat",
                            "洗护", null, 30, FulfillmentType.IN_STORE, null, null, null, null);
                }

                @Override
                public com.petplatform.service.api.dto.ServiceSnapshotPageDTO
                        getStoreServiceSnapshots(StoreServiceSnapshotQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public com.petplatform.service.api.dto.ServiceBookabilityDTO checkBookable(
                        com.petplatform.service.api.query.ServiceBookabilityQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
                    throw new UnsupportedOperationException();
                }
            };
            proof = new ScheduleCapacityProofApiImpl(db.source, guard, facts, merchant, orders,
                    Clock.fixed(NOW, ZoneOffset.UTC), 1000);
            ScheduleWriteStore store = new ScheduleWriteStore(db.source, seq(5000));
            commands = new ScheduleMerchantCommandApiImpl(new ScheduleMerchantCommandService(
                    store, new ScheduleAdmissionGate(admission), guard, facts, merchant,
                    orders, services, proof, Clock.fixed(NOW, ZoneOffset.UTC)));
            holds = new ReservationHoldApiImpl(db.source, seq(2000), guard, facts, proof,
                    orders, Clock.fixed(NOW, ZoneOffset.UTC));
            confirms = new ReservationConfirmApiImpl(db.source, seq(8000), guard, facts,
                    payments);
            expiry = new ReservationExpiryApiImpl(db.source, seq(9000), guard, new FakeExpiry(),
                    facts);
            releases = new ReservationRefundReleaseApiImpl(db.source, seq(9500), guard,
                    new FakeRefunds());
            tx = new TransactionTemplate(new DataSourceTransactionManager(db.source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(30);
        }

        /** One guarded booking hold on window 101's interval; commits with its order row. */
        private HoldResult hold(String orderId) {
            HoldResult held = tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                HoldResult result = holds.hold(new HoldCommand(ctx(), orderId, "601", "10",
                        STORE, "301", "IN_STORE", S,
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"), null, null, "101",
                        null, null));
                insertOrder(result);
                return result;
            });
            if (held == null) throw new IllegalStateException("hold failed");
            return held;
        }

        /** Two guarded connections race for the last slot of one window. */
        private HoldResult raceHold(String orderId, CountDownLatch ready) {
            return tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                ready.countDown();
                HoldResult result = holds.hold(new HoldCommand(ctx(), orderId, "601", "10",
                        STORE, "301", "IN_STORE", S,
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"), null, null, "101",
                        null, null));
                insertOrder(result);
                return result;
            });
        }

        /** Re-proof through the public booking guard: a fresh candidate must be refused. */
        private void checkBooking(OffsetDateTime start, OffsetDateTime end) {
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                proof.checkNewReservation(new CapacityProofQuery(STORE, "301", "IN_STORE",
                        start, end, null, null, null, query()));
                return null;
            });
        }

        private void confirm(HoldResult held) {
            payments.proven.add(held.reservationId());
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                confirms.confirm(new ConfirmReservationCommand(
                        systemContext("EVENT:PAYMENT_SUCCEEDED:" + held.orderId() + ":"
                                + held.reservationId()),
                        held.orderId(), held.reservationId(), STORE, 0, held.holdExpireAt()));
                return null;
            });
        }

        private void release(HoldResult held, String refundOrderId) {
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                releases.release(held.orderId(), held.reservationId(), STORE, refundOrderId,
                        new QueryContext("sch004-test", OperatorType.SYSTEM, null));
                return null;
            });
        }

        private void expire(HoldResult held) {
            OffsetDateTime observedNow = OffsetDateTime.now(ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                expiry.expire(new ExpireHoldCommand(
                        systemContext("TASK:RESERVATION_HOLD_EXPIRE:" + held.reservationId()
                                + ":0"),
                        held.orderId(), held.reservationId(), STORE, 0, held.holdExpireAt(),
                        observedNow));
                return null;
            });
        }

        private void insertOrder(HoldResult held) {
            db.jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,"
                    + "store_id,service_id,pet_id,reservation_id,order_stage,payment_status,"
                    + "verification_status,fulfillment_type,original_amount,discount_amount,"
                    + "pay_amount,refunded_amount,appointment_start_at,appointment_end_at,"
                    + "created_at,updated_at) VALUES(?,?,601,10,201,301,701,?,"
                    + "'PENDING_PAYMENT','INIT','UNVERIFIED','IN_STORE',100.00,0.00,100.00,"
                    + "0.00,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    Long.parseLong(held.orderId()), Long.parseLong(held.orderId()),
                    Long.parseLong(held.reservationId()),
                    java.sql.Timestamp.from(held.startAt().toInstant()),
                    java.sql.Timestamp.from(held.endAt().toInstant()));
        }

        private static QueryContext query() {
            return new QueryContext("sch004-test", OperatorType.USER, "601");
        }
    }

    private static final class FakePayments implements OrderPaymentFactsApi {
        private final List<String> proven = new ArrayList<>();

        @Override
        public void requirePayableForPreparation(String orderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String locateStore(String orderId, QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderPaymentFact readForPayment(String orderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void assertPaymentCommitted(String orderId, String reservationId, String storeId,
                QueryContext context) {
            if (!proven.contains(reservationId)) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        "ORDER payment proof absent");
            }
        }
    }

    private static final class FakeExpiry implements OrderExpiryFactsApi {
        @Override
        public void assertExpiryCommitted(String orderId, String reservationId, String storeId,
                QueryContext context) {
            // The ORDER side of the expiry proof is not this test's subject.
        }
    }

    private static final class FakeRefunds implements RefundExecutionFactsApi {
        @Override
        public RefundExecutionFact requireForChannel(String refundOrderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundSuccessFact requireSucceeded(String refundOrderId, String orderId,
                String storeId, QueryContext context) {
            return new RefundSuccessFact(refundOrderId, "R" + refundOrderId, orderId, "9",
                    storeId, new BigDecimal("100.00"), new BigDecimal("100.00"), "CH1",
                    OffsetDateTime.parse("2026-09-27T13:00:00Z"), "E1", "MERCHANT_APPROVED",
                    "FULL");
        }
    }

    private static final class FakeMerchant implements MerchantCurrentStaffFactsApi {
        @Override
        public CurrentStoreStaffFacts readStore(String storeId, QueryContext context) {
            return new CurrentStoreStaffFacts("10", storeId, true, List.of(
                    new CurrentStaffFact("401", "10", storeId, "ACTIVE", true, "0"),
                    new CurrentStaffFact("402", "10", storeId, "ACTIVE", true, "0")));
        }
    }

    private static final class FakeOrders implements OrderProtectionFactsApi {
        private final Database db;

        private FakeOrders(Database db) {
            this.db = db;
        }

        @Override
        public OrderProtectionSnapshot readStore(String store, QueryContext query) {
            return snapshot(store, null);
        }

        @Override
        public OrderProtectionSnapshot getByReservations(String store,
                List<String> reservationIds, QueryContext query) {
            List<OrderProtectionFact> found = new ArrayList<>();
            for (String reservationId : reservationIds) {
                found.addAll(rows(store, reservationId));
            }
            return new OrderProtectionSnapshot(store, true, found.size(),
                    (int) found.stream().filter(fact -> fact.currentStaffId() != null).count(),
                    found);
        }

        @Override
        public OrderProtectionSnapshot getCurrentAssignments(String store,
                List<String> staffIds, QueryContext query) {
            return snapshot(store, null);
        }

        private OrderProtectionSnapshot snapshot(String store, String reservationId) {
            List<OrderProtectionFact> found = rows(store, reservationId);
            return new OrderProtectionSnapshot(store, true, found.size(),
                    (int) found.stream().filter(fact -> fact.currentStaffId() != null).count(),
                    found);
        }

        private List<OrderProtectionFact> rows(String store, String reservationId) {
            String sql = "SELECT id,reservation_id,user_id,merchant_id,store_id,service_id,"
                    + "fulfillment_type FROM pet_order WHERE store_id=?"
                    + (reservationId == null ? "" : " AND reservation_id=?") + " FOR UPDATE";
            Object[] args = reservationId == null
                    ? new Object[]{Long.parseLong(store)}
                    : new Object[]{Long.parseLong(store), Long.parseLong(reservationId)};
            return db.jdbc.query(sql, (rs, n) -> new OrderProtectionFact(rs.getString("id"),
                    rs.getString("reservation_id"), rs.getString("user_id"),
                    rs.getString("merchant_id"), rs.getString("store_id"),
                    rs.getString("service_id"), rs.getString("fulfillment_type"),
                    null, "PENDING_PAYMENT", "UNVERIFIED", "0", null, null, false), args);
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "soldout_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            // Same local-MySQL harness contract as the sibling schedule tests: the
            // <prefix>_MYSQL_URL environment wins, defaults target a local root only.
            String prefix = System.getenv().containsKey("SCH004_MYSQL_URL") ? "SCH004"
                    : System.getenv().containsKey("SCH003_MYSQL_URL") ? "SCH003"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "SCH004";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        prefix + "_MYSQL_URL must target a local root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("Schema06 is absent");
                for (String script : List.of("06-核心数据库Schema-v0.1.sql",
                        "13-Async-Infra-Schema-v0.1.sql",
                        "14-Command-Idempotency-Schema-v0.1.sql",
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                        "38-Booking-Create-Schema-v0.1.sql",
                        "39-Booking-Expiry-Schema-v0.1.sql",
                        "53-Schedule-Write-Schema-v0.1.sql")) {
                    try (Connection connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + script)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failed) {
                close();
                throw failed;
            }
        }

        /** Window 101 (capacity as given) plus two qualified scheduled employees 401/402:
         * overlapping claims each need a distinct qualified staff member (SCH solver). */
        private void seedGeneral(int capacity) {
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "service_id,start_at,end_at,configured_capacity,status,version,"
                    + "created_at,updated_at,window_kind) VALUES(101,10,201,301,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00'," + capacity
                    + ",'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL')");
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(402,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(412,402,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(404,201,401,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(414,201,402,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }

        /** `count` non-overlapping hourly open GENERAL windows intersecting the batch range
         * 2030-01-01..2030-01-09 (one service+kind window pair must never overlap). */
        private void seedWindows(long first, int count) {
            java.time.format.DateTimeFormatter stamp =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            java.time.LocalDateTime start = java.time.LocalDateTime.parse("2030-01-01 02:00:00",
                    stamp);
            for (long index = 0; index < count; index++) {
                long id = first + index;
                String from = start.plusHours(index).format(stamp);
                String to = start.plusHours(index + 1).format(stamp);
                jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                        + "service_id,start_at,end_at,configured_capacity,status,version,"
                        + "created_at,updated_at,window_kind) VALUES(" + id + ",10,201,301,'"
                        + from + "','" + to + "',1,'OPEN',0,UTC_TIMESTAMP(3),"
                        + "UTC_TIMESTAMP(3),'GENERAL')");
            }
        }

        private String windowStatus(long windowId) {
            return jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window WHERE id=?", String.class,
                    windowId);
        }

        private String windowVersion(long windowId) {
            return jdbc.queryForObject(
                    "SELECT version FROM schedule_availability_window WHERE id=?", String.class,
                    windowId);
        }

        private int openCount() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM schedule_availability_window "
                    + "WHERE status='OPEN'", Integer.class);
        }

        private int batchCloseAudits() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action "
                    + "WHERE action='WINDOW_BATCH_CLOSE'", Integer.class);
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(url + "?allowPublicKeyRetrieval=true&useSSL=false"
                    + "&connectionTimeZone=UTC", user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failed) {
                        connection.close();
                        throw failed;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }
}
