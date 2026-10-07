package com.petplatform.boot.adapter.web.c;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.StaffLoginPhonePort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Contract 54 §7 employee invitation list controller acceptance over isolated real MySQL
 * (schemas 06/28/29/52/54): the wire projection (exact keys, string ids, millisecond ISO
 * instants, PageResult wrapping, fixed id DESC order), the paging/parameter 400 surface, the
 * missing-session 401, and the anti-enumeration empty page for non-matching sessions. The
 * switch-off route-absence behavior matches the existing controller wiring (404 while the
 * double switch stays off) and is pinned by the context-runner case.
 */
class CStaffInvitationListHttpTest {

    private static final long MERCHANT = 9_100_000_000_000_101L;
    private static final long STORE = 9_100_000_000_000_102L;
    private static final long OWNER = 9_100_000_000_000_110L;
    private static final long USER = 9_100_000_000_000_111L;
    private static final long OTHER_USER = 9_100_000_000_000_112L;
    private static final String PHONE = "13900001111";
    private static final String OTHER_PHONE = "13900002222";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);

    private static final long INV_PENDING = 9_300_000_000_000_301L;
    private static final long INV_CONFIRMED = 9_300_000_000_000_291L;
    private static final long INV_OTHER = 9_300_000_000_000_299L;

    private Fixture fixture;
    private CStaffInvitationController controller;

    @BeforeEach
    void start() throws Exception {
        fixture = new Fixture();
        seed(fixture);
        // Fake mirroring the boot wiring: the confirm/detail channel stays boolean, the §7 list
        // seeks with the session phone read once through the purpose-bound port.
        StaffLoginPhonePort loginPhones = new StaffLoginPhonePort() {
            @Override
            public boolean matchesSessionUserPhone(long userId, String phone) {
                return userId == USER && PHONE.equals(phone);
            }

            @Override
            public String sessionUserPhone(long userId) {
                return userId == USER ? PHONE : null;
            }
        };
        MerchantStaffMemberApiImpl api = new MerchantStaffMemberApiImpl(fixture.source,
                () -> 9_300_000_000_000_900L,
                (ApplicationReviewFactsReader) merchantId ->
                        new ApplicationReviewFactsReader.Facts("APPROVED"),
                new AesGcmProtectedValueProvider("staff-inv-list-test-v1", key((byte) 3), key((byte) 4)),
                loginPhones, Mockito.mock(ScheduleCapacityGuardApi.class), CLOCK);
        controller = new CStaffInvitationController(api);
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static byte[] key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static void seed(Fixture fixture) {
        JdbcTemplate jdbc = fixture.jdbc;
        jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,version,created_at,updated_at)"
                + " VALUES(?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MERCHANT, OWNER, "列表测试商家");
        jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,version,created_at,updated_at)"
                + " VALUES(?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", STORE, MERCHANT, "旗舰一店", "地址一");
        jdbc.update("INSERT INTO merchant_application(id,owner_user_id,reserved_merchant_id,status,"
                + "subject_verification_status,version,created_at,updated_at)"
                + " VALUES(?,?,?,'DRAFT','NOT_STARTED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", 2101L, OWNER, MERCHANT);
        invitation(jdbc, INV_CONFIRMED, PHONE, "CONFIRMED", Long.toUnsignedString(INV_CONFIRMED),
                true);
        invitation(jdbc, INV_PENDING, PHONE, "INVITED", "PENDING", false);
        invitation(jdbc, INV_OTHER, OTHER_PHONE, "INVITED", "PENDING", false);
        for (long invitation : new long[] {INV_CONFIRMED, INV_PENDING, INV_OTHER}) {
            jdbc.update("INSERT INTO merchant_member_invitation_action(id,invitation_id,action_code,created_at)"
                    + " VALUES(?,?,'merchant.order.verify',UTC_TIMESTAMP(3))",
                    invitation + 1, invitation);
        }
    }

    private static void invitation(JdbcTemplate jdbc, long id, String phone, String status,
            String pendingMarker, boolean confirmed) {
        jdbc.update("INSERT INTO merchant_member_invitation(id,merchant_id,store_id,phone,member_name,"
                + "status,invited_by,confirmed_by,member_id,version,pending_marker,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?," + (confirmed ? "9300000000000201" : "NULL")
                + ",0,?,'2026-10-06 08:00:00.000','2026-10-06 08:00:00.000')",
                id, MERCHANT, STORE, phone, "李小美", status, OWNER,
                confirmed ? USER : null, pendingMarker);
    }

    private static MockHttpServletRequest request(long userId, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/c/staff/invitations");
        request.setAttribute(CBearerSessionFilter.VIEW, new MiniSessionView("session-1",
                Long.toString(userId), Instant.now(), "138****0000", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    @Test
    void listProjectsOwnInvitationsNewestFirstOnTheWire() {
        ApiResponse<Map<String, Object>> value = controller.list(null, null, request(USER, null));
        assertEquals("SUCCESS", value.code());
        assertEquals(2L, value.data().get("total"));
        assertEquals(1, value.data().get("page"));
        assertEquals(20, value.data().get("pageSize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) value.data().get("items");
        assertEquals(2, items.size());
        // Fixed stable order id DESC: the pending invitation is newer than the confirmed one.
        assertEquals(Long.toUnsignedString(INV_PENDING), items.get(0).get("invitationId"));
        assertEquals(Long.toUnsignedString(INV_CONFIRMED), items.get(1).get("invitationId"));
        Map<String, Object> row = items.get(0);
        // §7 projection: exact key set, names, action catalog, ISO millisecond instants.
        assertEquals(Set.of("invitationId", "merchantId", "merchantName", "storeId", "storeName",
                "memberName", "grantedActions", "status", "invitedAt", "updatedAt"), row.keySet());
        assertEquals("INVITED", row.get("status"));
        assertEquals("列表测试商家", row.get("merchantName"));
        assertEquals("旗舰一店", row.get("storeName"));
        assertEquals("李小美", row.get("memberName"));
        assertEquals(List.of("merchant.order.verify"), row.get("grantedActions"));
        assertEquals("2026-10-06T08:00:00.000Z", row.get("invitedAt"));
        assertEquals("2026-10-06T08:00:00.000Z", row.get("updatedAt"));
        assertEquals("9100000000000101", row.get("merchantId"));
        assertEquals("9100000000000102", row.get("storeId"));
        // The OTHER_PHONE row never appears; the confirmed history does.
        assertEquals("CONFIRMED", items.get(1).get("status"));
    }

    @Test
    void listIsAntiEnumerationEmptyForNonMatchingSessions() {
        for (long session : new long[] {OTHER_USER, 9_100_000_000_000_199L}) {
            ApiResponse<Map<String, Object>> value = controller.list(null, null, request(session, null));
            assertEquals("SUCCESS", value.code());
            assertEquals(0L, value.data().get("total"));
            assertTrue(((List<?>) value.data().get("items")).isEmpty());
        }
    }

    @Test
    void listParsesPagingAndRejectsAnythingElse() {
        ApiResponse<Map<String, Object>> page = controller.list("2", "1", request(USER, "page=2&pageSize=1"));
        assertEquals(1, ((List<?>) page.data().get("items")).size());
        // Second row of the fixed id DESC order: the older confirmed invitation.
        assertEquals("9300000000000291", ((Map<?, ?>) ((List<?>) page.data().get("items")).get(0)).get("invitationId"));

        for (String query : new String[] {"foo=1", "page=0", "page=x", "pageSize=51", "pageSize=0", "page="}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> controller.list(valueOf(query, "page"), valueOf(query, "pageSize"),
                            request(USER, query)), query).code(), query);
        }
        MockHttpServletRequest duplicated = request(USER, null);
        duplicated.addParameter("page", new String[] {"1", "2"});
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> controller.list("1", null, duplicated)).code());
    }

    private static String valueOf(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        }
        return null;
    }

    @Test
    void missingSessionIsUnauthorized() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/v1/c/staff/invitations");
        assertEquals(CommonApiCodes.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> controller.list(null, null, anonymous)).code());
    }

    @Test
    void sliceStaysDefaultOffUntilBothSwitchesAreEnabled() {
        // Route absent (404 by the shared error surface) while the double switch stays off,
        // exactly like the §4 employee routes shipped by the binding slice.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CStaffInvitationController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CStaffInvitationController.class).isEmpty());
                });
    }

    /** Fresh isolated real MySQL with the authoritative schemas; dropped on close, even on
     *  failure. Env-prefix chain copied from the binding MySQL test (STAFFB -> MER001 -> AUTH). */
    private static final class Fixture implements AutoCloseable {
        private static final List<String> SCRIPTS = List.of(
                "06-核心数据库Schema-v0.1.sql",
                "28-Merchant-Agreement-Schema-v0.1.sql",
                "29-Merchant-Application-Schema-v0.1.sql",
                "52-Merchant-Staff-Identity-Schema-v0.1.sql",
                "54-Merchant-Staff-Binding-Schema-v0.1.sql");

        private final String name = "staffb_http_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private boolean created;

        private Fixture() throws Exception {
            String prefix = System.getenv().containsKey("STAFFB_MYSQL_URL") ? "STAFFB"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "STAFFB";
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
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
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
                Objects.requireNonNull(root, "Repository root with SQL06 was not found");
                try (Connection connection = source.getConnection()) {
                    for (String script : SCRIPTS) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + script)),
                                StandardCharsets.UTF_8));
                    }
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
                if (!name.startsWith("staffb_http_") || !name.matches("[a-z0-9_]+")) {
                    throw new IllegalStateException("Refusing to drop unexpected database name: " + name);
                }
                admin.execute("DROP DATABASE `" + name + "`");
                created = false;
            }
        }
    }
}
