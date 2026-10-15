package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminAuthorization;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindowListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminWindowListQuery;
import com.petplatform.schedule.biz.apiimpl.AdminScheduleQueryApiImpl;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Check;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Phase;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.ScheduleGovernanceAuthorizationPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Isolated real MySQL proves the contract-64 schedule view read face (PRD 6.2.5 只读监管):
 * window distribution with SOLD_OUT-口径 occupancy facts, staff shifts, the reservation/lock
 * ledger including the timeout-release history, plus filter/vocabulary gates and fail-closed
 * authorization. Strictly read-only — the face has no write path to prove.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminScheduleQueryMySqlTest {
    private static final String OPERATOR = "9100000000000999";
    private static final Instant NOW = Instant.parse("2029-12-31T23:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Database db;
    private AdminScheduleQueryApi api;
    private final AtomicReference<String> lastCollectionAction = new AtomicReference<>();

    @BeforeAll
    void open() throws Exception {
        db = new Database();
        api = new AdminScheduleQueryApiImpl(db.source, port(true, "ALLOWED"), CLOCK);
        seed();
    }

    @AfterAll
    void close() throws Exception {
        if (db != null) db.close();
    }

    private void seed() {
        // Service windows: one OPEN with live claims, one CLOSED, one SOLD_OUT, one far away.
        window(101, "GENERAL", "2030-01-01 01:00:00", "2030-01-01 02:00:00", 3, "OPEN", 5);
        window(102, "GENERAL", "2030-01-02 01:00:00", "2030-01-02 02:00:00", 2, "CLOSED", 2);
        window(103, "PICKUP", "2030-01-03 01:00:00", "2030-01-03 02:00:00", 1, "SOLD_OUT", 3);
        window(104, "GENERAL", "2030-02-01 01:00:00", "2030-02-01 02:00:00", 2, "OPEN", 0);
        // Reservations: confirmed + live lock on 101; expired lock; timeout release; pickup trip.
        reservation(601, 701, "CONFIRMED", "IN_STORE", "2030-01-01 01:00:00", "2030-01-01 02:00:00", "2030-01-01 00:30:00");
        reservation(602, 702, "TEMP_LOCKED", "IN_STORE", "2030-01-01 01:00:00", "2030-01-01 02:00:00", "2030-01-01 00:30:00");
        reservation(603, 703, "TEMP_LOCKED", "IN_STORE", "2030-01-01 03:00:00", "2030-01-01 04:00:00", "2029-12-31 22:00:00");
        reservation(604, 704, "EXPIRED", "IN_STORE", "2030-01-01 04:00:00", "2030-01-01 05:00:00", "2029-12-31 21:30:00");
        reservation(605, 705, "RELEASED", "PICKUP_DELIVERY", "2030-01-02 05:00:00", "2030-01-02 06:00:00", "2029-12-31 20:00:00");
        reservation(606, 706, "CONFIRMED", "PICKUP_DELIVERY", "2030-01-03 01:00:00", "2030-01-03 02:00:00", "2030-01-03 00:30:00");
        jdbc().update("UPDATE schedule_reservation SET pickup_start_at=?, return_start_at=? WHERE id=605",
                "2029-12-31 20:00:00", "2030-01-01 22:30:00");
        claim(901, 601, 101, "GENERAL");
        claim(902, 602, 101, "GENERAL");
        claim(903, 606, 103, "PICKUP");
        // Staff shifts.
        jdbc().update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at) VALUES(801,201,401,'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',4,'2029-12-30 08:00:00','2029-12-30 08:00:00')");
        jdbc().update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,version,created_at,updated_at) VALUES(802,201,402,'2030-01-01 01:00:00','2030-01-01 02:00:00','CLOSED',1,'2029-12-30 08:00:00','2029-12-30 08:00:00')");
    }

    private void window(long id, String kind, String start, String end, int capacity, String status, int version) {
        jdbc().update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,window_kind,start_at,end_at,configured_capacity,status,version,created_at,updated_at) VALUES(?,10,201,301,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id, kind, start, end, capacity, status, version);
    }

    private void reservation(long id, long orderId, String status, String fulfillment,
            String start, String end, String lockExpire) {
        jdbc().update("INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,service_id,fulfillment_type,start_at,end_at,status,lock_token,lock_expire_at,capacity_snapshot,qualified_staff_count_snapshot,version,created_at,updated_at) VALUES(?,?,10,201,301,?,?,?,?,?,?,3,2,1,'2029-12-30 08:00:00','2029-12-30 08:00:00')",
                id, orderId, fulfillment, start, end, status, null, lockExpire);
    }

    private void claim(long id, long reservationId, long windowId, String kind) {
        var window = jdbc().queryForMap(
                "SELECT start_at, end_at FROM schedule_availability_window WHERE id=?", windowId);
        jdbc().update("INSERT INTO schedule_reservation_claim(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at) VALUES(?,?,?,?,?,?,?,?)",
                id, reservationId, windowId, 201L, 301L, kind, window.get("start_at"), window.get("end_at"));
    }

    private JdbcTemplate jdbc() {
        return db.jdbc;
    }

    private ScheduleGovernanceAuthorizationPort port(boolean allowed, String reasonCode) {
        return new ScheduleGovernanceAuthorizationPort() {
            @Override
            public Decision check(Check checkQuery) {
                return decision(allowed, reasonCode);
            }

            @Override
            public Decision checkCollection(CollectionCheck collectionQuery) {
                lastCollectionAction.set(collectionQuery.actionCode());
                return decision(allowed, reasonCode);
            }
        };
    }

    private static Decision decision(boolean allowed, String reasonCode) {
        return new Decision(allowed, NOW.atOffset(ZoneOffset.UTC), "epoch:1", reasonCode);
    }

    /** Null decision on every check: the authorization owner is unavailable. */
    private ScheduleGovernanceAuthorizationPort unavailablePort() {
        return new ScheduleGovernanceAuthorizationPort() {
            @Override
            public Decision check(Check checkQuery) {
                return null;
            }

            @Override
            public Decision checkCollection(CollectionCheck collectionCheck) {
                return null;
            }
        };
    }

    private AdminScheduleQueryApi api(boolean allowed, String reasonCode) {
        return new AdminScheduleQueryApiImpl(db.source, port(allowed, reasonCode), CLOCK);
    }

    private static QueryContext query() {
        return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, OPERATOR);
    }

    private static AdminAuthorization authorization() {
        return new AdminAuthorization("9100000000000888", 4L);
    }

    private static OffsetDateTime utc(String value) {
        return OffsetDateTime.parse(value);
    }

    @Test
    void windowListCarriesSoldOutNotionOccupancyAndFilters() {
        PageResult<AdminScheduleQueryApi.AdminScheduleWindow> page = api.listWindows(
                new AdminWindowListQuery(null, null, null, null, null, null, null, 1, 20,
                        query(), authorization()));
        assertEquals("schedule.read", lastCollectionAction.get());
        assertEquals(4, page.total());
        // start_at DESC: 104, 103, 102, 101.
        assertEquals("104", page.items().get(0).windowId());
        var open = page.items().get(3);
        assertEquals("101", open.windowId());
        assertEquals(1, open.confirmedCount());
        assertEquals(1, open.tempLockedCount());
        assertEquals("OPEN", open.status());
        assertEquals("5", open.version());
        var soldOut = page.items().get(1);
        assertEquals("SOLD_OUT", soldOut.status());
        assertEquals(1, soldOut.confirmedCount());
        assertEquals(0, soldOut.tempLockedCount());
        assertEquals("PICKUP", soldOut.windowKind());
        assertEquals(1, api.listWindows(new AdminWindowListQuery(null, null, null, null,
                "SOLD_OUT", null, null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listWindows(new AdminWindowListQuery(null, null, null, "PICKUP",
                null, null, null, 1, 20, query(), authorization())).total());
        assertEquals(0, api.listWindows(new AdminWindowListQuery("11", null, null, null, null,
                null, null, 1, 20, query(), authorization())).total());
        var range = api.listWindows(new AdminWindowListQuery(null, null, null, null, null,
                utc("2030-01-01T00:00:00.000Z"), utc("2030-01-03T00:00:00.000Z"), 1, 20,
                query(), authorization()));
        assertEquals(2, range.total());
        var paged = api.listWindows(new AdminWindowListQuery(null, null, null, null, null, null,
                null, 2, 2, query(), authorization()));
        assertEquals(4, paged.total());
        assertEquals(2, paged.items().size());
        assertEquals("102", paged.items().get(0).windowId());
    }

    @Test
    void staffWindowListFiltersByStoreStaffAndStatus() {
        var page = api.listStaffWindows(new AdminStaffWindowListQuery(null, null, null, null,
                null, 1, 20, query(), authorization()));
        assertEquals(2, page.total());
        var first = page.items().get(0);
        assertEquals("802", first.windowId());
        assertEquals("402", first.staffId());
        assertEquals("CLOSED", first.status());
        assertEquals(1, api.listStaffWindows(new AdminStaffWindowListQuery(null, "401", null,
                null, null, 1, 20, query(), authorization())).total());
        assertEquals(1, api.listStaffWindows(new AdminStaffWindowListQuery(null, null,
                "AVAILABLE", null, null, 1, 20, query(), authorization())).total());
    }

    @Test
    void reservationLedgerSurfacesTimeoutReleasesAndPickupFacts() {
        var page = api.listReservations(new AdminReservationListQuery(null, null, null, null,
                null, null, null, 1, 20, query(), authorization()));
        assertEquals("schedule.read", lastCollectionAction.get());
        assertEquals(6, page.total());
        // start_at DESC, id DESC on ties: 606, 605, 604, 603, 602, 601.
        assertEquals("606", page.items().get(0).reservationId());
        assertEquals("602", page.items().get(4).reservationId());
        assertEquals("601", page.items().get(5).reservationId());
        assertEquals(1, api.listReservations(new AdminReservationListQuery(null, null, null,
                null, "EXPIRED", null, null, 1, 20, query(), authorization())).total());
        var pickup = api.listReservations(new AdminReservationListQuery(null, null, null, "705",
                null, null, null, 1, 20, query(), authorization())).items().get(0);
        assertEquals("RELEASED", pickup.status());
        assertEquals("PICKUP_DELIVERY", pickup.fulfillmentType());
        assertEquals("2029-12-31T20:00:00Z", pickup.pickupStartAt().toInstant().toString());
        assertEquals("2030-01-01T22:30:00Z", pickup.returnStartAt().toInstant().toString());
        var locked = api.listReservations(new AdminReservationListQuery(null, null, null, "702",
                null, null, null, 1, 20, query(), authorization())).items().get(0);
        assertEquals("2030-01-01T00:30:00Z", locked.lockExpireAt().toInstant().toString());
        assertEquals(3, locked.capacitySnapshot());
        assertEquals(2, locked.qualifiedStaffCountSnapshot());
    }

    @Test
    void vocabularyRangeValidationRejectsInvalidInput() {
        assertInvalidWindows(new AdminWindowListQuery(null, null, null, "DROPIN", null, null, null, 1,
                20, query(), authorization()));
        assertInvalidWindows(new AdminWindowListQuery(null, null, null, null, "PAUSED", null, null, 1,
                20, query(), authorization()));
        assertInvalidStaff(new AdminStaffWindowListQuery(null, null, "SLEEPING", null, null, 1, 20,
                query(), authorization()));
        assertInvalidReservations(new AdminReservationListQuery(null, null, null, null, "LOCKED", null, null,
                1, 20, query(), authorization()));
        // Unpaired range and over-long range (92-day cap) both reject.
        assertInvalidWindows(new AdminWindowListQuery(null, null, null, null, null,
                utc("2030-01-01T00:00:00.000Z"), null, 1, 20, query(), authorization()));
        assertInvalidWindows(new AdminWindowListQuery(null, null, null, null, null,
                utc("2030-01-01T00:00:00.000Z"), utc("2030-05-01T00:00:00.000Z"), 1, 20,
                query(), authorization()));
        assertInvalidReservations(new AdminReservationListQuery(null, null, null, null, null,
                utc("2030-01-01T00:00:00.000Z"), utc("2030-05-01T00:00:00.000Z"), 1, 20,
                query(), authorization()));
    }

    private void assertInvalidWindows(AdminWindowListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listWindows(query)).code());
    }

    private void assertInvalidStaff(AdminStaffWindowListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listStaffWindows(query)).code());
    }

    private void assertInvalidReservations(AdminReservationListQuery query) {
        assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                assertThrows(ApiException.class, () -> api.listReservations(query)).code());
    }

    @Test
    void authorizationFailuresStayClosed() {
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api(false, "ACTION_NOT_GRANTED").listWindows(new AdminWindowListQuery(null,
                        null, null, null, null, null, null, 1, 20, query(), authorization())))
                .code());
        // A present but denied decision stays 403 even without a reason code.
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api(false, null).listReservations(new AdminReservationListQuery(null, null,
                        null, null, null, null, null, 1, 20, query(), authorization()))).code());
        // Null authorization decisions never read (503 fail-closed).
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                () -> new AdminScheduleQueryApiImpl(db.source, unavailablePort(), CLOCK)
                        .listReservations(new AdminReservationListQuery(null, null, null, null,
                                null, null, null, 1, 20, query(), authorization()))).code());
        assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class,
                () -> api.listStaffWindows(new AdminStaffWindowListQuery(null, null, null, null,
                        null, 1, 20, new QueryContext("trace", OperatorType.USER, "1"),
                        authorization()))).code());
        assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> api.listWindows(new AdminWindowListQuery(null, null, null, null, null,
                        null, null, 1, 20, query(), new AdminAuthorization("", 1)))).code());
    }

    private static final class Database implements AutoCloseable {
        private final String name = "gov2sched_" + UUID.randomUUID().toString().replace("-", "");
        private final DataSource source;
        private final JdbcTemplate admin;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            // Same local-MySQL harness contract as the sibling schedule tests: the
            // <prefix>_MYSQL_URL environment wins, defaults target a local root only.
            String prefix = System.getenv().containsKey("GOV2_MYSQL_URL") ? "GOV2"
                    : System.getenv().containsKey("SCH004_MYSQL_URL") ? "SCH004"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "GOV2";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must target a local root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            this.source = source(url + name, user, password);
            jdbc = new JdbcTemplate(this.source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("Schema06 is absent");
                for (String script : List.of("06-核心数据库Schema-v0.1.sql",
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql")) {
                    try (Connection connection = this.source.getConnection()) {
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

        private DataSource source(String url, String user, String password) {
            DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return source;
        }

        @Override
        public void close() throws Exception {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }
}
