package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.id.core.*;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.user.biz.application.WechatSessionProvider;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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

/**
 * FA-1/FA-2/FA-3/FA-4/FA-5 acceptance-face regression (2026-10-16, QA-005 findings): one boot
 * with the contract-63 trade face, the contract-65 ops face and the contract-45 merchant order
 * face all enabled proves the combinations the kernel-level suites cannot see.
 *
 * <p>FA-2: {@code pet.admin.ops.http.enabled=true} previously failed bean resolution (the
 * encyclopedia/coupon kernels were exposed twice, once per interface) — this context starts.
 * FA-4: trade + merchant faces previously collided on two ReservationRefundReleaseApi beans —
 * exactly one survives here. FA-1: the four disposal POSTs previously hit the security chain's
 * denyAll — they now reach their kernels (404 for unknown ids, never the chain 403). FA-3: the
 * ops read routes previously hit the same denyAll — they answer 200 through the chain, and the
 * authorization owner's collection gate knows the ops read codes. FA-5: the two hyphenated
 * trade read codes authorize (200/404 instead of the lexicon 400).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminFacesFixRegressionHttpTest {
    private static final String ORIGIN = "https://faces.example.invalid", PASSWORD = "Example_ONLY_92!";
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    private Fixture fixture;
    private ConfigurableApplicationContext context;
    private String base;

    @org.junit.jupiter.api.BeforeAll
    void start() throws Exception {
        fixture = new Fixture();
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", 0);
        props.put("spring.flyway.enabled", false);
        props.put("spring.main.banner-mode", "off");
        props.put("spring.jmx.enabled", false);
        props.put("pet.auth.admin.enabled", true);
        props.put("pet.auth.admin.migration-enabled", false);
        props.put("pet.auth.admin.origin", ORIGIN);
        props.put("pet.auth.admin.redis-host", fixture.redisHost);
        props.put("pet.auth.admin.redis-port", fixture.redisPort);
        props.put("pet.auth.admin.cache-prefix", fixture.adminPrefix);
        props.put("pet.auth.admin.key-id", "qa-key");
        props.put("pet.auth.admin.mac-key-base64", Base64.getEncoder().encodeToString(fixture.mac));
        props.put("pet.auth.admin.encryption-key-base64", Base64.getEncoder().encodeToString(fixture.aes));
        props.put("pet.auth.admin.audit-path", fixture.directory.resolve("audit.bin").toString());
        props.put("pet.auth.c.enabled", true);
        props.put("pet.auth.c.redis-host", fixture.redisHost);
        props.put("pet.auth.c.redis-port", fixture.redisPort);
        props.put("pet.auth.c.cache-prefix", fixture.prefix);
        props.put("pet.admin.trade.http.enabled", true);
        props.put("pet.admin.ops.http.enabled", true);
        // The shared store capacity guard (schedule protection foundation) backs the trade
        // disposition kernels and the merchant order face alike; the booking expiry kernel
        // provides the reservation release facts the late-refund service reads.
        props.put("pet.schedule.protection.enabled", true);
        props.put("pet.order.expiry.enabled", true);
        // The payment foundation reads booking user facts from the order creation kernel.
        props.put("pet.order.creation.enabled", true);
        // The payment foundation publishes through the transactional outbox; with the outbox on,
        // the refund.late subscription must be on too or the dispatcher boots with an empty
        // consumer list and fails (the composition constraint the E2E launcher documented).
        props.put("pet.outbox.enabled", true);
        props.put("pet.refund.late.enabled", true);
        props.put("pet.payment.lakala.channel-time-zone", "Asia/Shanghai");
        // The refund execution settings validate a non-blank request IP and notify URL.
        props.put("pet.payment.dispatch.request-ip", "127.0.0.1");
        props.put("pet.payment.dispatch.notify-url", "https://faces.example.invalid/notify");
        props.put("pet.order.merchant.enabled", true);
        props.put("pet.payment.foundation.enabled", true);
        props.put("pet.order.merchant.protection-key", Base64.getEncoder().encodeToString(key(17)));
        try {
            context = new SpringApplicationBuilder(PetPlatformApplication.class)
                    .properties(props)
                    .initializers(c -> {
                        var beans = (GenericApplicationContext) c;
                        beans.registerBean("facesFixDataSource", DataSource.class, () -> fixture.source);
                        beans.registerBean("facesFixIds", SnowflakeIdGenerator.class, () -> fixture.ids);
                        beans.registerBean("facesFixWechat",
                                WechatSessionProvider.class, CAuthHttpTest.FixedWechatProvider::new);
                        beans.registerBean("facesFixMerchantModeration",
                                com.petplatform.order.biz.application.MerchantOrderPorts.Moderation.class,
                                () -> value -> new com.petplatform.order.biz.application.MerchantOrderPorts.Approval(
                                        sha(value), "TEST_ONLY_MODERATION", true));
                        // The production refund channel needs real provider credentials; this
                        // regression never executes a refund (kernel 404s only), so the same
                        // test-domain stub seam the E2E launcher uses satisfies the wiring.
                        beans.registerBean("facesFixRefundChannel",
                                com.petplatform.payment.biz.application.PaymentRefundChannel.class,
                                () -> new com.petplatform.payment.biz.application.PaymentRefundChannel() {
                                    @Override public com.petplatform.payment.biz.application
                                            .PaymentRefundChannel.VerifiedResult submit(
                                            com.petplatform.payment.biz.application
                                                    .PaymentRefundChannel.RefundRequest request) {
                                        return receipt(request);
                                    }

                                    @Override public com.petplatform.payment.biz.application
                                            .PaymentRefundChannel.VerifiedResult query(
                                            com.petplatform.payment.biz.application
                                                    .PaymentRefundChannel.RefundRequest request) {
                                        return receipt(request);
                                    }

                                    private com.petplatform.payment.biz.application
                                            .PaymentRefundChannel.VerifiedResult receipt(
                                            com.petplatform.payment.biz.application
                                                    .PaymentRefundChannel.RefundRequest request) {
                                        long cents = request.amount().movePointRight(2).longValueExact();
                                        return new com.petplatform.payment.biz.application
                                                .PaymentRefundChannel.VerifiedResult("SUCCESS",
                                                request.refundNo(), "test-refund-" + request.refundNo(),
                                                cents, cents,
                                                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                                                "0".repeat(64));
                                    }
                                });
                    })
                    .run();
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            context.getBean(AdminAuthService.class)
                    .bootstrap("qa-owner", "QA Owner", PASSWORD.toCharArray(),
                            "HTTP isolated initialization");
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    @AfterAll
    void stop() {
        close();
    }

    private void close() {
        try {
            if (context != null) context.close();
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    // ------------------------------------------------------------------ FA-2 + FA-3

    @Test
    void opsFaceBootsAndServesReadsThroughTheSecurityChain() throws Exception {
        // FA-2: one kernel instance serves both encyclopedia faces — the previous double bean
        // exposure failed this exact boot at AdminOpsController constructor parameter 1.
        var queryKernels =
                context.getBeansOfType(com.petplatform.community.api.query.AdminEncyclopediaQueryApi.class);
        var commandKernels =
                context.getBeansOfType(com.petplatform.community.api.command.AdminEncyclopediaCommandApi.class);
        assertEquals(1, queryKernels.size());
        assertEquals(1, commandKernels.size());
        assertSame(queryKernels.values().iterator().next(), commandKernels.values().iterator().next());
        var couponKernels =
                context.getBeansOfType(com.petplatform.coupon.api.query.AdminCouponQueryApi.class);
        assertEquals(1, couponKernels.size());

        String token = adminToken();

        // The runtime switch view: pure boot environment, proves the ops GET permit + session.
        Reply settings = send("GET", "/api/v1/admin/settings/runtime", null, bearer(token));
        assertEquals(200, settings.status(), settings.redacted());
        assertEquals("ok", settings.envelope().get("message"));
        assertEquals(Boolean.TRUE, settings.data().get("readOnly"));
        assertTrue(((List<?>) settings.data().get("switches")).stream()
                .anyMatch(row -> "pet.admin.ops.http.enabled".equals(map(row).get("key"))
                        && Boolean.TRUE.equals(map(row).get("enabled"))));

        // Kernel-backed ops reads: the collection gate inside the authorization owner must know
        // the ops read codes (FA-3 second layer), not only the chain permit.
        assertEquals(200, send("GET", "/api/v1/admin/encyclopedia/categories", null, bearer(token)).status());
        Reply couponTemplates = send("GET", "/api/v1/admin/coupon-templates", null, bearer(token));
        assertEquals(200, couponTemplates.status(), couponTemplates.redacted());
        assertEquals(0, ((Number) couponTemplates.data().get("total")).intValue());
        Reply ledger = send("GET", "/api/v1/admin/points/ledger", null, bearer(token));
        assertEquals(200, ledger.status(), ledger.redacted());
        assertEquals(0, ((Number) ledger.data().get("total")).intValue());

        // The grant POST reaches its kernel through the chain (FA-1): with the 35号 union
        // opening pet.coupon.grant.enabled, the assembled kernel answers an unknown template
        // with its own NOT_FOUND — never the chain's COMMON_FORBIDDEN 403 (the pre-fix
        // answer), nor the fail-closed 503 of the previously unassembled channel.
        Reply grant = send("POST", "/api/v1/admin/coupon-instances/grant",
                Map.of("templateId", "930000000000000001", "userId", "930000000000000002"),
                bearer(token));
        assertEquals(404, grant.status(), grant.redacted());
        assertEquals("COMMON_NOT_FOUND", grant.envelope().get("code"));

        // Anonymous still fails closed at the session boundary, not the chain.
        Reply anonymous = send("GET", "/api/v1/admin/settings/runtime", null, Map.of());
        assertEquals(401, anonymous.status());
    }

    // ----------------------------------------------------------------------- FA-4

    @Test
    void merchantAndTradeFacesShareOneReleaseKernel() {
        // FA-4: trade.http + order.merchant coexist on one ReservationRefundReleaseApi bean —
        // the pre-fix collision failed merchantRefundProjection construction at startup.
        assertEquals(1, context.getBeansOfType(ReservationRefundReleaseApi.class).size());
    }

    // ----------------------------------------------------------------------- FA-1

    @Test
    void disposalPostsReachTheirKernelsInsteadOfTheChainDeny() throws Exception {
        String token = adminToken();
        // Unknown ids: each kernel answers its own NOT_FOUND — proof the request crossed the
        // security chain (pre-fix every one of these was the chain's 403 COMMON_FORBIDDEN).
        Reply disable = send("POST", "/api/v1/admin/merchants/930000000000000071/disable",
                Map.of("reason", "资质复核不通过，运营处置下线"), bearer(token));
        assertEquals(404, disable.status(), disable.redacted());
        assertEquals("COMMON_NOT_FOUND", disable.envelope().get("code"));

        Reply abnormalClose = send("POST", "/api/v1/admin/orders/930000000000000072/abnormal-close",
                Map.of("reasonCode", "OTHER", "reason", "商家无法履约，运营异常关单"), bearer(token));
        assertEquals(404, abnormalClose.status(), abnormalClose.redacted());
        assertEquals("COMMON_NOT_FOUND", abnormalClose.envelope().get("code"));

        Reply channelQuery = send("POST", "/api/v1/admin/refunds/930000000000000073/channel-query",
                null, bearer(token));
        assertEquals(404, channelQuery.status(), channelQuery.redacted());
        assertEquals("COMMON_NOT_FOUND", channelQuery.envelope().get("code"));

        Reply retry = send("POST", "/api/v1/admin/refunds/930000000000000073/retry",
                null, bearer(token));
        assertEquals(404, retry.status(), retry.redacted());
        assertEquals("COMMON_NOT_FOUND", retry.envelope().get("code"));

        // The chain permit relays the route; the session boundary still rejects anonymous calls.
        Reply anonymous = send("POST", "/api/v1/admin/merchants/930000000000000071/disable",
                Map.of("reason", "资质复核不通过，运营处置下线"), Map.of());
        assertEquals(401, anonymous.status());
    }

    // ----------------------------------------------------------------------- FA-5

    @Test
    void hyphenatedActionCodesAuthorizeTheTradeReadRoutes() throws Exception {
        String token = adminToken();
        // Collection entry check with order.verification-log.read — pre-fix a 400 for everyone.
        Reply verifications = send("GET", "/api/v1/admin/trade/verifications", null, bearer(token));
        assertEquals(200, verifications.status(), verifications.redacted());
        assertEquals(0, ((Number) verifications.data().get("total")).intValue());
        // Per-resource check with the same code: unknown row stays the business 404, not 400.
        Reply absentVerification = send("GET", "/api/v1/admin/trade/verifications/930000000000000099",
                null, bearer(token));
        assertEquals(404, absentVerification.status(), absentVerification.redacted());

        // order.status-log.read over a real order row — the route that broke the order-detail
        // console for every operator.
        fixture.jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,"
                + "service_id,pet_id,reservation_id,order_stage,fulfillment_type,original_amount,"
                + "pay_amount,appointment_start_at,appointment_end_at,created_at,updated_at,version)"
                + " VALUES(930001,930001,930010,930020,930021,930030,930040,930050,'COMPLETED',"
                + "'IN_STORE',60.00,60.00,'2026-10-15 08:00:00.000','2026-10-15 09:00:00.000',"
                + "NOW(3),NOW(3),3)");
        Reply statusLogs = send("GET", "/api/v1/admin/trade/orders/930001/status-logs", null, bearer(token));
        assertEquals(200, statusLogs.status(), statusLogs.redacted());
        assertEquals(0, ((Number) statusLogs.data().get("total")).intValue());
    }

    // -------------------------------------------------------------------- support

    private Reply send(String method, String path, Object body, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20));
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> envelope = json.readValue(response.body(), Map.class);
        return new Reply(response.statusCode(), envelope, response.headers());
    }

    private Attempt attempt() throws Exception {
        Map<String, String> headers = Map.of(
                "Origin", ORIGIN, "X-Request-Id", UUID.randomUUID().toString());
        Reply response = send("POST", "/api/v1/admin/auth/attempts", Map.of(), headers);
        assertEquals(201, response.status(), response.redacted());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Attempt(String.valueOf(response.data().get("attemptId")),
                String.valueOf(response.data().get("attemptToken")), cookie);
    }

    private String adminToken() throws Exception {
        Attempt attempt = attempt();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Auth-Attempt", attempt.token());
        headers.put("Cookie", attempt.cookie());
        headers.put("Origin", ORIGIN);
        headers.put("X-Request-Id", UUID.randomUUID().toString());
        Reply login = send("POST", "/api/v1/admin/auth/login",
                Map.of("attemptId", attempt.id(), "account", "qa-owner", "password", PASSWORD),
                headers);
        assertEquals(200, login.status(), login.redacted());
        return String.valueOf(login.data().get("accessToken"));
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token, "X-Request-Id", UUID.randomUUID().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object row) {
        return (Map<String, Object>) row;
    }

    private static byte[] key(int seed) {
        byte[] value = new byte[32];
        new Random(seed).nextBytes(value);
        return value;
    }

    private static String sha(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Attempt(String id, String token, String cookie) {}

    private record Reply(int status, Map<String, Object> envelope, java.net.http.HttpHeaders headers) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) envelope.get("data");
        }

        String redacted() {
            return status + " " + envelope.get("code");
        }
    }

    /** Local fixture: fresh database with the auth, core, idempotency, baike and disposition schemas. */
    static final class Fixture implements AutoCloseable {
        final String name = "auth001_faces_" + UUID.randomUUID().toString().replace("-", "");
        final String prefix = name + ":";
        final String adminPrefix = name + "_admin:";
        final String redisHost = required("AUTH_REDIS_HOST");
        final int redisPort = Integer.parseInt(required("AUTH_REDIS_PORT"));
        final byte[] mac = bytes(3), aes = bytes(7);
        final Path directory;
        final DataSource source;
        final JdbcTemplate jdbc, admin;
        HutoolSnowflakeIdProvider ids;
        boolean created;

        Fixture() throws Exception {
            String server = required("AUTH_MYSQL_URL");
            if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException("Dedicated local MySQL required");
            if (!Set.of("127.0.0.1", "localhost").contains(redisHost))
                throw new IllegalArgumentException("Dedicated local Redis required");
            directory = Files.createTempDirectory("faces-fix-");
            source = source(server + name);
            jdbc = new JdbcTemplate(source);
            admin = new JdbcTemplate(source(server));
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try {
                if (!admin.queryForObject("SELECT VERSION()", String.class).startsWith("8."))
                    throw new IllegalStateException("Real MySQL 8 required");
                Path root = root();
                try (var connection = source.getConnection();
                        var files = Files.list(root.resolve("docs/03-database"))) {
                    Path idSchema = files.filter(p -> p.getFileName().toString().startsWith("25-")
                            && p.toString().endsWith(".sql")).findFirst().orElseThrow();
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(idSchema));
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                            root.resolve("docs/03-database/26-Admin-Auth-Schema-v0.1.sql")));
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                            root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")));
                    // The additive schema chain in the dependency order the E2E launcher proved:
                    // each later schema ALTERs tables created by earlier ones (e.g. 63号 rebuilds
                    // the refund_execution check constraint last redefined by 49号).
                    for (String schema : List.of(
                            "13-Async-Infra-Schema-v0.1.sql",
                            // The grant route admits through the 14号 shared command_idempotency
                            // binding once the 35号 union assembles the grant channel.
                            "14-Command-Idempotency-Schema-v0.1.sql",
                            "31-Private-Asset-Schema-v0.1.sql",
                            "33-Service-Write-Schema-v0.1.sql",
                            "35-Merchant-Staff-Audit-Schema-v0.1.sql",
                            "52-Merchant-Staff-Identity-Schema-v0.1.sql",
                            "54-Merchant-Staff-Binding-Schema-v0.1.sql",
                            "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                            "38-Booking-Create-Schema-v0.1.sql",
                            "39-Booking-Expiry-Schema-v0.1.sql",
                            "40-Payment-Foundation-Schema-v0.1.sql",
                            "41-Payment-Dispatch-Schema-v0.1.sql",
                            "42-Late-Refund-Execution-Schema-v0.1.sql",
                            "43-Payment-Refund-Dispatch-Schema-v0.1.sql",
                            "44-Late-Refund-Order-Projection-Schema-v0.1.sql",
                            "45-Merchant-Order-Actions-Schema-v0.1.sql",
                            "46-Order-Reschedule-Schema-v0.1.sql",
                            "47-Verification-Credential-Schema-v0.1.sql",
                            "48-Verification-Completion-Schema-v0.1.sql",
                            "49-Refund-Application-Schema-v0.1.sql",
                            "50-AfterSale-Workflow-Schema-v0.1.sql",
                            "51-AfterSale-Http-Schema-v0.1.sql",
                            "63-Admin-Trade-Disposition-Schema-v0.1.sql",
                            "60-Points-Task-Schema-v0.1.sql",
                            "62-Baike-Content-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                                root.resolve("docs/03-database/" + schema)));
                    }
                }
                String evidence = "qa-faces-fix-virgin:" + name;
                jdbc.update("INSERT INTO"
                        + " snowflake_worker_state(node_id,format_identity,enabled,initialization_ref,"
                        + "created_at,updated_at) VALUES(42,?,TRUE,?,NOW(3),NOW(3))",
                        SnowflakeProviderSettings.FORMAT_IDENTITY, evidence);
                ids = new HutoolSnowflakeIdProvider(new JdbcSnowflakeNodeStore(source),
                        new SnowflakeProviderSettings(42), old -> {
                            if (!created || old.nodeId() != 42 || old.incarnation() != null
                                    || old.fence() != 0 || old.reservedThrough() != -1
                                    || !evidence.equals(old.initializationRef()))
                                throw new IllegalStateException("Not this fixture's virgin node");
                        });
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (true) {
                    try {
                        ids.nextId();
                        break;
                    } catch (IllegalStateException e) {
                        if (!e.getMessage().contains("WARMING") || System.nanoTime() >= deadline)
                            throw e;
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
            if (v == null || v.isBlank())
                throw new IllegalStateException(key + " required; cannot skip real integration");
            return v;
        }

        static byte[] bytes(int n) {
            byte[] b = new byte[32];
            Arrays.fill(b, (byte) n);
            return b;
        }

        static Path root() {
            Path p = Path.of("").toAbsolutePath();
            while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
            return Objects.requireNonNull(p);
        }

        static DataSource source(String url) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
                            + "&connectTimeout=1000&socketTimeout=15000",
                    System.getenv().getOrDefault("AUTH_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", ""));
        }

        public void close() {
            if (!created) return;
            try {
                var client = RedisClient.create("redis://" + redisHost + ":" + redisPort);
                try (var connection = client.connect()) {
                    for (String key : connection.sync().keys(prefix + "*")) connection.sync().del(key);
                    for (String key : connection.sync().keys(adminPrefix + "*")) connection.sync().del(key);
                } finally {
                    client.shutdown();
                }
            } catch (RuntimeException ignored) {
                // Redis cleanup best-effort; the database drop below is the durable teardown.
            } finally {
                if (ids != null) ids.close();
                admin.execute("DROP DATABASE `" + name + "`");
                created = false;
                try (var files = Files.list(directory)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                    Files.deleteIfExists(directory);
                } catch (Exception ignored) {
                    // temp dir best-effort
                }
            }
        }
    }
}
