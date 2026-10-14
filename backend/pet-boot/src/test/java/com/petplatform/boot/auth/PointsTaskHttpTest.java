package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract-58 acceptance over the real HTTP stack with both faces assembled (pet.auth.c +
 * pet.auth.admin + pet.points.task(.http).enabled): the C sign-in/task/referral family with
 * strict bodies, terminal X-Request-Id idempotency and the daily-conflict 409, plus the admin
 * config GET/PUT pair with the full-put validation and replay/conflict semantics. The order
 * first-payment fact arrives through a stub OrderQueryApi bean so the fail-closed 503 shape is
 * observable when the order read side is not assembled at all.
 */
class PointsTaskHttpTest {
  private static final String ORIGIN = "https://points.example.invalid";
  private static final String PASSWORD = "Example_ONLY_93!";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final byte[] adminMac = key(3), adminAes = key(7);
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    try (var connection = db.source.getConnection()) {
      for (String file : List.of("26-Admin-Auth-Schema-v0.1.sql", "58-Points-Task-Schema-v0.1.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
      }
    }
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", 0);
    props.put("spring.flyway.enabled", false);
    props.put("spring.main.banner-mode", "off");
    props.put("spring.jmx.enabled", false);
    props.put("pet.auth.c.enabled", true);
    props.put("pet.auth.c.redis-host", db.redisHost);
    props.put("pet.auth.c.redis-port", db.redisPort);
    props.put("pet.auth.c.cache-prefix", db.prefix);
    props.put("pet.auth.admin.enabled", true);
    props.put("pet.auth.admin.migration-enabled", false);
    props.put("pet.auth.admin.origin", ORIGIN);
    props.put("pet.auth.admin.redis-host", db.redisHost);
    props.put("pet.auth.admin.redis-port", db.redisPort);
    props.put("pet.auth.admin.cache-prefix", db.name + "_admin:");
    props.put("pet.auth.admin.key-id", "qa-key");
    props.put("pet.auth.admin.mac-key-base64", Base64.getEncoder().encodeToString(adminMac));
    props.put("pet.auth.admin.encryption-key-base64", Base64.getEncoder().encodeToString(adminAes));
    props.put("pet.auth.admin.audit-path", db.directory.resolve("points-audit.bin").toString());
    props.put("pet.points.task.enabled", true);
    props.put("pet.points.task.http.enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .properties(props)
              .initializers(
                  c -> {
                    var beans = (GenericApplicationContext) c;
                    beans.registerBean(
                        "pointsDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("pointsIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "pointsWechat", WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                  })
              .run(
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + db.redisHost,
                  "--pet.auth.c.redis-port=" + db.redisPort,
                  "--pet.auth.c.cache-prefix=" + db.prefix,
                  "--pet.points.task.enabled=true",
                  "--pet.points.task.http.enabled=true");
      base =
          "http://127.0.0.1:"
              + context.getEnvironment().getProperty("local.server.port")
              + "/api/v1";
      context
          .getBean(AdminAuthService.class)
          .bootstrap(
              "qa-points", "QA Points", PASSWORD.toCharArray(),
              "Isolated points task acceptance");
    } catch (Exception failure) {
      if (context != null) context.close();
      db.close();
      throw failure;
    }
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (context != null) context.close();
    } finally {
      if (db != null) db.close();
    }
  }

  @Test
  void cFaceSignInTasksReferralStrictBodiesAndIdempotency() throws Exception {
    Map<String, Object> inviter = consumerLogin("points-inviter", "13800007701");
    Map<String, Object> invitee = consumerLogin("points-invitee", "13800007702");
    String inviterToken = str(inviter, "accessToken");
    String inviteeToken = str(invitee, "accessToken");

    assertEquals(401, send("GET", "/c/points/sign-in/status", null, Map.of()).status());
    assertEquals(400, send("GET", "/c/points/tasks?all=1", null, bearer(inviterToken)).status());

    Reply status = send("GET", "/c/points/sign-in/status", null, bearer(inviterToken));
    assertEquals(200, status.status());
    assertEquals(Boolean.FALSE, status.data().get("signedToday"));
    assertEquals(0, status.data().get("streak"));
    assertEquals("5", status.data().get("dailyPoints"));
    assertEquals(7, ((List<?>) status.data().get("week")).size());
    assertEquals("no-store", status.raw().headers().firstValue("Cache-Control").orElseThrow());

    // Strict empty-object body surface on the sign-in command.
    String requestId = UUID.randomUUID().toString();
    assertEquals(400, sendRaw("POST", "/c/points/sign-in", "{\"points\":1}",
        bearer(inviterToken, requestId)).status());
    assertEquals(400, sendRaw("POST", "/c/points/sign-in", "{} {}",
        bearer(inviterToken, requestId)).status());
    assertEquals(400, sendRaw("POST", "/c/points/sign-in", "{}", bearer(inviterToken)).status());
    assertEquals(400, sendRaw("POST", "/c/points/sign-in?x=1", "{}",
        bearer(inviterToken, requestId)).status());

    Reply first = sendRaw("POST", "/c/points/sign-in", "{}", bearer(inviterToken, requestId));
    assertEquals(200, first.status());
    assertEquals("5", first.data().get("points"));
    assertEquals(1, first.data().get("streakAfter"));
    assertEquals("5", first.data().get("balanceAfter"));

    Reply replay = sendRaw("POST", "/c/points/sign-in", "{}", bearer(inviterToken, requestId));
    assertEquals(200, replay.status());
    assertEquals(first.data().get("ledgerId"), replay.data().get("ledgerId"));

    Reply sameDay = sendRaw("POST", "/c/points/sign-in", "{}",
        bearer(inviterToken, UUID.randomUUID().toString()));
    assertEquals(409, sameDay.status());
    assertEquals("COMMON_CONFLICT", sameDay.envelope().get("code"));

    Reply tasks = send("GET", "/c/points/tasks", null, bearer(inviterToken));
    assertEquals(200, tasks.status());
    String inviteCode = str(tasks.data(), "inviteCode");
    assertTrue(inviteCode.matches("P[0-9a-z]{14}"));
    List<?> rows = (List<?>) tasks.data().get("tasks");
    assertEquals(4, rows.size());
    assertEquals("COMMUNITY_POST", ((Map<?, ?>) rows.get(3)).get("taskCode"));
    assertEquals("NOT_OPEN", ((Map<?, ?>) rows.get(3)).get("status"));

    // Referral binding: self-code 400, happy binding 200, repeat 409.
    assertEquals(400, send("POST", "/c/points/referral-bindings",
        Map.of("inviteCode", inviteCode), bearer(inviterToken, UUID.randomUUID().toString()))
        .status());
    Reply bound = send("POST", "/c/points/referral-bindings", Map.of("inviteCode", inviteCode),
        bearer(inviteeToken, UUID.randomUUID().toString()));
    assertEquals(200, bound.status());
    assertNotNull(bound.data().get("bindingId"));
    Reply repeat = send("POST", "/c/points/referral-bindings", Map.of("inviteCode", inviteCode),
        bearer(inviteeToken, UUID.randomUUID().toString()));
    assertEquals(409, repeat.status());
    assertEquals("COMMON_CONFLICT", repeat.envelope().get("code"));

    // No claimable per-head reward until the invitee's first payment lands after the binding;
    // the first-payment fact flows through the REAL assembled OrderQueryApi bean (auth.c on).
    assertEquals(409, send("POST", "/c/points/claims", Map.of("claimType", "INVITE_PER_HEAD"),
        bearer(inviterToken, UUID.randomUUID().toString())).status());
    db.jdbc.update(
        "INSERT INTO pet_order(id, order_no, user_id, merchant_id, store_id, service_id, pet_id,"
            + " reservation_id, order_stage, payment_status, fulfillment_type, original_amount,"
            + " pay_amount, appointment_start_at, appointment_end_at, paid_at, created_at, updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        9_400_000_000_002_001L, 9_400_000_000_002_001L, Long.parseLong(str(invitee, "userId")),
        1L, 1L, 1L, 1L, 9_400_000_000_002_099L, "PENDING_SERVICE", "PAID", "IN_STORE",
        new java.math.BigDecimal("99.00"), new java.math.BigDecimal("99.00"),
        LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(1));
    Reply claimed = send("POST", "/c/points/claims", Map.of("claimType", "INVITE_PER_HEAD"),
        bearer(inviterToken, UUID.randomUUID().toString()));
    assertEquals(200, claimed.status());
    assertEquals("50", claimed.data().get("pointsGranted"));
    assertEquals("55", claimed.data().get("balanceAfter"));

    assertEquals(400, send("POST", "/c/points/claims", Map.of("claimType", "NO_SUCH"),
        bearer(inviterToken, UUID.randomUUID().toString())).status());
  }

  @Test
  void adminFaceConfigGetPutValidationReplayAndConflict() throws Exception {
    String admin = adminLogin();
    assertEquals(401, send("GET", "/admin/points-task-config", null, Map.of("Origin", ORIGIN))
        .status());

    Reply defaults = send("GET", "/admin/points-task-config", null, adminBearer(admin));
    assertEquals(200, defaults.status());
    assertEquals("5", defaults.data().get("signInDailyPoints"));
    assertEquals("20", defaults.data().get("profileCompletePoints"));
    assertEquals("0", defaults.data().get("version"));
    assertNull(defaults.data().get("updatedAt"));
    assertEquals("no-store", defaults.raw().headers().firstValue("Cache-Control").orElseThrow());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("signInDailyPoints", 6);
    body.put("signInStreakBonusPoints", 31);
    body.put("invitePerInviteePoints", 51);
    body.put("inviteMonthlyRewardCap", 4);
    body.put("inviteMilestoneBonusPoints", 101);
    body.put("firstOrderPoints", 99);
    body.put("profileCompletePoints", 21);

    // Strict full-put: unknown field, missing field and an out-of-range amount are all 400.
    Map<String, Object> unknown = new LinkedHashMap<>(body);
    unknown.put("extra", 1);
    assertEquals(400, send("PUT", "/admin/points-task-config", unknown,
        adminBearer(admin, UUID.randomUUID().toString())).status());
    Map<String, Object> missing = new LinkedHashMap<>(body);
    missing.remove("inviteMonthlyRewardCap");
    assertEquals(400, send("PUT", "/admin/points-task-config", missing,
        adminBearer(admin, UUID.randomUUID().toString())).status());
    Map<String, Object> outOfRange = new LinkedHashMap<>(body);
    outOfRange.put("signInDailyPoints", 1001);
    assertEquals(400, send("PUT", "/admin/points-task-config", outOfRange,
        adminBearer(admin, UUID.randomUUID().toString())).status());
    assertEquals(400, sendRaw("PUT", "/admin/points-task-config",
        "{\"signInDailyPoints\":6,\"signInStreakBonusPoints\":31,\"invitePerInviteePoints\":51,"
            + "\"inviteMonthlyRewardCap\":4,\"inviteMilestoneBonusPoints\":101,"
            + "\"firstOrderPoints\":99,\"profileCompletePoints\":21}",
        adminBearer(admin)).status());

    String requestId = UUID.randomUUID().toString();
    Reply saved = send("PUT", "/admin/points-task-config", body, adminBearer(admin, requestId));
    assertEquals(200, saved.status());
    assertEquals("6", saved.data().get("signInDailyPoints"));
    // The row inserts at version 0 (notification-preference convention); a second full-put
    // with a fresh requestId would bump it to 1.
    assertEquals("0", saved.data().get("version"));
    assertNotNull(saved.data().get("updatedAt"));

    Reply replay = send("PUT", "/admin/points-task-config", body, adminBearer(admin, requestId));
    assertEquals(200, replay.status());
    assertEquals("0", replay.data().get("version"));

    Map<String, Object> different = new LinkedHashMap<>(body);
    different.put("signInDailyPoints", 7);
    Reply conflict =
        send("PUT", "/admin/points-task-config", different, adminBearer(admin, requestId));
    assertEquals(409, conflict.status());
    assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.envelope().get("code"));

    assertEquals("6", send("GET", "/admin/points-task-config", null, adminBearer(admin))
        .data().get("signInDailyPoints"));
  }

  // ------------------------------------------------------------------ helpers

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/c/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    Reply grant =
        send(
            "POST",
            "/c/auth/wechat-login",
            Map.of(
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of(
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(200, grant.status());
    return grant.data();
  }

  private String adminLogin() throws Exception {
    Reply attempt =
        send("POST", "/admin/auth/attempts", Map.of(),
            Map.of("Origin", ORIGIN, "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status());
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin", ORIGIN,
                "Cookie", attempt.raw().headers().firstValue("Set-Cookie").orElseThrow()
                    .split(";", 2)[0],
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    Reply challenge =
        send("POST", "/admin/auth/captcha/challenges", Map.of("attemptId", attemptId), headers);
    assertEquals(200, challenge.status());
    db.jdbc.update(
        "UPDATE admin_captcha SET answer_mac=? WHERE id=?",
        AdminSecretCodec.fixed("qa-key", adminMac, adminAes).mac("qa-key", "CAPTCHA", "ABC234"),
        Long.parseLong(str(challenge.data(), "captchaId")));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply proof =
        send(
            "POST",
            "/admin/auth/captcha/verify",
            Map.of("attemptId", attemptId, "captchaId", str(challenge.data(), "captchaId"),
                "answer", "ABC234"),
            headers);
    assertEquals(200, proof.status());
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply grant =
        send(
            "POST",
            "/admin/auth/login",
            Map.of(
                "attemptId", attemptId,
                "account", "qa-points",
                "password", PASSWORD,
                "captchaProof", str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, grant.status());
    return str(grant.data(), "accessToken");
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    return sendRaw(method, path, body == null ? null : json.writeValueAsString(body), headers);
  }

  private Reply sendRaw(
      String method, String path, String rawBody, Map<String, String> headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (rawBody != null) builder.header("Content-Type", "application/json");
    HttpResponse<String> response =
        http.send(
            builder
                .method(
                    method,
                    rawBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 500) {
      System.out.println("DEBUG " + method + " " + path + " -> " + response.body());
    }
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response);
  }

  private static Map<String, String> bearer(String token, String requestId) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    if (requestId != null) headers.put("X-Request-Id", requestId);
    return headers;
  }

  private static Map<String, String> bearer(String token) {
    return bearer(token, null);
  }

  private static Map<String, String> adminBearer(String token) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Origin", ORIGIN);
    headers.put("Authorization", "Bearer " + token);
    return headers;
  }

  private static Map<String, String> adminBearer(String token, String requestId) {
    Map<String, String> headers = adminBearer(token);
    headers.put("X-Request-Id", requestId);
    return headers;
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
    return Objects.requireNonNull(p);
  }

  private static byte[] key(int seed) {
    byte[] value = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(value);
    return value;
  }
}
