package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ScheduleProtectionFoundationMySqlTest {
    private static final QueryContext CONTEXT =
            new QueryContext(UUID.randomUUID().toString(), OperatorType.SYSTEM, "schedule-test");

    @Test
    void firstRowCreationSerializesOneStoreButDoesNotBlockAnotherStore() throws Exception {
        try (Database db = new Database(); var executor = Executors.newFixedThreadPool(3)) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            CountDownLatch firstAcquired = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondAcquired = new CountDownLatch(1);
            var first = executor.submit(() -> db.transaction().execute(status -> {
                guard.acquire(List.of("101"), CONTEXT);
                firstAcquired.countDown();
                await(releaseFirst);
                return null;
            }));
            assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> db.transaction().execute(status -> {
                guard.acquire(List.of("101"), CONTEXT);
                secondAcquired.countDown();
                return null;
            }));
            var otherStore = executor.submit(() -> db.transaction().execute(status -> {
                guard.acquire(List.of("102"), CONTEXT);
                guard.requireHeld("102", db.source);
                return null;
            }));
            otherStore.get(5, TimeUnit.SECONDS);
            assertFalse(secondAcquired.await(250, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(secondAcquired.getCount() == 0);
            assertEquals(2, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_store_capacity_guard", Integer.class));
        }
    }

    @Test
    void guardRejectsUnsharedTransactionsAndRollbackReleasesFirstRow() throws Exception {
        try (Database db = new Database()) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class,
                            () -> guard.acquire(List.of("103"), CONTEXT)).code());
            assertThrows(UnexpectedRollbackException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of("103"), CONTEXT);
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> guard.requireHeld("103", db.otherSource)).code());
                return null;
            }));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_store_capacity_guard WHERE store_id=103", Integer.class));
            db.transaction().execute(status -> {
                guard.acquire(List.of("103"), CONTEXT);
                status.setRollbackOnly();
                return null;
            });
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_store_capacity_guard WHERE store_id=103", Integer.class));
            db.transaction().execute(status -> {
                guard.acquire(List.of("103"), CONTEXT);
                return null;
            });
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_store_capacity_guard WHERE store_id=103", Integer.class));
        }
    }

    @Test
    void innerRequiresNewCannotReuseOuterGuardAndReadOnlyOrRepeatableReadFails() throws Exception {
        try (Database db = new Database()) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            db.transaction().execute(status -> {
                guard.acquire(List.of("104"), CONTEXT);
                TransactionTemplate inner = db.transaction();
                inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThrows(UnexpectedRollbackException.class, () -> inner.execute(innerStatus -> {
                    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                            assertThrows(ApiException.class,
                                    () -> guard.requireHeld("104", db.source)).code());
                    return null;
                }));
                assertThrows(UnexpectedRollbackException.class, () -> inner.execute(innerStatus -> {
                    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                            assertThrows(ApiException.class,
                                    () -> guard.acquire(List.of("104"), CONTEXT)).code());
                    return null;
                }));
                guard.requireHeld("104", db.source);
                return null;
            });
            TransactionTemplate readOnly = db.transaction();
            readOnly.setReadOnly(true);
            assertThrows(UnexpectedRollbackException.class, () -> readOnly.execute(status -> {
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> guard.acquire(List.of("105"), CONTEXT)).code());
                return null;
            }));
            TransactionTemplate repeatable = db.transaction();
            repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThrows(UnexpectedRollbackException.class, () -> repeatable.execute(status -> {
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> guard.acquire(List.of("105"), CONTEXT)).code());
                return null;
            }));
        }
    }

    @Test
    void currentFactsRequireCompleteActiveClaimsAndUseStorePrefixIndexes() throws Exception {
        try (Database db = new Database()) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            ScheduleProtectionFactsApiImpl facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
            db.jdbc.update("INSERT INTO schedule_availability_window "
                    + "(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,"
                    + "status,version,created_at,updated_at,window_kind) VALUES "
                    + "(1,10,201,301,'2030-01-01 01:00:00','2030-01-01 02:00:00',2,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'PICKUP'),"
                    + "(2,10,201,301,'2030-01-01 03:00:00','2030-01-01 04:00:00',2,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'RETURN')");
            db.jdbc.update("INSERT INTO schedule_reservation "
                    + "(id,order_id,user_id,merchant_id,store_id,service_id,fulfillment_type,"
                    + "start_at,end_at,pickup_start_at,return_start_at,status,capacity_snapshot,"
                    + "qualified_staff_count_snapshot,version,created_at,updated_at) VALUES "
                    + "(401,501,601,10,201,301,'PICKUP_DELIVERY','2030-01-01 01:00:00',"
                    + "'2030-01-01 04:00:00','2030-01-01 01:00:00','2030-01-01 03:00:00',"
                    + "'TEMP_LOCKED',2,2,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            db.jdbc.update("INSERT INTO schedule_reservation_claim "
                    + "(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at) "
                    + "VALUES (701,401,1,201,301,'PICKUP','2030-01-01 01:00:00',"
                    + "'2030-01-01 02:00:00')");
            assertThrows(UnexpectedRollbackException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of("201"), CONTEXT);
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> facts.readStore("201", CONTEXT)).code());
                return null;
            }));
            db.jdbc.update("INSERT INTO schedule_reservation_claim "
                    + "(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at) "
                    + "VALUES (702,401,2,201,301,'RETURN','2030-01-01 03:00:00',"
                    + "'2030-01-01 04:00:00')");
            db.transaction().execute(status -> {
                guard.acquire(List.of("201"), CONTEXT);
                var snapshot = facts.readStore("201", CONTEXT);
                assertTrue(snapshot.complete());
                assertEquals(2, snapshot.claims().size());
                return null;
            });
            db.jdbc.update("UPDATE schedule_availability_window SET configured_capacity=0 WHERE id=1");
            db.transaction().execute(status -> {
                guard.acquire(List.of("201"), CONTEXT);
                assertEquals(0, facts.readStore("201", CONTEXT).windows().getFirst()
                        .configuredCapacity(), "configured zero is valid source data");
                return null;
            });
            assertEquals("idx_schedule_service_time", db.explain("SELECT id FROM "
                    + "schedule_availability_window FORCE INDEX(idx_schedule_service_time) "
                    + "WHERE store_id=201 ORDER BY id FOR UPDATE"));
            assertEquals("idx_reservation_service_time", db.explain("SELECT id FROM "
                    + "schedule_reservation FORCE INDEX(idx_reservation_service_time) "
                    + "WHERE store_id=201 ORDER BY id FOR UPDATE"));
            assertEquals("idx_claim_store_reservation", db.explain("SELECT id FROM "
                    + "schedule_reservation_claim FORCE INDEX(idx_claim_store_reservation) "
                    + "WHERE store_id=201 ORDER BY id FOR UPDATE"));
            db.jdbc.update("INSERT INTO schedule_availability_window "
                    + "(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,"
                    + "status,version,created_at,updated_at,window_kind) VALUES "
                    + "(3,10,201,301,'2030-01-01 01:30:00','2030-01-01 02:30:00',1,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'PICKUP')");
            assertThrows(UnexpectedRollbackException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of("201"), CONTEXT);
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> facts.readStore("201", CONTEXT)).code());
                return null;
            }));
        }
    }

    @Test
    void completedPastAssignmentDoesNotConsumeFutureStaffAndZeroCapacityIsKnownConflict()
            throws Exception {
        try (Database db = new Database()) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            ScheduleProtectionFactsApiImpl facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
            db.jdbc.update("INSERT INTO schedule_availability_window "
                    + "(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,"
                    + "status,version,created_at,updated_at,window_kind) VALUES "
                    + "(1,10,301,401,'2025-01-01 01:00:00','2025-01-01 02:00:00',1,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL'),"
                    + "(2,10,301,401,'2030-01-01 01:00:00','2030-01-01 02:00:00',1,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL')");
            db.jdbc.update("INSERT INTO schedule_reservation "
                    + "(id,order_id,user_id,merchant_id,store_id,service_id,fulfillment_type,"
                    + "start_at,end_at,status,capacity_snapshot,qualified_staff_count_snapshot,"
                    + "version,created_at,updated_at) VALUES "
                    + "(501,601,701,10,301,401,'IN_STORE','2025-01-01 01:00:00',"
                    + "'2025-01-01 02:00:00','CONFIRMED',1,1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            db.jdbc.update("INSERT INTO schedule_reservation_claim "
                    + "(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at) "
                    + "VALUES (801,501,1,301,401,'GENERAL','2025-01-01 01:00:00',"
                    + "'2025-01-01 02:00:00')");
            db.jdbc.update("INSERT INTO staff_service_capability "
                    + "(id,staff_id,service_id,status,created_at,updated_at) "
                    + "VALUES (901,1001,401,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            db.jdbc.update("INSERT INTO staff_availability_window "
                    + "(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at) "
                    + "VALUES (1002,301,1001,'2030-01-01 01:00:00','2030-01-01 02:00:00',"
                    + "'AVAILABLE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            MerchantCurrentStaffFactsApi merchant = (storeId, context) ->
                    new CurrentStoreStaffFacts("10", storeId, true,
                            List.of(new CurrentStaffFact("1001", "10", storeId,
                                    "ACTIVE", true, "0")));
            OrderProtectionFactsApi order = new OrderProtectionFactsApi() {
                @Override
                public OrderProtectionSnapshot readStore(String storeId, QueryContext context) {
                    return new OrderProtectionSnapshot(storeId, true, 1, 1,
                            List.of(new OrderProtectionFact("601", "501", "701", "10", storeId,
                                    "401", "IN_STORE", "1001", "COMPLETED", "VERIFIED",
                                    "0", "1101", "0", false)));
                }
                @Override
                public OrderProtectionSnapshot getByReservations(String storeId,
                        List<String> reservationIds, QueryContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override
                public OrderProtectionSnapshot getCurrentAssignments(String storeId,
                        List<String> affectedStaffIds, QueryContext context) {
                    throw new UnsupportedOperationException();
                }
            };
            ScheduleCapacityProofApiImpl proof = new ScheduleCapacityProofApiImpl(db.source,
                    guard, facts, merchant, order,
                    Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC), 1000);
            CapacityProofQuery query = new CapacityProofQuery("301", "401", "IN_STORE",
                    OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                    OffsetDateTime.parse("2030-01-01T02:00:00Z"), "2", null, null, CONTEXT);
            db.transaction().execute(status -> {
                guard.acquire(List.of("301"), CONTEXT);
                assertEquals(1, proof.checkNewReservation(query).evaluatedReservations(),
                        "past completed current assignment is not a future fixed resource");
                return null;
            });
            db.jdbc.update("UPDATE schedule_availability_window SET configured_capacity=0 WHERE id=2");
            assertThrows(UnexpectedRollbackException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of("301"), CONTEXT);
                assertEquals("SCHEDULE_CAPACITY_EXCEEDED",
                        assertThrows(ApiException.class,
                                () -> proof.checkNewReservation(query)).code());
                return null;
            }));
            db.jdbc.update("UPDATE schedule_availability_window "
                    + "SET configured_capacity=1,merchant_id=11 WHERE id=2");
            assertThrows(UnexpectedRollbackException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of("301"), CONTEXT);
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        assertThrows(ApiException.class,
                                () -> proof.checkNewReservation(query)).code());
                return null;
            }));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "sch003_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final DataSource otherSource;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            String prefix = System.getenv().containsKey("SCH003_MYSQL_URL") ? "SCH003"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "SCH003";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33457/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("MySQL test URL must target a local root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            otherSource = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("Schema06 is absent");
                for (String script : List.of("06-核心数据库Schema-v0.1.sql",
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql")) {
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

        private TransactionTemplate transaction() {
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            return tx;
        }

        private String explain(String sql) {
            return jdbc.queryForObject("EXPLAIN " + sql, (rs, n) -> rs.getString("key"));
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
                    } catch (SQLException failed) {
                        connection.close();
                        throw failed;
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
