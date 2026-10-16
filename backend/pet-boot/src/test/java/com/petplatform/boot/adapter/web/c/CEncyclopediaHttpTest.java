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
 * Contract 62 encyclopedia HTTP face over the real Boot stack with loopback HTTP, a real
 * isolated MySQL (Schema 06 + SQL 25 + SQL 62 seed) and the isolated volatile Redis. The
 * WeChat provider is the fixed-code test double from the auth slice — the baike routes
 * themselves are anonymous-browse GETs (PRD "所有用户；未登录用户可浏览", STR-D8 analog):
 * anonymous passes, a carried invalid bearer still 401s, non-GET stays denied.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CEncyclopediaHttpTest {

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
            context = new SpringApplicationBuilder(PetPlatformApplication.class).properties(props)
                    .initializers(c -> {
                        var beans = (GenericApplicationContext) c;
                        beans.registerBean("qaEncyclopediaDataSource", DataSource.class, () -> fixture.source);
                        beans.registerBean("qaEncyclopediaHutool", SnowflakeIdGenerator.class, () -> fixture.ids);
                        beans.registerBean("qaEncyclopediaWechat", WechatSessionProvider.class,
                                FixedWechatProvider::new);
                    }).run("--pet.auth.c.enabled=true",
                            "--pet.encyclopedia.query.enabled=true",
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

    record Reply(int status, Map<String, Object> envelope, Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() { return (Map<String, Object>) envelope.get("data"); }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items() { return (List<Map<String, Object>>) data().get("items"); }
        String code() { return String.valueOf(envelope.get("code")); }
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null ? null : values.get(0);
        }
        @Override public String toString() { return "Reply[status=" + status + ",data=REDACTED]"; }
    }

    private Reply send(String method, String path, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        headers.forEach(builder::header);
        var response = client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> envelope = response.body() == null || response.body().isBlank()
                ? new LinkedHashMap<>()
                : json.readValue(response.body(), Map.class);
        if (response.statusCode() < 400) {
            assertTrue(envelope.get("traceId") instanceof String);
        }
        return new Reply(response.statusCode(), envelope, response.headers().map());
    }

    @Test void anonymousBrowseReadsDictionaryAndList() throws Exception {
        Reply categories = send("GET", "/encyclopedia/categories", Map.of());
        assertEquals(200, categories.status);
        assertEquals("no-store", categories.header("Cache-Control"));
        List<Map<String, Object>> items = categories.items();
        assertEquals(5, items.size());
        assertEquals("DOG", items.get(0).get("categoryCode"));
        assertEquals("狗狗", items.get(0).get("categoryName"));
        assertEquals("INSECT", items.get(4).get("categoryCode"));

        Reply dogs = send("GET", "/encyclopedia/breeds?categoryCode=DOG&pageSize=20", Map.of());
        assertEquals(200, dogs.status);
        assertEquals(8, ((Number) dogs.data().get("total")).longValue());
        assertEquals(List.of("贵宾犬", "金毛寻回犬", "比熊犬", "威尔士柯基犬", "拉布拉多寻回犬", "博美犬", "柴犬", "法国斗牛犬"),
                dogs.items().stream().map(row -> row.get("name")).toList());
        // Cards carry list fields but never the detail-only rich bodies.
        assertFalse(dogs.items().get(0).containsKey("descriptionHtml"));

        // Default category is DOG when the parameter is absent (PRD 默认值 狗狗).
        Reply defaults = send("GET", "/encyclopedia/breeds", Map.of());
        assertEquals(8, ((Number) defaults.data().get("total")).longValue());

        // Keyword matches an alias (PRD: 别名参与搜索匹配).
        Reply searched = send("GET", "/encyclopedia/breeds?keyword=%E6%B3%B0%E8%BF%AA", Map.of());
        assertEquals(1, ((Number) searched.data().get("total")).longValue());
        assertEquals("贵宾犬", searched.items().get(0).get("name"));
    }

    @Test void offlineDetailAnswers404AndUnknownParameters400() throws Exception {
        String breedId = String.valueOf(send("GET", "/encyclopedia/breeds?keyword=%E8%B4%B5%E5%AE%BE", Map.of())
                .items().get(0).get("breedId"));

        Reply detail = send("GET", "/encyclopedia/breeds/" + breedId, Map.of());
        assertEquals(200, detail.status);
        assertEquals("贵宾犬", detail.data().get("name"));
        assertTrue(String.valueOf(detail.data().get("descriptionHtml")).contains("起源历史"));
        assertEquals(3, ((List<?>) detail.data().get("facts")).size());
        assertEquals(3, ((List<?>) detail.data().get("related")).size());
        // Related never contains the current breed and follows the curated order.
        List<String> relatedNames = ((List<Map<String, Object>>) detail.data().get("related")).stream()
                .map(row -> String.valueOf(row.get("name"))).toList();
        assertFalse(relatedNames.contains("贵宾犬"));
        assertEquals("金毛寻回犬", relatedNames.get(0));

        fixture.jdbc.update("UPDATE encyclopedia_breed SET status='OFFLINE' WHERE id=" + Long.parseLong(breedId));
        Reply offline = send("GET", "/encyclopedia/breeds/" + breedId, Map.of());
        assertEquals(404, offline.status);
        assertEquals("BREED_NOT_FOUND", offline.code());
        assertNull(offline.data());
        Reply missing = send("GET", "/encyclopedia/breeds/999", Map.of());
        assertEquals(404, missing.status);
        assertEquals("BREED_NOT_FOUND", missing.code());

        Reply badParam = send("GET", "/encyclopedia/breeds?city=shanghai", Map.of());
        assertEquals(400, badParam.status);
        Reply badPage = send("GET", "/encyclopedia/breeds?page=0", Map.of());
        assertEquals(400, badPage.status);
        Reply badCategory = send("GET", "/encyclopedia/breeds?categoryCode=FISH", Map.of());
        assertEquals(400, badCategory.status);
    }

    @Test void anonymousPassesButCarriedInvalidBearerStays401AndNonGetDenied() throws Exception {
        // Anonymous: passes (no session attribute, visibility subject-independent).
        assertEquals(200, send("GET", "/encyclopedia/categories", Map.of()).status);
        // Carried garbage bearer: the optional-session filter never masks a bad credential.
        Reply invalid = send("GET", "/encyclopedia/categories",
                Map.of("Authorization", "Bearer not-a-real-token"));
        assertEquals(401, invalid.status);
        assertEquals("COMMON_UNAUTHORIZED", invalid.code());
        // Non-GET stays denied by the chain even with the slice assembled.
        Reply post = send("POST", "/encyclopedia/breeds", Map.of());
        assertEquals(403, post.status);
    }

    /**
     * Same fixture shape as the auth/address C HTTP tests: dedicated local MySQL + Redis from
     * the environment, throwaway database per class, auth001c cache-prefix namespace.
     */
    static final class HttpFixture implements AutoCloseable {
        final String name = "auth001c_baike62_" + UUID.randomUUID().toString().replace("-", "");
        final String prefix = name + ":";
        final String redisHost = required("AUTH_REDIS_HOST");
        final int redisPort = Integer.parseInt(required("AUTH_REDIS_PORT"));
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
                            root.resolve("docs/03-database/62-Baike-Content-Schema-v0.1.sql")));
                }
                String evidence = "qa-baike-http-virgin:" + name;
                jdbc.update("INSERT INTO snowflake_worker_state(node_id,format_identity,enabled,initialization_ref,created_at,updated_at)"
                        + " VALUES(21,?,TRUE,?,NOW(3),NOW(3))", SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
                ids = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source),
                        new SnowflakeProviderSettings(21),
                        old -> {
                            if (!created || old.nodeId() != 21 || old.incarnation() != null
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

        void dropDatabase() {
            try {
                admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
            } catch (org.springframework.dao.RecoverableDataAccessException retry) {
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
            }
        }
    }
}
