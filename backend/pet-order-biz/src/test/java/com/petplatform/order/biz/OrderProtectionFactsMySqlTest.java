package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class OrderProtectionFactsMySqlTest {
    private static final long STORE = 7001L;
    private static final long OTHER_STORE = 7002L;
    private static final long MERCHANT = 7100L;
    private static final long SERVICE = 7200L;
    private static final long STAFF = 7300L;
    private static final QueryContext CONTEXT =
            new QueryContext("order-protection-test", OperatorType.SYSTEM, "test");
    private static final OffsetDateTime FUTURE_START =
            OffsetDateTime.parse("2030-01-01T09:00:00Z");
    private static final OffsetDateTime FUTURE_END =
            OffsetDateTime.parse("2030-01-01T10:00:00Z");

    @Test
    void readsWholeStoreBeforeEitherFilterAndKeepsCurrentAssignmentFixed() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            db.order(8002, 8102, STORE, null, "PENDING_PAYMENT", "UNVERIFIED");
            db.assignment(8201, 8001, STAFF, 1);
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.reservation(8102, 8002, STORE, "TEMP_LOCKED", 9002);
            facts.staff(STAFF, STORE);
            var api = db.api(facts);

            OrderProtectionSnapshot all = db.guarded(STORE,
                    () -> api.readStore(Long.toString(STORE), CONTEXT));
            assertTrue(all.complete());
            assertEquals(2, all.totalOrders());
            assertEquals(1, all.totalCurrentAssignments());
            assertEquals(List.of("8001", "8002"), all.items().stream().map(i -> i.orderId()).toList());
            assertTrue(all.items().get(0).protectRequired());
            assertFalse(all.items().get(1).protectRequired());
            assertEquals("0", all.items().get(0).assignmentVersion());

            OrderProtectionSnapshot byReservation = db.guarded(STORE,
                    () -> api.getByReservations("7001", List.of("8102"), CONTEXT));
            assertEquals(1, byReservation.items().size());
            assertEquals(2, byReservation.totalOrders());
            assertEquals("8002", byReservation.items().get(0).orderId());
            OrderProtectionSnapshot emptyTarget = db.guarded(STORE,
                    () -> api.getCurrentAssignments("7001", List.of("9999"), CONTEXT));
            assertTrue(emptyTarget.items().isEmpty());
            assertEquals(1, emptyTarget.totalCurrentAssignments());
            assertEquals("8001", db.guarded(STORE,
                    () -> api.getCurrentAssignments("7001", null, CONTEXT))
                    .items().get(0).orderId());
        }
    }

    @Test
    void missingAssignmentAndGlobalOrphanFailClosedEvenForEmptyTarget() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.staff(STAFF, STORE);
            var api = db.api(facts);
            assertUnavailable(() -> db.guarded(STORE,
                    () -> api.getCurrentAssignments("7001", List.of(), CONTEXT)));

            db.assignment(8201, 8001, STAFF, 1);
            assertEquals(1, db.guarded(STORE,
                    () -> api.readStore("7001", CONTEXT)).totalCurrentAssignments());
            db.assignment(8202, 8999, STAFF, 1);
            assertUnavailable(() -> db.guarded(STORE,
                    () -> api.getByReservations("7001", List.of("8101"), CONTEXT)));
            db.jdbc.update("DELETE FROM order_staff_assignment WHERE id=8202");
            db.jdbc.update("UPDATE pet_order SET service_staff_id=NULL WHERE id=8001");
            assertUnavailable(() -> db.guarded(STORE,
                    () -> api.readStore("7001", CONTEXT)));
        }
    }

    @Test
    void malformedCurrentAndHistoricalAssignmentIdentifiersFailClosed() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.staff(STAFF, STORE);
            var api = db.api(facts);
            db.assignment(0, 8001, STAFF, 1);
            assertUnavailable(() -> db.guarded(STORE, () -> api.readStore("7001", CONTEXT)));
            db.jdbc.update("DELETE FROM order_staff_assignment WHERE id=0");
            db.assignment(8201, 8001, STAFF, 1);
            db.assignment(8202, 8001, 0, 0);
            assertUnavailable(() -> db.guarded(STORE, () -> api.readStore("7001", CONTEXT)));
        }
    }

    @Test
    void validatesBidirectionalBindingUserAndLifecycleWithoutHistoricalEligibilityRecheck()
            throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            db.assignment(8201, 8001, STAFF, 1);
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.staff(STAFF, STORE);
            var api = db.api(facts);

            facts.reservations.set(0, reservation(8101, 8001, STORE, "CONFIRMED", "9999"));
            assertUnavailable(() -> db.guarded(STORE, () -> api.readStore("7001", CONTEXT)));
            facts.reservations.set(0, reservation(8101, 8001, STORE, "RELEASED", "7400"));
            assertUnavailable(() -> db.guarded(STORE, () -> api.readStore("7001", CONTEXT)));

            db.jdbc.update("UPDATE pet_order SET order_stage='CANCELED' WHERE id=8001");
            facts.staff.clear(); // Historical current assignment does not require current ACTIVE eligibility.
            assertFalse(db.guarded(STORE, () -> api.readStore("7001", CONTEXT))
                    .items().get(0).protectRequired());

            db.jdbc.update("UPDATE pet_order SET order_stage='COMPLETED', verification_status='VERIFIED' WHERE id=8001");
            facts.reservations.set(0, reservation(8101, 8001, STORE, "CONFIRMED", "7400"));
            assertUnavailable(() -> db.guarded(STORE, () -> api.readStore("7001", CONTEXT)));
            facts.claims.set(0, new ClaimFact("9001", "8101", "9011", "7001", "7200",
                    "GENERAL", OffsetDateTime.parse("2020-01-01T09:00:00Z"),
                    OffsetDateTime.parse("2020-01-01T10:00:00Z")));
            assertFalse(db.guarded(STORE, () -> api.readStore("7001", CONTEXT))
                    .items().get(0).protectRequired());
        }
    }

    @Test
    void caughtFailureMarksCallerTransactionRollbackOnly() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.staff(STAFF, STORE);
            var api = db.api(facts);
            try {
                db.tx.execute(status -> {
                    db.guard.acquire(List.of("7001"), CONTEXT);
                    db.jdbc.update("UPDATE pet_order SET version=7 WHERE id=8001");
                    assertUnavailable(() -> api.readStore("7001", CONTEXT));
                    return null;
                });
            } catch (RuntimeException expectedRollback) {
                // Spring may report UnexpectedRollbackException to the caller.
            }
            assertEquals(0L, db.jdbc.queryForObject("SELECT version FROM pet_order WHERE id=8001", Long.class));
        }
    }

    @Test
    void separateStoresCanReadWhileOtherStoreAssignmentIsLocked() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            db.assignment(8201, 8001, STAFF, 1);
            db.order(8002, 8102, OTHER_STORE, STAFF + 1, "PENDING_SERVICE", "UNVERIFIED");
            db.assignment(8202, 8002, STAFF + 1, 1);
            Facts facts = new Facts();
            facts.reservation(8101, 8001, STORE, "CONFIRMED", 9001);
            facts.reservation(8102, 8002, OTHER_STORE, "CONFIRMED", 9002);
            facts.staff(STAFF, STORE);
            facts.staff(STAFF + 1, OTHER_STORE);
            var api = db.api(facts);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var blocker = executor.submit(() -> db.tx.execute(status -> {
                    db.guard.acquire(List.of("7001"), CONTEXT);
                    db.jdbc.queryForObject("SELECT id FROM pet_order WHERE id=8001 FOR UPDATE", Long.class);
                    db.jdbc.queryForObject("SELECT id FROM order_staff_assignment WHERE id=8201 FOR UPDATE", Long.class);
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interruption);
                    }
                    return null;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var other = executor.submit(() -> db.guarded(OTHER_STORE,
                        () -> api.readStore("7002", CONTEXT)));
                assertEquals("8002", other.get(2, TimeUnit.SECONDS).items().get(0).orderId());
                release.countDown();
                blocker.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void storeReadsAndGlobalIntegrityProbeUseTheirIntendedIndexes() throws Exception {
        try (Database db = new Database()) {
            db.order(8001, 8101, STORE, STAFF, "PENDING_SERVICE", "UNVERIFIED");
            db.assignment(8201, 8001, STAFF, 1);
            var orders = db.jdbc.queryForList("""
                    EXPLAIN SELECT id FROM pet_order FORCE INDEX (idx_order_store_stage_created)
                    WHERE store_id=? ORDER BY id FOR UPDATE
                    """, STORE);
            assertEquals("idx_order_store_stage_created", orders.get(0).get("key"));
            var assignments = db.jdbc.queryForList("""
                    EXPLAIN SELECT a.id FROM pet_order o FORCE INDEX (idx_order_store_stage_created)
                    STRAIGHT_JOIN order_staff_assignment a FORCE INDEX (idx_assignment_order_state)
                        ON a.order_id=o.id
                    WHERE o.store_id=? ORDER BY a.order_id,a.id FOR UPDATE
                    """, STORE);
            assertEquals("idx_order_store_stage_created", assignments.get(0).get("key"));
            assertEquals("idx_assignment_order_state", assignments.get(1).get("key"));
            var orphanProbe = db.jdbc.queryForList("""
                    EXPLAIN SELECT a.id FROM order_staff_assignment a
                    FORCE INDEX (idx_assignment_current_order)
                    LEFT JOIN pet_order o ON o.id=a.order_id
                    WHERE a.is_current=1 AND o.id IS NULL LIMIT 1
                    """);
            assertEquals("idx_assignment_current_order", orphanProbe.get(0).get("key"));
        }
    }

    private static ReservationFact reservation(long id, long orderId, long storeId,
            String status, String userId) {
        return new ReservationFact(Long.toString(id), Long.toString(orderId), userId,
                Long.toString(MERCHANT), Long.toString(storeId), Long.toString(SERVICE),
                "IN_STORE", FUTURE_START, FUTURE_END, null, null, status, "0");
    }

    private static void assertUnavailable(Runnable action) {
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                assertThrows(ApiException.class, action::run).code());
    }

    private static final class Facts {
        final List<ReservationFact> reservations = new ArrayList<>();
        final List<ClaimFact> claims = new ArrayList<>();
        final List<CurrentStaffFact> staff = new ArrayList<>();

        void reservation(long id, long orderId, long storeId, String status, long claimId) {
            reservations.add(OrderProtectionFactsMySqlTest.reservation(
                    id, orderId, storeId, status, "7400"));
            claims.add(new ClaimFact(Long.toString(claimId), Long.toString(id), "9011",
                    Long.toString(storeId), Long.toString(SERVICE), "GENERAL", FUTURE_START, FUTURE_END));
        }

        void staff(long staffId, long storeId) {
            staff.add(new CurrentStaffFact(Long.toString(staffId), Long.toString(MERCHANT),
                    Long.toString(storeId), "ACTIVE", true, "0"));
        }

        StoreScheduleFacts readSchedule(String storeId, QueryContext context) {
            return new StoreScheduleFacts(storeId, true, List.of(), reservations.stream()
                    .filter(row -> storeId.equals(row.storeId())).toList(), claims.stream()
                    .filter(row -> storeId.equals(row.storeId())).toList());
        }

        CurrentStoreStaffFacts readMerchant(String storeId, QueryContext context) {
            return new CurrentStoreStaffFacts(Long.toString(MERCHANT), storeId, true,
                    staff.stream().filter(row -> storeId.equals(row.storeId())).toList());
        }
    }

    private static final class Database implements AutoCloseable {
        final String name = "ordprot_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final Guard guard;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ORDER_PROTECTION_MYSQL_URL")
                    ? "ORDER_PROTECTION" : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ORDER_PROTECTION";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33457/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ORDER_PROTECTION_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            guard = new Guard(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "37-Reservation-Protection-Foundation-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        OrderProtectionFactsApiImpl api(Facts facts) {
            return new OrderProtectionFactsApiImpl(source, guard, facts::readSchedule,
                    facts::readMerchant,
                    Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC));
        }

        <T> T guarded(long storeId, java.util.function.Supplier<T> work) {
            return tx.execute(status -> {
                guard.acquire(List.of(Long.toString(storeId)), CONTEXT);
                return work.get();
            });
        }

        void order(long id, long reservation, long store, Long staff, String stage, String verification) {
            jdbc.update("""
                    INSERT INTO pet_order
                    (id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,
                     service_staff_id,order_stage,verification_status,fulfillment_type,
                     original_amount,pay_amount,appointment_start_at,appointment_end_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,100.00,100.00,'2030-01-01 09:00:00',
                           '2030-01-01 10:00:00',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                    """, id, id + 10000, 7400L, MERCHANT, store, SERVICE, 7500L, reservation,
                    staff, stage, verification, "IN_STORE");
        }

        void assignment(long id, long order, long staff, int current) {
            jdbc.update("""
                    INSERT INTO order_staff_assignment
                    (id,order_id,staff_id,assigned_by_type,is_current,assigned_at,version)
                    VALUES(?,?,?,'MERCHANT',?,UTC_TIMESTAMP(3),0)
                    """, id, order, staff, current);
        }

        private static DataSource dataSource(String url, String user, String password) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password) {
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

        @Override public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }

    /** Test seam checks a real transaction/connection; B owns production guard and lock proof. */
    private static final class Guard implements ScheduleCapacityGuardApi {
        private final DataSource source;
        private final ThreadLocal<List<String>> acquired = new ThreadLocal<>();

        Guard(DataSource source) { this.source = source; }

        @Override public void acquire(List<String> storeIds, QueryContext context) {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                    !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getConnection(source), source)) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "test transaction absent");
            }
            acquired.set(List.copyOf(storeIds));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { acquired.remove(); }
            });
        }

        @Override public void requireHeld(String storeId, DataSource callerSource) {
            if (callerSource != source || !TransactionSynchronizationManager.isActualTransactionActive() ||
                    !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder) ||
                    acquired.get() == null || !acquired.get().contains(storeId)) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "test guard absent");
            }
        }
    }
}
