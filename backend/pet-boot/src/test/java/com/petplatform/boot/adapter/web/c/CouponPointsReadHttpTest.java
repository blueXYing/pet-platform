package com.petplatform.boot.adapter.web.c;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import com.petplatform.points.biz.apiimpl.PointsQueryApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * CCR-C006-COUPON-POINTS-READ-001 P1 controller acceptance: the four C routes over isolated real
 * MySQL (schema 06 sections 8/9). Covers the adapter concerns the biz tests cannot: the default
 * AVAILABLE bucket, the invalid-status and unknown/duplicate/bound-violating query parameter 400s,
 * the anti-enumeration 404 surface, the missing-session 401, and the exact wire projection
 * (string ids, two-decimal amounts, ISO instants, PageResult wrapping).
 */
class CouponPointsReadHttpTest {

    private static final long OWNER = 9_400_000_000_000_601L;
    private static final long OTHER = 9_400_000_000_000_602L;
    private static final long TEMPLATE = 9_400_000_000_000_611L;
    private static final long AVAILABLE_1 = 9_400_000_000_000_621L;
    private static final long USED_1 = 9_400_000_000_000_631L;
    private static final long OTHERS_1 = 9_400_000_000_000_641L;
    private static final long LEDGER_1 = 9_400_000_000_000_651L;
    private static final long LEDGER_2 = 9_400_000_000_000_652L;

    private Fixture fixture;
    private CCouponController coupons;
    private CPointsController points;

    @BeforeEach
    void start() throws Exception {
        fixture = new Fixture();
        seed(fixture);
        coupons = new CCouponController(new CouponQueryApiImpl(fixture.source));
        points = new CPointsController(new PointsQueryApiImpl(fixture.source));
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static void seed(Fixture fixture) {
        JdbcTemplate jdbc = fixture.jdbc;
        jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                        + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                        + " VALUES(?,?,'ACTIVE',100,0,'2026-01-01 00:00:00.000','2026-12-31 23:59:59.000',?"
                        + ",0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                TEMPLATE, "新人立减券",
                "{\"amountOff\":\"15\",\"thresholdAmount\":\"99.5\",\"scopeSummary\":\"限新用户\"}");
        instance(jdbc, AVAILABLE_1, OWNER, "AVAILABLE", null, "2026-11-01 00:00:00.000");
        instance(jdbc, USED_1, OWNER, "USED", "2026-10-06 01:02:03.000", "2026-11-01 00:00:00.000");
        instance(jdbc, OTHERS_1, OTHER, "AVAILABLE", null, "2026-11-01 00:00:00.000");
        jdbc.update("INSERT INTO points_account(id,user_id,balance,version,created_at,updated_at)"
                + " VALUES(?,?,1280,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                9_400_000_000_000_615L, OWNER);
        jdbc.update("INSERT INTO points_ledger(id,user_id,biz_type,biz_id,order_id,delta,"
                        + "balance_after,request_id,created_at) VALUES(?,?,?,NULL,NULL,-500,1280,?,?)",
                LEDGER_1, OWNER, "REFUND_CLAWBACK", "req-1", "2026-10-06 05:00:00.000");
        jdbc.update("INSERT INTO points_ledger(id,user_id,biz_type,biz_id,order_id,delta,"
                        + "balance_after,request_id,created_at) VALUES(?,?,?,NULL,NULL,1780,1780,?,?)",
                LEDGER_2, OWNER, "ORDER_REWARD", "req-2", "2026-10-05 04:00:00.000");
    }

    private static void instance(JdbcTemplate jdbc, long id, long user, String status,
            String usedAt, String expireAt) {
        jdbc.update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
                        + "frozen_at,freeze_expire_at,used_at,original_expire_at,expire_at,version,"
                        + "created_at,updated_at) VALUES(?,?,?,?,'0',NULL,NULL,?,?,?,0,"
                        + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id, TEMPLATE, user, status, usedAt, expireAt, expireAt);
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(com.petplatform.boot.config.CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", Long.toString(OWNER), Instant.now(),
                        "138****0000", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    @Test
    void couponListDefaultsToAvailableBucket() {
        ApiResponse<Map<String, Object>> value =
                coupons.list(null, null, null, request("GET", "/api/v1/c/coupons", null));
        assertEquals("SUCCESS", value.code());
        assertEquals(1L, value.data().get("total"));
        assertEquals(1, value.data().get("page"));
        assertEquals(20, value.data().get("pageSize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) value.data().get("items");
        assertEquals("9400000000000621", items.get(0).get("couponId"));
        assertEquals("新人立减券", items.get(0).get("name"));
        // D1: amounts render as two-decimal strings; a missing label reads as null, never fails.
        assertEquals("15.00", items.get(0).get("amountOff"));
        assertEquals("99.50", items.get(0).get("thresholdAmount"));
        assertEquals("限新用户", items.get(0).get("scopeSummary"));
        assertNull(items.get(0).get("typeLabel"));
        assertEquals("2026-11-01", items.get(0).get("validTo"));
        assertEquals("AVAILABLE", items.get(0).get("status"));
        assertNull(items.get(0).get("usedAt"));
    }

    @Test
    void couponUsedBucketCarriesUsedAtInstant() {
        ApiResponse<Map<String, Object>> value = coupons.list(
                "USED", "1", "20", request("GET", "/api/v1/c/coupons", "status=USED&page=1&pageSize=20"));
        assertEquals(1L, value.data().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) value.data().get("items");
        assertEquals("2026-10-06T01:02:03.000Z", items.get(0).get("usedAt"));
    }

    @Test
    void couponDetailIsAntiEnumerationSafe() {
        ApiResponse<Map<String, Object>> own = coupons.detail(
                "9400000000000621", request("GET", "/api/v1/c/coupons/9400000000000621", null));
        assertEquals("9400000000000621", own.data().get("couponId"));

        // Foreign coupon and absent coupon both surface the same code.
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> coupons.detail("9400000000000641",
                        request("GET", "/api/v1/c/coupons/9400000000000641", null))).code());
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> coupons.detail("9400000000000999",
                        request("GET", "/api/v1/c/coupons/9400000000000999", null))).code());
        assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class,
                () -> coupons.detail("abc",
                        request("GET", "/api/v1/c/coupons/abc", null))).code());

        // No query string is accepted on the detail route.
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.detail("9400000000000621",
                        request("GET", "/api/v1/c/coupons/9400000000000621", "page=1"))).code());
    }

    @Test
    void couponListRejectsInvalidStatusAndUnknownOrMalformedPaging() {
        for (String status : new String[] {"FROZEN", "RISK_FROZEN", "available", "x"}) {
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> coupons.list(status, null, null,
                            request("GET", "/api/v1/c/coupons", "status=" + status))).code(),
                    status);
        }
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.list(null, null, null,
                        request("GET", "/api/v1/c/coupons", "foo=1"))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.list(null, "0", null,
                        request("GET", "/api/v1/c/coupons", "page=0"))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.list(null, null, "51",
                        request("GET", "/api/v1/c/coupons", "pageSize=51"))).code());
        // Duplicate parameter: the array overload registers both values in the servlet mock
        // (real containers fold repeated query keys the same way).
        MockHttpServletRequest duplicated = request("GET", "/api/v1/c/coupons", null);
        duplicated.addParameter("page", new String[] {"1", "2"});
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.list(null, null, null, duplicated)).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> coupons.list(null, "x", null,
                        request("GET", "/api/v1/c/coupons", "page=x"))).code());
    }

    @Test
    void pointsBalanceAndLedgerRenderSignedStringsInFixedOrder() {
        ApiResponse<Map<String, Object>> balance = points.balance(
                request("GET", "/api/v1/c/points/balance", null));
        assertEquals("1280", balance.data().get("balance"));

        ApiResponse<Map<String, Object>> ledger = points.ledger(
                null, null, request("GET", "/api/v1/c/points/ledger", null));
        assertEquals(2L, ledger.data().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ledger.data().get("items");
        // Newest first: the clawback line is negative, integers cross the wire as strings.
        assertEquals("9400000000000651", rows.get(0).get("ledgerId"));
        assertEquals("REFUND_CLAWBACK", rows.get(0).get("bizType"));
        assertEquals("-500", rows.get(0).get("delta"));
        assertEquals("1280", rows.get(0).get("balanceAfter"));
        assertEquals("2026-10-06T05:00:00.000Z", rows.get(0).get("createdAt"));
        assertEquals("9400000000000652", rows.get(1).get("ledgerId"));

        // No query string on the balance route; ledger only accepts paging.
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> points.balance(
                        request("GET", "/api/v1/c/points/balance", "page=1"))).code());
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                () -> points.ledger(null, null,
                        request("GET", "/api/v1/c/points/ledger", "status=AVAILABLE"))).code());
    }

    @Test
    void missingSessionIsUnauthorizedOnEveryRoute() {
        MockHttpServletRequest anonymous =
                new MockHttpServletRequest("GET", "/api/v1/c/coupons");
        assertEquals(CommonApiCodes.UNAUTHORIZED,
                assertThrows(ApiException.class, () -> coupons.list(null, null, null, anonymous)).code());
        MockHttpServletRequest anonymousBalance =
                new MockHttpServletRequest("GET", "/api/v1/c/points/balance");
        assertEquals(CommonApiCodes.UNAUTHORIZED,
                assertThrows(ApiException.class, () -> points.balance(anonymousBalance)).code());
    }

    @Test
    void sliceStaysDefaultOffUntilTheCSessionSwitchIsEnabled() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.config.CouponPointsReadConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CouponQueryApiImpl.class).isEmpty());
                    assertTrue(context.getBeansOfType(PointsQueryApiImpl.class).isEmpty());
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CCouponController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CCouponController.class).isEmpty());
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CPointsController.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(CPointsController.class).isEmpty());
                });
    }

    /** Fresh isolated real MySQL with the authoritative schema; dropped on close, even on failure. */
    private static final class Fixture implements AutoCloseable {
        private static final String PREFIX = "cpnpts_http_";

        private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private boolean created;

        private Fixture() throws Exception {
            String prefix = System.getenv().containsKey("CPNPTS_MYSQL_URL") ? "CPNPTS"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "CPNPTS";
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
                Path script = Objects.requireNonNull(root,
                        "Repository root with SQL06 was not found")
                        .resolve("docs/03-database/06-核心数据库Schema-v0.1.sql");
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(script), StandardCharsets.UTF_8));
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
                    throw new IllegalStateException("Refusing to drop unexpected database name: " + name);
                }
                admin.execute("DROP DATABASE `" + name + "`");
                created = false;
            }
        }
    }
}
