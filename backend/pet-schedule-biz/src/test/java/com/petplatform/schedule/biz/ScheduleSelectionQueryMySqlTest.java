package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;
import com.petplatform.schedule.api.query.SelectionWindowQuery;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.schedule.biz.application.SelectionWindowQueryService;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceQueryApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ScheduleSelectionQueryMySqlTest {
    private static final LocalDate DATE = LocalDate.of(2030, 1, 1);
    private static final QueryContext CONTEXT =
            new QueryContext("selection-test", OperatorType.USER, "601");

    @Test
    void pickupAndReturnCountOriginalClaimsWithoutChargingGapOrLegacyGeneralWindow()
            throws Exception {
        try (Database db = new Database()) {
            db.window(101, "PICKUP", "2030-01-01 01:00:00", "2030-01-01 01:45:00");
            db.window(102, "GENERAL", "2030-01-01 02:00:00", "2030-01-01 04:00:00");
            db.window(103, "RETURN", "2030-01-01 04:00:00", "2030-01-01 05:30:00");
            db.reservation(201, "PICKUP_DELIVERY", "2030-01-01 01:00:00",
                    "2030-01-01 05:30:00", "2030-01-01 01:00:00",
                    "2030-01-01 04:00:00");
            db.claim(301, 201, 101, "PICKUP", "2030-01-01 01:00:00",
                    "2030-01-01 01:45:00");
            db.claim(302, 201, 103, "RETURN", "2030-01-01 04:00:00",
                    "2030-01-01 05:30:00");
            db.jdbc.update("UPDATE schedule_reservation SET status='TEMP_LOCKED', "
                    + "lock_expire_at='2029-01-01 00:00:00' WHERE id=201");

            SelectionWindowQueryService service = service(db, FulfillmentType.PICKUP_DELIVERY);
            SelectionWindowPageDTO page = service.page(query(null));
            assertEquals(List.of("101", "103"), page.items().stream()
                    .map(item -> item.windowId()).toList());
            assertEquals(List.of("PICKUP", "RETURN"), page.items().stream()
                    .map(item -> item.kind()).toList());
            assertEquals(List.of(1, 1), page.items().stream()
                    .map(item -> item.occupiedCount()).toList());
            assertEquals(List.of(1, 1), page.items().stream()
                    .map(item -> item.remainingCapacity()).toList());
            assertEquals("2030-01-01T01:00Z", page.items().getFirst().start().toString());
            assertEquals("2030-01-01T05:30Z", page.items().getLast().end().toString());
            assertEquals(List.of("103"), service.page(query("RETURN")).items().stream()
                    .map(item -> item.windowId()).toList());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    assertThrows(ApiException.class, () -> service.page(query("GENERAL"))).code());
        }
    }

    @Test
    void inStoreUsesGeneralClaimAndRejectsIncompleteOrUnknownFacts() throws Exception {
        try (Database db = new Database()) {
            db.window(101, "GENERAL", "2030-01-01 01:00:00", "2030-01-01 03:00:00");
            db.window(102, "GENERAL", "2030-01-01 03:00:00", "2030-01-01 05:00:00");
            db.reservation(201, "IN_STORE", "2030-01-01 01:30:00",
                    "2030-01-01 02:30:00", null, null);
            db.claim(301, 201, 101, "GENERAL", "2030-01-01 01:30:00",
                    "2030-01-01 02:30:00");
            SelectionWindowQueryService service = service(db, FulfillmentType.IN_STORE);
            assertEquals(List.of(1, 0), service.page(query(null)).items().stream()
                    .map(item -> item.occupiedCount()).toList());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    assertThrows(ApiException.class, () -> service.page(query("PICKUP"))).code());
            db.jdbc.update("DELETE FROM schedule_reservation_claim WHERE id=301");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> service.page(query(null))).code());
            db.jdbc.update("UPDATE schedule_availability_window SET window_kind='BROKEN' "
                    + "WHERE id=102");
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> service.page(query(null))).code());
        }
    }

    @Test
    void pickupLegacyGeneralReservationCannotBeInventedAsTwoDirectionalClaims()
            throws Exception {
        try (Database db = new Database()) {
            db.window(101, "GENERAL", "2030-01-01 01:00:00", "2030-01-01 05:00:00");
            db.window(102, "PICKUP", "2030-01-01 01:00:00", "2030-01-01 02:00:00");
            db.window(103, "RETURN", "2030-01-01 04:00:00", "2030-01-01 05:00:00");
            db.reservation(201, "PICKUP_DELIVERY", "2030-01-01 01:00:00",
                    "2030-01-01 05:00:00", "2030-01-01 01:00:00",
                    "2030-01-01 04:00:00");
            SelectionWindowQueryService service = service(db, FulfillmentType.PICKUP_DELIVERY);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> service.page(query(null))).code());
        }
    }

    @Test
    void orphanClaimAgainstAVisibleWindowFailsClosed() throws Exception {
        try (Database db = new Database()) {
            db.window(101, "GENERAL", "2030-01-01 01:00:00", "2030-01-01 03:00:00");
            db.claim(301, 999, 101, "GENERAL", "2030-01-01 01:30:00",
                    "2030-01-01 02:30:00");
            SelectionWindowQueryService service = service(db, FulfillmentType.IN_STORE);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> service.page(query(null))).code());
        }
    }

    @Test
    void soldOutWindowStaysListedWithZeroRemainingLikeAnAtCapacityOpenOne() throws Exception {
        try (Database db = new Database()) {
            // Contract52 §3 (2026-10-05 ruling): SOLD_OUT is an open-derived state, so the
            // selection read keeps listing it with remaining=0/available=false instead of
            // dropping the slot the C-end page previously showed as an at-capacity window.
            db.window(101, "GENERAL", "2030-01-01 01:00:00", "2030-01-01 03:00:00");
            db.jdbc.update("UPDATE schedule_availability_window SET status='SOLD_OUT', "
                    + "configured_capacity=1 WHERE id=101");
            db.reservation(201, "IN_STORE", "2030-01-01 01:30:00", "2030-01-01 02:30:00",
                    null, null);
            db.claim(301, 201, 101, "GENERAL", "2030-01-01 01:30:00",
                    "2030-01-01 02:30:00");
            SelectionWindowPageDTO page =
                    service(db, FulfillmentType.IN_STORE).page(query(null));
            assertEquals(1, page.items().size());
            var soldOut = page.items().getFirst();
            assertEquals("101", soldOut.windowId());
            assertEquals(1, soldOut.effectiveCapacity());
            assertEquals(1, soldOut.occupiedCount());
            assertEquals(0, soldOut.remainingCapacity());
            assertEquals(false, soldOut.available());
        }
    }

    private static SelectionWindowQuery query(String kind) {
        return new SelectionWindowQuery("301", "201", DATE, DATE, kind, CONTEXT);
    }

    private static SelectionWindowQueryService service(Database db, FulfillmentType type) {
        ServiceQueryApi services = mock(ServiceQueryApi.class);
        when(services.checkBookable(any())).thenReturn(
                new ServiceBookabilityDTO("301", "10", "201", true, List.of()));
        when(services.getServiceSnapshot(any())).thenReturn(new ServiceSnapshotDTO(
                "301", "10", "201", "service", "1", "category", BigDecimal.ONE, 60,
                type, null, null, null, null));
        QualifiedStaffFactsPort staff = (store, selectedService, from, to) -> 2;
        return new SelectionWindowQueryService(new SelectionReadStore(db.source), services,
                staff, Clock.fixed(Instant.parse("2029-12-31T00:00:00Z"), ZoneOffset.UTC));
    }

    private static final class Database implements AutoCloseable {
        private final String name = "selection_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        Database() throws Exception {
            String prefix=System.getenv().containsKey("SCH_SELECTION_MYSQL_URL") ? "SCH_SELECTION"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "PLAT004";
            String url = System.getenv().getOrDefault(prefix+"_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33450/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("test MySQL URL must target localhost");
            }
            String user = System.getenv().getOrDefault(prefix+"_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix+"_MYSQL_PASSWORD", "");
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
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql")) {
                    try (Connection connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + script)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        void window(long id, String kind, String start, String end) {
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "service_id,start_at,end_at,configured_capacity,status,version,"
                    + "created_at,updated_at,window_kind) VALUES(?,10,201,301,?,?,2,'OPEN',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)", id, start, end, kind);
        }

        void reservation(long id, String type, String start, String end,
                String pickup, String returning) {
            jdbc.update("INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,"
                    + "service_id,fulfillment_type,start_at,end_at,pickup_start_at,"
                    + "return_start_at,status,capacity_snapshot,qualified_staff_count_snapshot,"
                    + "version,created_at,updated_at,user_id) VALUES(?,?,10,201,301,?,?,?,?,?,"
                    + "'CONFIRMED',2,2,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),601)",
                    id, id + 1000, type, start, end, pickup, returning);
        }

        void claim(long id, long reservation, long window, String kind, String start,
                String end) {
            jdbc.update("INSERT INTO schedule_reservation_claim(id,reservation_id,window_id,"
                    + "store_id,service_id,kind,start_at,end_at) VALUES(?,?,?,201,301,?,?,?)",
                    id, reservation, window, kind, start, end);
        }

        private static DataSource source(String url, String user, String password) {
            // Keep connectionTimeZone unset: DATETIME must retain its UTC wall time when read.
            return new DriverManagerDataSource(url + "?allowPublicKeyRetrieval=true&useSSL=false",
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
