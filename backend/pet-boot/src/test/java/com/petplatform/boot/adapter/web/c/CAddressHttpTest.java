package com.petplatform.boot.adapter.web.c;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.HutoolSnowflakeIdProvider;
import com.petplatform.id.core.JdbcSnowflakeNodeStore;
import com.petplatform.id.core.SnowflakeProviderSettings;
import com.petplatform.user.biz.application.WechatSessionProvider;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract 59 address HTTP face over the real Boot stack with loopback HTTP, a real isolated
 * MySQL (Schema 06 + SQL 14 + SQL 25 + SQL 55) and the isolated volatile Redis. The WeChat
 * provider is the fixed-code test double from the auth slice — no real WeChat chain is claimed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CAddressHttpTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    HttpFixture fixture;
    ConfigurableApplicationContext context;
    String base;

    @BeforeAll
    void start() throws Exception {
        fixture = new HttpFixture();
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", 0);
        props.put("spring.flyway.enabled", false);
        props.put("spring.main.banner-mode", "off");
        props.put("spring.jmx.enabled", false);
        props.put("pet.auth.admin.enabled", false);
        try {
            // Command-line args outrank application.yml; plain properties() are defaults and
            // would lose against the yml's explicit pet.auth.c block.
            context = new SpringApplicationBuilder(PetPlatformApplication.class).properties(props)
                    .initializers(c -> {
                        var beans = (GenericApplicationContext) c;
                        beans.registerBean("qaAddressDataSource", DataSource.class, () -> fixture.source);
                        beans.registerBean("qaAddressHutool", SnowflakeIdGenerator.class, () -> fixture.ids);
                        beans.registerBean("qaAddressWechat", WechatSessionProvider.class,
                                FixedWechatProvider::new);
                    }).run("--pet.auth.c.enabled=true",
                            "--pet.auth.c.redis-host=" + fixture.redisHost,
                            "--pet.auth.c.redis-port=" + fixture.redisPort,
                            "--pet.auth.c.cache-prefix=" + fixture.prefix);
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
                    + "/api/v1/c";
        } catch (Exception failure) {
            if (context != null) context.close();
            fixture.close();
            throw failure;
        }
    }

    @AfterAll
    void stop() throws Exception {
        try {
            if (context != null) context.close();
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    /** Same fixed-code double as the auth tests; it is the ONLY stand-in for the real provider. */
    static final class FixedWechatProvider implements WechatSessionProvider {
        @Override public WechatIdentity exchangeIdentity(String code) {
            if (code.startsWith("down")) throw new ProviderUnavailable();
            if (!code.startsWith("ok:")) throw new ProofRejected();
            String[] parts = code.split(":");
            return new WechatIdentity("qa-app", parts[1], parts.length > 2 ? parts[2] : null);
        }
        @Override public String exchangePhone(String code) {
            if (code.startsWith("down")) throw new ProviderUnavailable();
            if (!code.startsWith("phone:")) throw new ProofRejected();
            return code.substring(6);
        }
    }

    record Reply(int status, Map<String, Object> envelope) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() { return (Map<String, Object>) envelope.get("data"); }
        @Override public String toString() { return "Reply[status=" + status + ",data=REDACTED]"; }
    }

    private Reply send(String method, String path, Object body, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = client.send(builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> envelope = json.readValue(response.body(), Map.class);
        assertTrue(envelope.get("traceId") instanceof String);
        if (response.statusCode() >= 400) {
            assertNull(envelope.get("data"));
        }
        return new Reply(response.statusCode(), envelope);
    }

    private static String rid() { return UUID.randomUUID().toString(); }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", rid());
    }

    /** Full login in one shot: identity + phone proof both supplied to wechat-login. */
    private String login(String openid, String phone) throws Exception {
        Reply attempt = send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
                Map.of("X-Request-Id", rid()));
        assertEquals(201, attempt.status);
        Map<String, String> attemptHeaders = Map.of(
                "X-Auth-Attempt", String.valueOf(attempt.data().get("attemptToken")),
                "X-Request-Id", rid());
        Reply reply = send("POST", "/auth/wechat-login",
                Map.of("attemptId", attempt.data().get("attemptId"), "wechatCode", "ok:" + openid,
                        "phoneCode", "phone:" + phone),
                attemptHeaders);
        assertEquals(200, reply.status);
        return String.valueOf(reply.data().get("accessToken"));
    }

    private static Map<String, Object> address(String name, String detail, boolean isDefault) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contactName", name);
        body.put("contactPhone", "13800001234");
        body.put("region", "上海市 浦东新区");
        body.put("detail", detail);
        if (isDefault) body.put("isDefault", true);
        return body;
    }

    @Test
    void fullCrudLifecycleWithReplayAndAntiEnumeration() throws Exception {
        String token = login("addr-open-1", "13800000001");

        // Anonymous has no session on any of the six routes.
        assertEquals(401, send("GET", "/addresses", null, Map.of()).status);
        assertEquals(401, send("POST", "/addresses", address("匿名", "匿名路1号", false),
                Map.of("X-Request-Id", rid())).status);

        // Create: first 201, same-key replay 200 with the identical receipt (23 §5).
        String requestId = rid();
        Map<String, String> writeHeaders = new HashMap<>();
        writeHeaders.put("Authorization", "Bearer " + token);
        writeHeaders.put("X-Request-Id", requestId);
        Map<String, Object> body = address("李小白", "祖冲之路887弄12号3楼", true);
        Reply first = send("POST", "/addresses", body, writeHeaders);
        assertEquals(201, first.status);
        assertEquals("SUCCESS", first.envelope().get("code"));
        String addressId = String.valueOf(first.data().get("addressId"));
        assertTrue(addressId.matches("[1-9][0-9]{0,18}"), "Snowflake id as decimal String");
        assertEquals(true, first.data().get("isDefault"));
        Reply replay = send("POST", "/addresses", body, writeHeaders);
        assertEquals(200, replay.status);
        assertEquals(first.data(), replay.data());
        assertEquals(1, ((List<?>) send("GET", "/addresses", null, bearer(token)).envelope().get("data")).size(),
                "replay did not create a second row");

        // Same key, different params: 409 IDEMPOTENCY_KEY_CONFLICT.
        Map<String, Object> divergent = address("改名", "别的路1号", false);
        assertEquals(409, send("POST", "/addresses", divergent, writeHeaders).status);
        assertEquals("IDEMPOTENCY_KEY_CONFLICT",
                send("POST", "/addresses", divergent, writeHeaders).envelope().get("code"));

        // Strict JSON: unknown field and explicit null are both 400.
        Map<String, Object> unknown = new LinkedHashMap<>(body);
        unknown.put("label", "家");
        assertEquals(400, send("POST", "/addresses", unknown, bearer(token)).status);
        Map<String, Object> explicitNull = new LinkedHashMap<>(body);
        explicitNull.put("contactName", null);
        assertEquals(400, send("POST", "/addresses", explicitNull, bearer(token)).status);
        Map<String, Object> badPhone = new LinkedHashMap<>(body);
        badPhone.put("contactPhone", "12345");
        assertEquals(400, send("POST", "/addresses", badPhone, bearer(token)).status);
        // Missing X-Request-Id on a write is a plain 400.
        assertEquals(400, send("POST", "/addresses", body, Map.of("Authorization", "Bearer " + token)).status);

        // Detail read returns the owner plaintext projection.
        Reply detail = send("GET", "/addresses/" + addressId, null, bearer(token));
        assertEquals(200, detail.status);
        assertEquals("李小白", detail.data().get("contactName"));
        assertEquals("13800001234", detail.data().get("contactPhone"));

        // Update: full field replace; isDefault omitted keeps the current flag.
        Map<String, Object> second = service2(token);
        Map<String, Object> update = address("李小白二号", "漕溪北路88号5楼501室", false);
        update.remove("isDefault");
        Reply updated = send("PUT", "/addresses/" + addressId, update, bearer(token));
        assertEquals(200, updated.status);
        assertEquals("李小白二号", updated.data().get("contactName"));
        assertEquals(true, updated.data().get("isDefault"), "omitted isDefault keeps the flag");

        // set-default (bodyless) flips the default atomically to the second row.
        String secondId = second.get("addressId") == null ? null : String.valueOf(second.get("addressId"));
        Reply flipped = send("POST", "/addresses/" + secondId + "/set-default", null, bearer(token));
        assertEquals(200, flipped.status);
        assertEquals(true, flipped.data().get("isDefault"));
        Reply firstAfter = send("GET", "/addresses/" + addressId, null, bearer(token));
        assertEquals(false, firstAfter.data().get("isDefault"), "single default per user");
        // List keeps the default first.
        List<?> listed = (List<?>) send("GET", "/addresses", null, bearer(token)).envelope().get("data");
        assertEquals(secondId, String.valueOf(((Map<?, ?>) listed.get(0)).get("addressId")));

        // Cross-user: another session gets the same 404 ADDRESS_NOT_FOUND for read and writes.
        String stranger = login("addr-open-2", "13800000002");
        assertEquals(404, send("GET", "/addresses/" + addressId, null, bearer(stranger)).status);
        assertEquals("ADDRESS_NOT_FOUND",
                send("GET", "/addresses/" + addressId, null, bearer(stranger)).envelope().get("code"));
        assertEquals(404, send("PUT", "/addresses/" + addressId, update, bearer(stranger)).status);
        assertEquals(404, send("DELETE", "/addresses/" + addressId, null, bearer(stranger)).status);
        assertEquals(404, send("POST", "/addresses/" + addressId + "/set-default", null,
                bearer(stranger)).status);
        // Unknown id for the owner is indistinguishable.
        assertEquals(404, send("GET", "/addresses/999999999", null, bearer(token)).status);

        // Delete is soft and idempotent; replays keep returning the first receipt.
        String deleteId = rid();
        Map<String, String> deleteHeaders = new HashMap<>();
        deleteHeaders.put("Authorization", "Bearer " + token);
        deleteHeaders.put("X-Request-Id", deleteId);
        Reply deleted = send("DELETE", "/addresses/" + addressId, null, deleteHeaders);
        assertEquals(200, deleted.status);
        assertEquals("DELETED", deleted.data().get("status"));
        Reply deleteReplay = send("DELETE", "/addresses/" + addressId, null, deleteHeaders);
        assertEquals(200, deleteReplay.status);
        assertEquals(deleted.data(), deleteReplay.data());
        assertEquals(404, send("GET", "/addresses/" + addressId, null, bearer(token)).status);
    }

    private Map<String, Object> service2(String token) throws Exception {
        return send("POST", "/addresses", address("王小明", "张江路100号", false), bearer(token)).data();
    }

    @Test
    void capOfTwentyActiveAddressesIsEnforced() throws Exception {
        String token = login("addr-open-cap", "13800000003");
        for (int index = 0; index < 20; index++) {
            Reply created = send("POST", "/addresses",
                    address("批量" + index, "批量路" + index + "号", false), bearer(token));
            assertEquals(201, created.status, "address " + index + " within the cap");
        }
        Reply over = send("POST", "/addresses", address("溢出", "溢出路1号", false), bearer(token));
        assertEquals(400, over.status);
        assertEquals("ADDRESS_LIMIT_EXCEEDED", over.envelope().get("code"));
        // Deleting one frees a slot again.
        List<?> listed = (List<?>) send("GET", "/addresses", null, bearer(token)).envelope().get("data");
        String victim = String.valueOf(((Map<?, ?>) listed.get(0)).get("addressId"));
        assertEquals(200, send("DELETE", "/addresses/" + victim, null, bearer(token)).status);
        assertEquals(201, send("POST", "/addresses", address("回填", "回填路1号", false),
                bearer(token)).status);
    }

    static final class HttpFixture implements AutoCloseable {
        // The volatile-store constructor enforces the auth001c namespace, so the fixture database
        // (and thus the cache prefix) must lead with auth001c like every other C HTTP fixture.
        final String name = "auth001c_addr58_" + UUID.randomUUID().toString().replace("-", "");
        final String prefix = name + ":";
        final String redisHost = required("AUTH_REDIS_HOST");
        final int redisPort = Integer.parseInt(required("AUTH_REDIS_PORT"));
        final Path directory;
        final DataSource source;
        final JdbcTemplate jdbc;
        final JdbcTemplate admin;
        HutoolSnowflakeIdProvider ids;
        boolean created;

        HttpFixture() throws Exception {
            String server = required("AUTH_MYSQL_URL");
            if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("Dedicated local MySQL required");
            }
            if (!Set.of("127.0.0.1", "localhost").contains(redisHost)) {
                throw new IllegalArgumentException("Dedicated local Redis required");
            }
            directory = Files.createTempDirectory("addr58-http-");
            source = source(server + name);
            jdbc = new JdbcTemplate(source);
            admin = new JdbcTemplate(source(server));
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try {
                if (!admin.queryForObject("SELECT VERSION()", String.class).startsWith("8.")) {
                    throw new IllegalStateException("Real MySQL 8 required");
                }
                Path root = root();
                try (var c = source.getConnection();
                     var files = Files.list(root.resolve("docs/03-database"))) {
                    Path idSchema = files.filter(p -> p.getFileName().toString().startsWith("25-")
                            && p.toString().endsWith(".sql")).findFirst().orElseThrow();
                    ScriptUtils.executeSqlScript(c, new FileSystemResource(idSchema));
                    ScriptUtils.executeSqlScript(c, new FileSystemResource(
                            root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")));
                    ScriptUtils.executeSqlScript(c, new FileSystemResource(
                            root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql")));
                    ScriptUtils.executeSqlScript(c, new FileSystemResource(
                            root.resolve("docs/03-database/55-User-Address-Schema-v0.1.sql")));
                }
                String evidence = "qa-address-http-virgin:" + name;
                jdbc.update("INSERT INTO snowflake_worker_state(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
                        + " VALUES(19,?,TRUE,?,NOW(3),NOW(3))", SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
                ids = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source),
                        new SnowflakeProviderSettings(19),
                        old -> {
                            if (!created || old.nodeId() != 19 || old.incarnation() != null
                                    || old.fence() != 0 || old.reservedThrough() != -1
                                    || !evidence.equals(old.initializationRef())) {
                                throw new IllegalStateException("Not this fixture's virgin node");
                            }
                        });
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (true) {
                    try {
                        ids.nextId();
                        break;
                    } catch (IllegalStateException e) {
                        if (!e.getMessage().contains("WARMING") || System.nanoTime() >= deadline) throw e;
                        Thread.sleep(20);
                    }
                }
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        static String required(String key) {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) {
                throw new IllegalStateException(key + " required; cannot skip real integration");
            }
            return v;
        }

        static Path root() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return Objects.requireNonNull(p);
        }

        static DataSource source(String url) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
                    + "&connectTimeout=1000&socketTimeout=5000",
                    System.getenv().getOrDefault("AUTH_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", ""));
        }

        /** Teardown-only retry over a fresh connection: local container proxies drop idle links. */
        void dropDatabase() {
            try {
                admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
            } catch (org.springframework.dao.RecoverableDataAccessException retry) {
                // A lost ACK may follow a successful DROP. Retry only this fixture's random database.
                admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }

        @Override
        public void close() {
            if (!created) return;
            try {
                var client = RedisClient.create("redis://" + redisHost + ":" + redisPort);
                try (var connection = client.connect()) {
                    for (String key : connection.sync().keys(prefix + "*")) connection.sync().del(key);
                } finally {
                    client.shutdown();
                }
            } catch (RuntimeException cleanup) {
                System.getLogger(HttpFixture.class.getName())
                        .log(System.Logger.Level.WARNING, "Redis cleanup unavailable");
            } finally {
                if (ids != null) ids.close();
                dropDatabase();
                created = false;
                try {
                    if (Files.isDirectory(directory)) {
                        try (var files = Files.list(directory)) {
                            for (Path file : files.toList()) Files.deleteIfExists(file);
                        }
                        Files.deleteIfExists(directory);
                    }
                } catch (Exception ignored) {
                    // temp dir cleanup is best effort
                }
            }
        }
    }
}
