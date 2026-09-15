package com.petplatform.boot.auth;

import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.HutoolSnowflakeIdProvider;
import com.petplatform.id.core.JdbcSnowflakeNodeStore;
import com.petplatform.id.core.PreviousJvmExitVerifier;
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
 * AUTH-001 C-login HTTP slice over the real Boot stack with loopback HTTP, a real isolated
 * MySQL (Schema 06 + SQL 14 + SQL 25) and the isolated volatile Redis. The WeChat provider is
 * the fixed-code test double from the biz tests — no real WeChat chain is claimed. Codes:
 * {@code ok:<openid>}, {@code phone:<11-digit>}, other codes reject, {@code down*} is 503.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CAuthHttpTest {

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
        props.put("pet.auth.c.enabled", true);
        props.put("pet.auth.c.redis-host", fixture.redisHost);
        props.put("pet.auth.c.redis-port", fixture.redisPort);
        props.put("pet.auth.c.cache-prefix", fixture.prefix);
        try {
            // Command-line args outrank application.yml; plain properties() are defaults and
            // would lose against the yml's explicit pet.auth.c block.
            context = new SpringApplicationBuilder(PetPlatformApplication.class).properties(props)
                    .initializers(c -> {
                        var beans = (GenericApplicationContext) c;
                        beans.registerBean("qaCDataSource", DataSource.class, () -> fixture.source);
                        beans.registerBean("qaCHutool", SnowflakeIdGenerator.class, () -> fixture.ids);
                        beans.registerBean("qaCWechat", WechatSessionProvider.class,
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

    /** Same fixed-code double as the biz tests; it is the ONLY stand-in for the real provider. */
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

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> plain() { return Map.of("X-Request-Id", rid()); }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", rid());
    }

    record Attempt(String id, String token) { @Override public String toString() { return "Attempt[REDACTED]"; } }

    private Attempt attempt() throws Exception {
        Reply reply = send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"), plain());
        assertEquals(201, reply.status);
        assertEquals("PROVE_IDENTITY", reply.data().get("nextStep"));
        return new Attempt(String.valueOf(reply.data().get("attemptId")),
                String.valueOf(reply.data().get("attemptToken")));
    }

    private Map<String, String> attemptHeaders(Attempt attempt) {
        return Map.of("X-Auth-Attempt", attempt.token(), "X-Request-Id", rid());
    }

    /** Full login in one shot: identity + phone proof both supplied to wechat-login. */
    private Map<String, Object> login(String openid, String phone) throws Exception {
        Attempt attempt = attempt();
        Reply reply = send("POST", "/auth/wechat-login",
                Map.of("attemptId", attempt.id(), "wechatCode", "ok:" + openid,
                        "phoneCode", "phone:" + phone),
                attemptHeaders(attempt));
        assertEquals(200, reply.status);
        assertEquals("MINIAPP", reply.data().get("audience"));
        return reply.data();
    }

    @Test
    void wechatChainRunsThroughRealHttpFromAttemptToPetsAndProfile() throws Exception {
        Attempt attempt = attempt();
        Reply step = send("POST", "/auth/wechat-login",
                Map.of("attemptId", attempt.id(), "wechatCode", "ok:http-open-1"),
                attemptHeaders(attempt));
        assertEquals(200, step.status);
        assertEquals("VERIFY_PHONE", step.data().get("nextStep"));
        assertNull(step.data().get("accessToken"), "no SessionGrant before phone verification");
        Reply grantReply = send("POST", "/account/phone-binding",
                Map.of("phoneCode", "phone:13800002222", "attemptId", attempt.id()),
                attemptHeaders(attempt));
        assertEquals(200, grantReply.status);
        String token = String.valueOf(grantReply.data().get("accessToken"));
        String userId = String.valueOf(grantReply.data().get("userId"));

        Reply session = send("GET", "/auth/session", null, bearer(token));
        assertEquals(200, session.status);
        assertEquals(userId, session.data().get("userId"));
        assertEquals("MINIAPP", session.data().get("audience"));
        assertEquals("138****2222", session.data().get("phoneMasked"));

        assertEquals(200, send("GET", "/pets", null, bearer(token)).status);
        String requestId = rid();
        Map<String, Object> pet = new LinkedHashMap<>();
        pet.put("name", "豆豆");
        pet.put("petType", "DOG");
        pet.put("breedName", "柯基");
        pet.put("birthDate", "2023-05-01");
        pet.put("sex", "MALE");
        pet.put("weightKg", "12.50");
        pet.put("isDefault", true);
        // First create is 201; the same key replays the same receipt with 200 (23 §5).
        Map<String, String> writeHeaders = new HashMap<>();
        writeHeaders.put("Authorization", "Bearer " + token);
        writeHeaders.put("X-Request-Id", requestId);
        Reply first = send("POST", "/pets", pet, writeHeaders);
        assertEquals(201, first.status);
        String petId = String.valueOf(first.data().get("petId"));
        assertEquals("12.50", first.data().get("weightKg"), "weight stays a decimal string");
        Reply replay = send("POST", "/pets", pet, writeHeaders);
        assertEquals(200, replay.status);
        assertEquals(first.data(), replay.data());

        // petType is immutable after create: the update body must not carry it.
        Map<String, Object> update = new LinkedHashMap<>(pet);
        update.remove("petType");
        update.put("name", "豆豆二号");
        Reply updated = send("PUT", "/pets/" + petId, update, writeHeaders);
        assertEquals(200, updated.status);
        assertEquals("豆豆二号", updated.data().get("name"));
        assertEquals(200, send("GET", "/pets/" + petId, null, bearer(token)).status);
        assertEquals(1, ((List<?>) send("GET", "/pets", null, bearer(token)).envelope().get("data")).size());
        assertEquals(200, send("DELETE", "/pets/" + petId, null, bearer(token)).status);

        Reply profile = send("GET", "/profile", null, bearer(token));
        assertEquals(200, profile.status);
        assertEquals(userId, profile.data().get("userId"));
        assertEquals(false, profile.data().get("passwordEnabled"));
        Reply saved = send("PUT", "/profile", Map.of("nickname", "铲屎官"), writeHeaders);
        assertEquals(200, saved.status);
        assertEquals("铲屎官", saved.data().get("nickname"));
        assertEquals("铲屎官", send("GET", "/profile", null, bearer(token)).data().get("nickname"));

        assertEquals(200, send("POST", "/auth/logout", Map.of(), bearer(token)).status);
        assertEquals(200, send("POST", "/auth/logout", Map.of(), bearer(token)).status,
                "repeated logout stays safe");
        assertEquals(401, send("GET", "/auth/session", null, bearer(token)).status);
    }

    @Test
    void oneShotLoginAndSecondLoginReuseTheSameAccount() throws Exception {
        Map<String, Object> first = login("http-open-2", "13700003333");
        Map<String, Object> second = login("http-open-2", "13700003333");
        assertEquals(first.get("userId"), second.get("userId"));
        assertNotEquals(first.get("accessToken"), second.get("accessToken"));
        assertEquals(1, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_account", Integer.class));
    }

    @Test
    void invalidCredentialsAndProviderOutageMapExactly() throws Exception {
        Attempt attempt = attempt();
        Map<String, Object> body =
                Map.of("attemptId", attempt.id(), "wechatCode", "forged");
        assertEquals(401, send("POST", "/auth/wechat-login", body, attemptHeaders(attempt)).status);
        Map<String, String> noSecret = new HashMap<>(attemptHeaders(attempt));
        noSecret.remove("X-Auth-Attempt");
        assertEquals(401, send("POST", "/auth/wechat-login", body, noSecret).status);
        Map<String, String> wrongSecret = new HashMap<>(attemptHeaders(attempt));
        wrongSecret.put("X-Auth-Attempt", "wrong-secret");
        assertEquals(401, send("POST", "/auth/wechat-login", body, wrongSecret).status);
        Map<String, Object> unknownAttempt =
                Map.of("attemptId", "123456789012345678", "wechatCode", "ok:never");
        assertEquals(401, send("POST", "/auth/wechat-login", unknownAttempt, attemptHeaders(attempt)).status);
        Map<String, Object> outage =
                Map.of("attemptId", attempt.id(), "wechatCode", "down:now");
        assertEquals(503, send("POST", "/auth/wechat-login", outage, attemptHeaders(attempt)).status);
        assertEquals(400, send("POST", "/auth/attempts", Map.of("purpose", "SMS_LOGIN"), plain()).status);
        assertEquals(400, send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN", "x", 1), plain()).status);
        String requestId = rid();
        assertEquals(201, send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
                Map.of("X-Request-Id", requestId)).status);
        assertEquals(409, send("POST", "/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
                Map.of("X-Request-Id", requestId)).status);
    }

    @Test
    void businessRoutesNeedALiveSession() throws Exception {
        Map<String, Object> pet = Map.of("name", "孤儿", "petType", "CAT");
        assertEquals(401, send("POST", "/pets", pet, plain()).status);
        assertEquals(401, send("GET", "/pets", null, Map.of("Authorization", "Bearer garbage", "X-Request-Id", rid())).status);
        assertEquals(401, send("GET", "/profile", null, plain()).status);
        assertEquals(403, send("GET", "/not-implemented", null, plain()).status);
    }

    @Test
    void sessionExpiryEndsAccessWhileFrozenStillReadsButCannotWrite() throws Exception {
        String token = String.valueOf(login("http-open-4", "13600004444").get("accessToken"));
        long userId = Long.parseLong(String.valueOf(
                send("GET", "/auth/session", null, bearer(token)).data().get("userId")));
        // Real expiry: shrink this token's volatile session TTL and let it lapse.
        fixture.redisExpire(fixture.prefix + "session:" + sha256Hex(token), 1);
        Thread.sleep(1200);
        assertEquals(401, send("GET", "/auth/session", null, bearer(token)).status);

        String fresh = String.valueOf(login("http-open-4", "13600004444").get("accessToken"));
        fixture.jdbc.update("UPDATE user_account SET status='FROZEN' WHERE id=?", userId);
        Map<String, Object> pet = Map.of("name", "冰猫", "petType", "CAT");
        assertEquals(200, send("GET", "/auth/session", null, bearer(fresh)).status,
                "frozen accounts keep read access");
        assertEquals(200, send("GET", "/pets", null, bearer(fresh)).status);
        Reply write = send("POST", "/pets", pet, bearer(fresh));
        assertEquals(403, write.status);
        assertEquals("USER_FROZEN", write.envelope().get("code"));
        Reply profileWrite = send("PUT", "/profile", Map.of("nickname", "冻结昵称"), bearer(fresh));
        assertEquals(403, profileWrite.status);
        assertEquals("USER_FROZEN", profileWrite.envelope().get("code"));
    }

    @Test
    void idempotencyConflictAndForeignPetsStayRejected() throws Exception {
        String tokenA = String.valueOf(login("http-open-5a", "13500005555").get("accessToken"));
        String tokenB = String.valueOf(login("http-open-5b", "13500005556").get("accessToken"));
        String requestId = rid();
        Map<String, String> headersA = new HashMap<>();
        headersA.put("Authorization", "Bearer " + tokenA);
        headersA.put("X-Request-Id", requestId);
        Map<String, Object> pet = Map.of("name", "阿黄", "petType", "DOG");
        Reply created = send("POST", "/pets", pet, headersA);
        assertEquals(201, created.status);
        Map<String, Object> other = Map.of("name", "换参", "petType", "DOG");
        Reply conflict = send("POST", "/pets", other, headersA);
        assertEquals(409, conflict.status);
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.envelope().get("code"));
        // B never learns whether A's pet exists.
        Reply foreign = send("GET", "/pets/" + created.data().get("petId"), null, bearer(tokenB));
        assertEquals(404, foreign.status);
        assertEquals("PET_NOT_FOUND", foreign.envelope().get("code"));
    }

    @Test
    void phoneOwnedByAnotherAccountIs409OverHttp() throws Exception {
        login("http-open-6a", "13400006666");
        Attempt attempt = attempt();
        Reply conflict = send("POST", "/auth/wechat-login",
                Map.of("attemptId", attempt.id(), "wechatCode", "ok:http-open-6b",
                        "phoneCode", "phone:13400006666"),
                attemptHeaders(attempt));
        assertEquals(409, conflict.status);
        assertEquals("COMMON_CONFLICT", conflict.envelope().get("code"));
    }

    /**
     * Local copy of the admin HTTP fixture pattern: dedicated local MySQL, dedicated volatile
     * Redis, Schema 06 + SQL 14 + SQL 25, a virgin snowflake node, full teardown.
     */
    static final class HttpFixture implements AutoCloseable {
        final String name = "auth001c_http_" + UUID.randomUUID().toString().replace("-", "");
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
            directory = Files.createTempDirectory("auth001c-http-");
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
                }
                String evidence = "qa-c-auth-http-virgin:" + name;
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

        void redisExpire(String key, long seconds) {
            try (var redis = RedisClient.create("redis://" + redisHost + ":" + redisPort)) {
                var connection = redis.connect();
                try {
                    connection.sync().expire(key, seconds);
                } finally {
                    connection.close();
                    redis.shutdown();
                }
            }
        }

        /** Teardown-only retry over a fresh connection: local container proxies drop idle links. */
        void dropDatabase() {
            try {
                admin.execute("DROP DATABASE `" + name + "`");
            } catch (org.springframework.dao.RecoverableDataAccessException retry) {
                admin.execute("DROP DATABASE `" + name + "`");
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
