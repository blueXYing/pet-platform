package com.petplatform.boot.adapter.web.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandService;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Contract53 §3.3 (2026-10-07) controller acceptance for the merchant workbench window list
 * pagination over isolated real MySQL: the unpaginated legacy shape stays byte-compatible
 * (no envelope keys, no LIMIT), explicit paging slices with a filter-matched total, an
 * over-end page answers empty items with an unchanged total, illegal paging values answer
 * 400, the fixed start-ascending/id-tiebreak order stays stable across pages and the
 * optional filters compose with paging. Closes the gap registered by the M-side slice #106.
 */
class MerchantSchedulePaginationHttpTest {

    private static final String STORE = "201";
    /**
     * Seed order, fixed start_at-ascending/id-tiebreak expectation for store 201 (11 rows:
     * GENERAL 101..107 hourly 02:00..08:00, PICKUP 108 sharing 03:00 with 102, CLOSED 109 at
     * 09:00, service-302 rows 210 at 02:00 / SOLD_OUT 211 at 03:00). Id 301 belongs to
     * another store and must never appear.
     */
    private static final List<String> ALL_ORDERED = List.of(
            "101", "210", "102", "108", "211", "103", "104", "105", "106", "107", "109");

    private Fixture fixture;
    private MerchantScheduleController controller;

    @BeforeEach
    void start() throws Exception {
        fixture = new Fixture();
        seed(fixture);
        MerchantAdmissionQueryApi admission = new MerchantAdmissionQueryApi() {
            @Override
            public MerchantMembershipPageDTO listMemberships(
                    com.petplatform.merchant.api.query.MerchantMembershipQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                return new MerchantAdmissionDTO(query.merchantId(), query.storeId(), "OWNER",
                        "ALLOWED", OffsetDateTime.parse("2026-10-07T00:00:00Z"), "1", null,
                        null, "ACTIVE", "ACTIVE", null, List.of(), List.of(), List.of());
            }
        };
        ScheduleAdmissionGate gate = new ScheduleAdmissionGate(admission);
        // The write side is assembled but never invoked by these reads; every port that would
        // touch other domains fails loudly instead of silently answering fake facts.
        ScheduleCapacityGuardApiImpl guard = new ScheduleCapacityGuardApiImpl(fixture.source);
        ScheduleProtectionFactsApiImpl facts =
                new ScheduleProtectionFactsApiImpl(fixture.source, guard);
        MerchantCurrentStaffFactsApi merchant = (storeId, context) -> {
            throw new UnsupportedOperationException();
        };
        OrderProtectionFactsApi orders = new OrderProtectionFactsApi() {
            @Override
            public com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot
                    readStore(String store, com.petplatform.common.QueryContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot
                    getByReservations(String store, List<String> reservationIds,
                            com.petplatform.common.QueryContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot
                    getCurrentAssignments(String store, List<String> staffIds,
                            com.petplatform.common.QueryContext context) {
                throw new UnsupportedOperationException();
            }
        };
        ServiceQueryApi services = new ServiceQueryApi() {
            @Override
            public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
                throw new UnsupportedOperationException();
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
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        ScheduleCapacityProofApiImpl proof =
                new ScheduleCapacityProofApiImpl(fixture.source, guard, facts, merchant, orders,
                        clock, 1000);
        SnowflakeIdGenerator ids = new AtomicLong(9000)::incrementAndGet;
        ScheduleMerchantCommandApiImpl commands = new ScheduleMerchantCommandApiImpl(
                new ScheduleMerchantCommandService(
                        new ScheduleWriteStore(fixture.source, ids), gate, guard, facts, merchant,
                        orders, services, proof, clock));
        controller = new MerchantScheduleController(commands,
                new ScheduleMerchantQueryApiImpl(fixture.source, gate));
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * Store 201 window rows, inserted deliberately out of order. GENERAL ids 101..107 start
     * hourly 02:00..08:00; id 108 is a PICKUP window sharing 03:00 with 102 (pins the id
     * tiebreak); id 109 is CLOSED at 09:00. Ids 210/211 belong to service 302, id 301 to a
     * different store: both must stay isolated from the paged totals.
     */
    private static void seed(Fixture fixture) {
        JdbcTemplate jdbc = fixture.jdbc;
        window(jdbc, 101, 301, "GENERAL", "2030-01-01 02:00:00", "2030-01-01 03:00:00", "OPEN");
        window(jdbc, 102, 301, "GENERAL", "2030-01-01 03:00:00", "2030-01-01 04:00:00", "OPEN");
        window(jdbc, 103, 301, "GENERAL", "2030-01-01 04:00:00", "2030-01-01 05:00:00", "OPEN");
        window(jdbc, 104, 301, "GENERAL", "2030-01-01 05:00:00", "2030-01-01 06:00:00", "OPEN");
        window(jdbc, 105, 301, "GENERAL", "2030-01-01 06:00:00", "2030-01-01 07:00:00", "OPEN");
        window(jdbc, 106, 301, "GENERAL", "2030-01-01 07:00:00", "2030-01-01 08:00:00", "OPEN");
        window(jdbc, 107, 301, "GENERAL", "2030-01-01 08:00:00", "2030-01-01 09:00:00", "OPEN");
        window(jdbc, 108, 301, "PICKUP", "2030-01-01 03:00:00", "2030-01-01 03:30:00", "OPEN");
        window(jdbc, 109, 301, "GENERAL", "2030-01-01 09:00:00", "2030-01-01 10:00:00",
                "CLOSED");
        window(jdbc, 210, 302, "GENERAL", "2030-01-01 02:00:00", "2030-01-01 03:00:00", "OPEN");
        window(jdbc, 211, 302, "GENERAL", "2030-01-01 03:00:00", "2030-01-01 04:00:00",
                "SOLD_OUT");
        jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                + "service_id,start_at,end_at,configured_capacity,status,version,created_at,"
                + "updated_at,window_kind) VALUES(301,10,999,301,'2030-01-01 02:00:00',"
                + "'2030-01-01 03:00:00',1,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'GENERAL')");
    }

    private static void window(JdbcTemplate jdbc, long id, long service, String kind,
            String start, String end, String status) {
        jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                + "service_id,start_at,end_at,configured_capacity,status,version,created_at,"
                + "updated_at,window_kind) VALUES(?,?,201,?,?,?,1,?,0,UTC_TIMESTAMP(3),"
                + "UTC_TIMESTAMP(3),?)", id, 10L, service, start, end, status, kind);
    }

    private static MockHttpServletRequest request(String query) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/merchant/stores/201/"
                        + "availability-windows");
        request.setAttribute(CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", "601", Instant.now(), "138****0000", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private static List<String> ids(Map<String, Object> data) {
        return items(data).stream().map(item -> (String) item.get("windowId")).toList();
    }

    @Test
    void unpaginatedRequestKeepsTheLegacyShapeAndFullList() {
        Map<String, Object> data = data(controller.listWindows(STORE, "10", null, null, null,
                null, null, request("merchantId=10")));
        // Byte-compatible legacy answer: exactly {storeId, items}, no envelope keys, no LIMIT.
        assertEquals(List.of("storeId", "items"), List.copyOf(data.keySet()));
        assertEquals(ALL_ORDERED, ids(data));
        assertFalse(data.containsKey("page"));
        assertFalse(data.containsKey("pageSize"));
        assertFalse(data.containsKey("total"));
    }

    @Test
    void explicitPagingSlicesInFixedOrderWithFilterMatchedTotal() {
        Map<String, Object> page2 = data(controller.listWindows(STORE, "10", null, null, null, "2",
                "3", request("merchantId=10&page=2&pageSize=3")));
        assertEquals(11L, page2.get("total"));
        assertEquals(2, page2.get("page"));
        assertEquals(3, page2.get("pageSize"));
        assertEquals(ALL_ORDERED.subList(3, 6), ids(page2));

        Map<String, Object> page1 = data(controller.listWindows(STORE, "10", null, null, null, "1",
                "3", request("merchantId=10&page=1&pageSize=3")));
        Map<String, Object> page3 = data(controller.listWindows(STORE, "10", null, null, null, "3",
                "3", request("merchantId=10&page=3&pageSize=3")));
        // Pages 1..3 concatenated reproduce the full start-ascending/id-tiebreak order.
        assertEquals(ALL_ORDERED.subList(0, 3), ids(page1));
        assertEquals(ALL_ORDERED.subList(6, 9), ids(page3));
    }

    @Test
    void partialPagingFallsBackToDefaultsAndEmptyStringCountsAsAbsent() {
        // Only pageSize: page defaults to 1.
        Map<String, Object> sizeOnly = data(controller.listWindows(STORE, "10", null, null, null,
                null, "5", request("merchantId=10&pageSize=5")));
        assertEquals(1, sizeOnly.get("page"));
        assertEquals(5, sizeOnly.get("pageSize"));
        assertEquals(11L, sizeOnly.get("total"));
        assertEquals(ALL_ORDERED.subList(0, 5), ids(sizeOnly));

        // A bare empty page value is absent, so the request stays unpaginated (legacy shape).
        Map<String, Object> legacy = data(controller.listWindows(STORE, "10", null, null, null, "",
                null, request("merchantId=10&page=")));
        assertEquals(List.of("storeId", "items"), List.copyOf(legacy.keySet()));
        assertEquals(ALL_ORDERED, ids(legacy));
    }

    @Test
    void overEndPageAnswersEmptyItemsWithUnchangedTotal() {
        Map<String, Object> data = data(controller.listWindows(STORE, "10", null, null, null, "5",
                "3", request("merchantId=10&page=5&pageSize=3")));
        assertEquals(List.of(), ids(data));
        assertEquals(11L, data.get("total"));
        assertEquals(5, data.get("page"));
    }

    @Test
    void filtersApplyBeforeCountingAndSlicing() {
        Map<String, Object> openOnly = data(controller.listWindows(STORE, "10", null, null, "OPEN",
                "1", "20", request("merchantId=10&status=OPEN&page=1&pageSize=20")));
        assertEquals(9L, openOnly.get("total"));
        assertEquals(9, items(openOnly).size());

        Map<String, Object> pickup = data(controller.listWindows(STORE, "10", null, "PICKUP", null,
                "1", "20", request("merchantId=10&kind=PICKUP&page=1&pageSize=20")));
        assertEquals(1L, pickup.get("total"));
        assertEquals(List.of("108"), ids(pickup));

        Map<String, Object> otherService = data(controller.listWindows(STORE, "10", "302", null,
                null, "1", "1", request("merchantId=10&serviceId=302&page=1&pageSize=1")));
        assertEquals(2L, otherService.get("total"));
        assertEquals(List.of("210"), ids(otherService));

        // Closed windows stay listed in the unfiltered totals (workbench sees full states).
        Map<String, Object> closed = data(controller.listWindows(STORE, "10", null, null,
                "CLOSED", null, null, request("merchantId=10&status=CLOSED")));
        assertEquals(List.of("storeId", "items"), List.copyOf(closed.keySet()));
        assertEquals(List.of("109"), ids(closed));
    }

    @Test
    void illegalPagingValuesAreRejected() {
        for (String query : new String[] {
                "merchantId=10&page=0", "merchantId=10&page=10001", "merchantId=10&page=x",
                "merchantId=10&page=1.5", "merchantId=10&pageSize=0", "merchantId=10&pageSize=51",
                "merchantId=10&pageSize=99999", "merchantId=10&pageSize=-1",
                "merchantId=10&foo=1"}) {
            String page = param(query, "page"), size = param(query, "pageSize");
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> controller.listWindows(STORE, "10", null, null, null, page, size,
                            request(query))).code(), query);
        }
        // Duplicate paging parameters are rejected like every other query violation.
        MockHttpServletRequest duplicated = request("merchantId=10");
        duplicated.addParameter("page", new String[] {"1", "2"});
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> controller.listWindows(STORE, "10", null, null, null, "1", null,
                        duplicated)).code());
        // The paging bounds follow the notification-list baseline: the edges are legal.
        Map<String, Object> edge = data(controller.listWindows(STORE, "10", null, null, null,
                "10000", "50", request("merchantId=10&page=10000&pageSize=50")));
        assertEquals(List.of(), ids(edge));
        assertEquals(11L, edge.get("total"));
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        }
        return null;
    }

    /**
     * Fresh isolated real MySQL carrying the authoritative schema (the read path touches only
     * schedule_availability_window, so SQL06 suffices); dropped on close, even on failure.
     * Env contract copied from the sibling SCH-004 MySQL tests (SCH004_/SCH003_/MER001_/AUTH_).
     */
    private static final class Fixture implements AutoCloseable {
        private static final String PREFIX = "schpg_http_";

        private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private boolean created;

        private Fixture() throws Exception {
            String prefix = System.getenv().containsKey("SCH004_MYSQL_URL") ? "SCH004"
                    : System.getenv().containsKey("SCH003_MYSQL_URL") ? "SCH003"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "SCH004";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        prefix + "_MYSQL_URL must target a local isolated server, database-less");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(connect(url, user, password));
            source = connect(url + name
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try {
                String version = admin.queryForObject("SELECT VERSION()", String.class);
                if (version == null || !version.startsWith("8.")) {
                    throw new IllegalStateException("Real MySQL 8 is required");
                }
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(
                        root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                Path script = Objects.requireNonNull(root,
                        "Repository root with SQL06 was not found")
                        .resolve("docs/03-database/06-核心数据库Schema-v0.1.sql");
                // SQL06 creates schedule_availability_window; SQL37 adds the window_kind
                // column the seeded rows carry (the same core pair the SCH-003 tests load).
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(script), StandardCharsets.UTF_8));
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(script.getParent()
                                    .resolve("37-Reservation-Protection-Foundation-Schema-v0.1.sql")
                                    .toFile()), StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static DataSource connect(String url, String user, String password) {
            return new DriverManagerDataSource(url, user, password) {
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
            if (created) {
                if (!name.startsWith(PREFIX) || !name.matches("[a-z0-9_]+")) {
                    throw new IllegalStateException(
                            "Refusing to drop unexpected database name: " + name);
                }
                admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
                created = false;
            }
        }
    }
}
