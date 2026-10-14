package com.petplatform.boot.adapter.web.c;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.user.biz.apiimpl.FavoriteApiImpl;
import com.petplatform.user.biz.apiimpl.FootprintApiImpl;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
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
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Contract 58 controller acceptance over isolated real MySQL (SQL06 user tables + SQL14): the
 * adapter concerns the kernel tests cannot see — the 201/200 create/replay split, the missing
 * or malformed X-Request-Id 400, the missing-session 401, unknown/duplicate/bound-violating
 * query parameter 400s, the uniform hidden-target 404 with the catalog wire codes, and the
 * exact wire projection (string ids, ISO instant timestamps, PageResult wrapping).
 */
class CFavoriteFootprintHttpTest {

    private static final long OWNER = 9_400_000_000_000_901L;
    private static final long OTHER = 9_400_000_000_000_902L;
    private static final long SERVICE_1 = 9_400_000_000_000_911L;
    private static final long SERVICE_HIDDEN = 9_400_000_000_000_912L;
    private static final long STORE_1 = 9_400_000_000_000_921L;

    private final AtomicLong ids = new AtomicLong(9_400_000_000_300_000L);
    private Fixture fixture;
    private CFavoriteController favorites;
    private CFootprintController footprints;

    @BeforeEach
    void start() throws Exception {
        fixture = new Fixture();
        fixture.jdbc.update(
                "INSERT INTO user_account (id,phone,nickname,status,created_at,updated_at)"
                        + " VALUES (?, '138****9001', '小李', 'ACTIVE', NOW(3), NOW(3))", OWNER);
        fixture.jdbc.update(
                "INSERT INTO user_account (id,phone,nickname,status,created_at,updated_at)"
                        + " VALUES (?, '138****9002', '小王', 'ACTIVE', NOW(3), NOW(3))", OTHER);
        FakeCatalog catalog = new FakeCatalog();
        favorites = new CFavoriteController(
                new FavoriteApiImpl(fixture.source, ids::incrementAndGet, catalog, catalog));
        footprints = new CFootprintController(
                new FootprintApiImpl(fixture.source, ids::incrementAndGet, catalog, catalog));
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /** The two catalog *-api surfaces; the same visibility split the kernel tests pin. */
    private static final class FakeCatalog implements ServiceQueryApi, MerchantStoreDisplayApi {
        @Override
        public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
            if (Long.parseLong(query.serviceId()) == SERVICE_HIDDEN) {
                throw new ApiException("SERVICE_NOT_FOUND", "service resource not found");
            }
            return new ServiceSnapshotDTO(query.serviceId(), "9400000000000931",
                    "9400000000000921", "宠物洗澡美容全套", "9400000000000941", "宠物美容",
                    new BigDecimal("80.00"), 60, null, null, null, "https://cover/s1.jpg",
                    null);
        }

        @Override
        public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.petplatform.service.api.dto.ServiceBookabilityDTO checkBookable(
                com.petplatform.service.api.query.ServiceBookabilityQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.petplatform.service.api.dto.ServiceSnapshotPageDTO getStoreServiceSnapshots(
                com.petplatform.service.api.query.StoreServiceSnapshotQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MerchantStoreDisplayDTO getDisplayStore(MerchantStoreDisplayQuery query) {
            return new MerchantStoreDisplayDTO("9400000000000931", query.storeId(),
                    "萌宠之家", "萌宠之家宠物店", "上海市浦东新区世纪大道100号", "121.5", "31.2",
                    "021****1234", "shanghai");
        }

        @Override
        public com.petplatform.merchant.api.dto.MerchantStoreDisplayPageDTO pageDisplayStores(
                com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery query) {
            throw new UnsupportedOperationException();
        }
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(CBearerSessionFilter.VIEW,
                new MiniSessionView("session-1", Long.toString(OWNER), Instant.now(),
                        "138****9001", "ACTIVE"));
        if (query != null) {
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                request.addParameter(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return request;
    }

    private static MockHttpServletRequest anonymous(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    void addAnswers201ThenReplay200WithTheSameFavoriteId() {
        String requestId = UUID.randomUUID().toString();
        var first = favorites.add("SERVICE", Long.toString(SERVICE_1), write(requestId));
        // Review-family create/replay split: the very first create answers 201; a same-requestId
        // replay answers 200 with the identical first receipt.
        assertEquals(201, first.getStatusCode().value());
        assertEquals("no-store", first.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("SUCCESS", first.getBody().code());
        assertEquals(Boolean.TRUE, first.getBody().data().get("created"));
        assertEquals(Boolean.TRUE, first.getBody().data().get("favorited"));
        assertEquals("9400000000000911", first.getBody().data().get("targetId"));
        String favoriteId = (String) first.getBody().data().get("favoriteId");

        var replay = favorites.add("SERVICE", Long.toString(SERVICE_1), write(requestId));
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(Boolean.FALSE, replay.getBody().data().get("created"));
        assertEquals(favoriteId, replay.getBody().data().get("favoriteId"));
        // A different requestId for an already-favorited target is a business duplicate: still
        // the current row, still 200, never a second row.
        var duplicate = favorites.add("SERVICE", Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        assertEquals(200, duplicate.getStatusCode().value());
        assertEquals(Boolean.FALSE, duplicate.getBody().data().get("created"));
        assertEquals(favoriteId, duplicate.getBody().data().get("favoriteId"));
        // ISO millisecond timestamps on the wire.
        assertTrue(((String) replay.getBody().data().get("favoritedAt")).matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void missingOrMalformedRequestIdIsAPlain400() {
        ApiException missing = assertThrows(ApiException.class, () -> favorites.add(
                "SERVICE", Long.toString(SERVICE_1), request("POST", "/api/v1/c/favorites/SERVICE/1", null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, missing.code());
        ApiException malformed = assertThrows(ApiException.class, () -> favorites.add(
                "SERVICE", Long.toString(SERVICE_1),
                write("not-a-uuid")));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, malformed.code());
        ApiException cancelWithoutId = assertThrows(ApiException.class, () -> favorites.cancel(
                "SERVICE", Long.toString(SERVICE_1), request("DELETE", "/x", null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, cancelWithoutId.code());
    }

    @Test
    void missingSessionIs401OnEveryRoute() {
        assertThrows(ApiException.class, () -> favorites.add(
                "SERVICE", Long.toString(SERVICE_1), anonymous("POST", "/x")));
        assertThrows(ApiException.class, () -> favorites.list(
                null, null, null, anonymous("GET", "/x")));
        assertThrows(ApiException.class, () -> footprints.list(
                anonymous("GET", "/x"), null, null));
        assertThrows(ApiException.class, () -> footprints.delete(
                Long.toString(SERVICE_1), anonymous("DELETE", "/x")));
    }

    @Test
    void hiddenTargetKeepsTheCatalogWireCode() {
        ApiException hidden = assertThrows(ApiException.class, () -> favorites.add(
                "SERVICE", Long.toString(SERVICE_HIDDEN),
                write(UUID.randomUUID().toString())));
        assertEquals("SERVICE_NOT_FOUND", hidden.code());
        ApiException badType = assertThrows(ApiException.class, () -> favorites.add(
                "POST", Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString())));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, badType.code());
        MockHttpServletRequest withQuery = write(UUID.randomUUID().toString());
        withQuery.addParameter("debug", "1");
        ApiException queryError = assertThrows(ApiException.class, () -> favorites.add(
                "SERVICE", Long.toString(SERVICE_1), withQuery));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, queryError.code());
    }

    @Test
    void listProjectionFilterAndParameterBounds() {
        favorites.add("SERVICE", Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        favorites.add("STORE", Long.toString(STORE_1),
                write(UUID.randomUUID().toString()));

        ApiResponse<Map<String, Object>> all = favorites.list(null, null, null,
                request("GET", "/api/v1/c/favorites", null));
        assertEquals(2L, all.data().get("total"));
        assertEquals(20, all.data().get("pageSize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) all.data().get("items");
        assertEquals("9400000000000921", items.get(0).get("targetId"));
        assertEquals("萌宠之家宠物店", items.get(0).get("title"));
        assertNull(items.get(0).get("salePrice"));
        assertEquals("9400000000000911", items.get(1).get("targetId"));
        assertEquals("宠物洗澡美容全套", items.get(1).get("title"));
        assertEquals("宠物美容", items.get(1).get("categoryLabel"));
        assertEquals("80.00", items.get(1).get("salePrice"));
        assertEquals("9400000000000921", items.get(1).get("storeId"));
        assertEquals("https://cover/s1.jpg", items.get(1).get("coverUrl"));

        ApiResponse<Map<String, Object>> stores = favorites.list("STORE", "1", "50",
                request("GET", "/api/v1/c/favorites", "targetType=STORE&page=1&pageSize=50"));
        assertEquals(1L, stores.data().get("total"));

        for (String bad : new String[] {"POST", "post", "", "COUPON"}) {
            ApiException invalid = assertThrows(ApiException.class, () -> favorites.list(
                    bad, null, null, request("GET", "/x", null)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
        }
        ApiException overSize = assertThrows(ApiException.class, () -> favorites.list(
                null, "1", "51", request("GET", "/x", null)));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, overSize.code());
        ApiException unknown = assertThrows(ApiException.class, () -> favorites.list(
                null, null, null, request("GET", "/x", "status=AVAILABLE")));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, unknown.code());
    }

    @Test
    void statusProbeAnswersNullWhenNotFavorited() {
        ApiResponse<Map<String, Object>> empty = favorites.status("STORE", Long.toString(STORE_1),
                request("GET", "/api/v1/c/favorites/STORE/1", null));
        assertNull(empty.data().get("favoriteId"));
        assertNull(empty.data().get("favoritedAt"));
        favorites.add("STORE", Long.toString(STORE_1),
                write(UUID.randomUUID().toString()));
        ApiResponse<Map<String, Object>> set = favorites.status("STORE", Long.toString(STORE_1),
                request("GET", "/api/v1/c/favorites/STORE/1", null));
        assertNotNull(set.data().get("favoriteId"));
        // Anti-enumeration note: the probe is owner-scoped — the same pair under another
        // session still reads favoriteId=null.
        MockHttpServletRequest other = request("GET", "/x", null);
        other.setAttribute(CBearerSessionFilter.VIEW,
                new MiniSessionView("session-2", Long.toString(OTHER), Instant.now(),
                        "138****9002", "ACTIVE"));
        ApiResponse<Map<String, Object>> foreign = favorites.status("STORE", Long.toString(STORE_1), other);
        assertNull(foreign.data().get("favoriteId"));
    }

    @Test
    void cancelRemovesAndSecondCancelStillSucceeds() {
        favorites.add("SERVICE", Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        ApiResponse<Map<String, Object>> removed = favorites.cancel("SERVICE",
                Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        assertEquals(Boolean.TRUE, removed.data().get("removed"));
        ApiResponse<Map<String, Object>> absent = favorites.cancel("SERVICE",
                Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        assertEquals(Boolean.FALSE, absent.data().get("removed"));
        assertEquals(0L, favorites.list(null, null, null,
                request("GET", "/x", null)).data().get("total"));
    }

    @Test
    void footprintRecordListAndDeleteOverTheWire() throws Exception {
        // Store first, then the service twice: the re-viewed service's refreshed
        // last_viewed_at must bubble it above the earlier store row.
        footprints.record("STORE", Long.toString(STORE_1),
                write(UUID.randomUUID().toString()));
        String requestId = UUID.randomUUID().toString();
        var first = footprints.record("SERVICE", Long.toString(SERVICE_1),
                write(requestId));
        assertEquals(201, first.getStatusCode().value());
        assertEquals(Boolean.TRUE, first.getBody().data().get("created"));
        Thread.sleep(10);
        var again = footprints.record("SERVICE", Long.toString(SERVICE_1),
                write(UUID.randomUUID().toString()));
        assertEquals(200, again.getStatusCode().value());
        assertEquals(Boolean.FALSE, again.getBody().data().get("created"));
        String footprintId = (String) first.getBody().data().get("footprintId");

        ApiResponse<Map<String, Object>> page = footprints.list(
                request("GET", "/api/v1/c/footprints", null), null, null);
        assertEquals(2L, page.data().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("items");
        // The re-viewed service sits at the head; the earlier store follows.
        assertEquals("9400000000000911", rows.get(0).get("targetId"));
        assertEquals("9400000000000921", rows.get(1).get("targetId"));

        ApiResponse<Map<String, Object>> deleted = footprints.delete(footprintId,
                write(UUID.randomUUID().toString()));
        assertEquals(Boolean.TRUE, deleted.data().get("deleted"));
        ApiResponse<Map<String, Object>> again2 = footprints.delete(footprintId,
                write(UUID.randomUUID().toString()));
        assertEquals(Boolean.FALSE, again2.data().get("deleted"));

        ApiException badPage = assertThrows(ApiException.class, () -> footprints.list(
                request("GET", "/x", "page=0"), "0", null));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, badPage.code());
        ApiException unknownQuery = assertThrows(ApiException.class, () -> footprints.list(
                request("GET", "/x", "targetType=SERVICE"), null, null));
        assertEquals(CommonApiCodes.INVALID_ARGUMENT, unknownQuery.code());
    }

    /** A session-bearing request with a terminal X-Request-Id, the write-route shape. */
    private static MockHttpServletRequest write(String requestId) {
        MockHttpServletRequest request = request("POST", "/api/v1/c/favorites/SERVICE/9400000000000911", null);
        request.addHeader("X-Request-Id", requestId);
        return request;
    }

    /** Fresh isolated real MySQL with the authoritative schema; dropped on close, even on failure. */
    private static final class Fixture implements AutoCloseable {
        private static final String PREFIX = "favtrack_http_";

        private final String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private boolean created;

        private Fixture() throws Exception {
            String prefix = System.getenv().containsKey("FAVTRACK_MYSQL_URL") ? "FAVTRACK"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "FAVTRACK";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33440/");
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
                Path sql06 = Objects.requireNonNull(root,
                        "Repository root with SQL06 was not found").resolve("docs/03-database/06-核心数据库Schema-v0.1.sql");
                Path sql14 = root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql");
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new EncodedResource(new FileSystemResource(sql06), StandardCharsets.UTF_8));
                    ScriptUtils.executeSqlScript(connection,
                            new EncodedResource(new FileSystemResource(sql14), StandardCharsets.UTF_8));
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
            return new DriverManagerDataSource(url, user, password);
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

    // Static context pins the default-off assembly: no switch, no controller bean.
    @Test
    void controllersStayDefaultOffWithoutTheAssemblySwitches() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.petplatform.boot.config.FavoriteFootprintConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(FavoriteApiImpl.class).isEmpty());
                    assertTrue(context.getBeansOfType(FootprintApiImpl.class).isEmpty());
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CFavoriteController.class)
                .run(context -> assertTrue(context.getBeansOfType(CFavoriteController.class).isEmpty()));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CFootprintController.class)
                .run(context -> assertTrue(context.getBeansOfType(CFootprintController.class).isEmpty()));
    }
}
