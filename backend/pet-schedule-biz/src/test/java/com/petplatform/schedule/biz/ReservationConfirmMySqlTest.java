package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Real SCH rows; ORDER's same-transaction proof is a controlled test port. */
class ReservationConfirmMySqlTest {
    private static final String ORDER = "501", HOLD = "401", STORE = "201";
    private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2030-01-01T00:10:00Z");
    private static final CommandContext COMMAND_CONTEXT = new CommandContext(
            "EVENT:PAYMENT_SUCCEEDED:501:401", "confirm-test", OperatorType.SYSTEM,
            null, "OUTBOX");
    private static final QueryContext QUERY_CONTEXT = new QueryContext(
            "confirm-test", OperatorType.SYSTEM, null);

    @Test
    void confirmationRequiresPaidOrderCommitProofAndPreservesClaimHistory() throws Exception {
        try (Database db = new Database()) {
            ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(db.source);
            AtomicBoolean paidProof = new AtomicBoolean();
            OrderPaymentFactsApi orders = new OrderPaymentFactsApi() {
                @Override public String locateStore(String orderId, QueryContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override public OrderPaymentFact readForPayment(String orderId, String storeId,
                        QueryContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override public void assertPaymentCommitted(String orderId, String reservationId,
                        String storeId, QueryContext context) {
                    if (!paidProof.get() || !ORDER.equals(orderId) || !HOLD.equals(reservationId)
                            || !STORE.equals(storeId)) throw new ApiException(
                            CommonApiCodes.DEPENDENCY_UNAVAILABLE, "ORDER payment proof absent");
                }
            };
            AtomicLong ids = new AtomicLong(8_000_000);
            var api = new ReservationConfirmApiImpl(db.source, ids::incrementAndGet, guard,
                    new ScheduleProtectionFactsApiImpl(db.source, guard), orders);
            var command = new ConfirmReservationCommand(COMMAND_CONTEXT, ORDER, HOLD,
                    STORE, 0, DEADLINE);

            assertThrows(ApiException.class, () -> api.confirm(command));
            assertThrows(RuntimeException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of(STORE), QUERY_CONTEXT);
                api.confirm(command);
                return null;
            }));
            assertEquals("TEMP_LOCKED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation WHERE id=401", String.class));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='CONFIRM'",
                    Integer.class));

            paidProof.set(true);
            db.transaction().execute(status -> {
                guard.acquire(List.of(STORE), QUERY_CONTEXT);
                api.confirm(command);
                return null;
            });
            assertEquals("CONFIRMED", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_reservation WHERE id=401", String.class));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_reservation_claim WHERE reservation_id=401",
                    Integer.class));
            assertEquals("SYSTEM", db.jdbc.queryForObject(
                    "SELECT actor_type FROM schedule_reservation_audit WHERE action='CONFIRM'",
                    String.class));
            assertThrows(ApiException.class, () -> db.transaction().execute(status -> {
                guard.acquire(List.of(STORE), QUERY_CONTEXT);
                api.confirm(command);
                return null;
            }));
        }
    }

    private static final class Database implements AutoCloseable {
        final String name = "confirm_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33471/");
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException("test database must be local MySQL root");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("schema root absent");
                try (Connection connection = source.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "13-Async-Infra-Schema-v0.1.sql",
                            "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                            "38-Booking-Create-Schema-v0.1.sql",
                            "39-Booking-Expiry-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
                }
                seed();
            } catch (Exception failure) { close(); throw failure; }
        }

        TransactionTemplate transaction() {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return tx;
        }

        private void seed() {
            jdbc.update("""
                    INSERT INTO schedule_availability_window
                      (id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,
                       status,version,created_at,updated_at,window_kind)
                    VALUES (101,10,201,301,'2030-01-01 01:00:00','2030-01-01 02:00:00',1,
                            'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL')
                    """);
            jdbc.update("""
                    INSERT INTO schedule_reservation
                      (id,order_id,merchant_id,store_id,service_id,fulfillment_type,start_at,
                       end_at,status,lock_token,lock_expire_at,capacity_snapshot,
                       qualified_staff_count_snapshot,version,created_at,updated_at,user_id)
                    VALUES (401,501,10,201,301,'IN_STORE','2030-01-01 01:00:00',
                            '2030-01-01 02:00:00','TEMP_LOCKED','hold-confirm',
                            '2030-01-01 00:10:00',1,1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),601)
                    """);
            jdbc.update("""
                    INSERT INTO schedule_reservation_claim
                      (id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at)
                    VALUES (701,401,101,201,301,'GENERAL','2030-01-01 01:00:00',
                            '2030-01-01 02:00:00')
                    """);
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true", user, password);
        }
        @Override public void close() { admin.execute("DROP DATABASE IF EXISTS `" + name + "`"); }
    }
}
