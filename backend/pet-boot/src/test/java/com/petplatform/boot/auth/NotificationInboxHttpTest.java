package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * CCR-W2-NOTIFICATION-001 acceptance: a REAL approval writes the mandatory inbox row, then the
 * owner sees it in the list/detail, marks it read idempotently, and other users stay isolated.
 */
class NotificationInboxHttpTest {
  private static final String ORIGIN = "https://inbox.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<Long, PrivateAssetQueryPort.PrivateAssetRef> privateAssets = new HashMap<>();
  private final byte[] adminMac = key(3), adminAes = key(7);
  private final AesGcmProtectedValueProvider protector =
      new AesGcmProtectedValueProvider("qa-only-v1", key(31), key(47));
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String origin;

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
    return Objects.requireNonNull(p);
  }

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    try (var connection = db.source.getConnection()) {
      for (String file :
          List.of(
              "26-Admin-Auth-Schema-v0.1.sql",
              "28-Merchant-Agreement-Schema-v0.1.sql",
              "29-Merchant-Application-Schema-v0.1.sql")) {
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
      }
    }
    db.jdbc.update(
        "INSERT INTO merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)"
            + " VALUES(1,'qa-only-v1','HMAC-SHA-256',UTC_TIMESTAMP(3))");
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("inbox-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.merchant.application.notifications-enabled", true);
    props.put("pet.outbox.enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "inboxDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("inboxIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "inboxWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "inboxProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "inboxPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "inboxMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试服务地址"));
                    beans.registerBean(
                        "inboxSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                  })
              .run(
                  props.entrySet().stream()
                      .map(e -> "--" + e.getKey() + "=" + e.getValue())
                      .toArray(String[]::new));
      origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      context
          .getBean(AdminAuthService.class)
          .bootstrap(
              "qa-reviewer", "QA Reviewer", PASSWORD.toCharArray(),
              "Isolated inbox acceptance");
    } catch (Exception failure) {
      if (context != null) context.close();
      db.close();
      db = null;
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
  void approvalNotificationBecomesVisibleReadableAndIsolated() throws Exception {
    // Real flow: application -> review -> APPROVE writes the mandatory inbox row via outbox.
    Map<String, Object> owner = consumerLogin("inbox-owner", "13800007701");
    Map<String, Object> other = consumerLogin("inbox-other", "13800007702");
    String token = str(owner, "accessToken"), ownerId = str(owner, "userId");
    for (long id = 101; id <= 104; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("test-asset-" + id), "image/jpeg", 512, "READY"));
    Reply created = send("POST", "/api/v1/c/merchant-applications", draft(), bearer(token));
    assertEquals(201, created.status(), created.redacted());
    Reply submitted =
        send(
            "POST",
            "/api/v1/c/merchant-applications/" + str(created.data(), "applicationId") + "/submit",
            Map.of(
                "expectedVersion", str(created.data(), "version"),
                "revisionId", str(created.data(), "currentRevisionId")),
            bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    String admin = adminLogin();
    String reviewPath =
        "/api/v1/admin/merchant-applications/" + str(created.data(), "applicationId");
    Reply review = send("GET", reviewPath, null, bearer(admin));
    Reply claimed =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(map(review.data().get("task")), "version")),
            bearer(admin));
    assertEquals(200, claimed.status(), claimed.redacted());
    Map<String, Object> revision = map(review.data().get("submittedRevision"));
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (Object referenceObject : (List<?>) revision.get("materialReferences")) {
      Map<String, Object> reference = map(referenceObject);
      String type = String.valueOf(reference.get("materialType"));
      boolean license = "BUSINESS_LICENSE".equals(type);
      if (!license && !"ID_CARD_BACK".equals(type)) continue;
      evidence.add(
          Map.of(
              "materialId", String.valueOf(reference.get("materialId")),
              "materialSha256", String.valueOf(reference.get("materialSha256")),
              "credentialType", license ? "CREDIT_CODE" : "IDENTITY_NUMBER",
              "subjectName", license ? "星河宠物生活馆" : "张三",
              "identifier", license ? testCreditCode() : testIdentity(),
              "validFrom", "2020-01-01",
              "validityKind", "LONG_TERM"));
    }
    Reply verified =
        send(
            "POST",
            reviewPath + "/manual-verification",
            Map.of(
                "submissionRevisionId", str(revision, "revisionId"),
                "expectedVersion", str(review.data(), "version"),
                "expectedTaskVersion", str(claimed.data(), "version"),
                "evidenceItems", evidence,
                "reason", "已逐项核对当前提交的原件材料",
                "confirmed", true),
            bearer(admin));
    assertEquals(200, verified.status(), verified.redacted());
    Reply approved =
        send(
            "POST",
            reviewPath + "/decision",
            Map.of(
                "decisionType", "APPROVE",
                "submissionRevisionId", str(revision, "revisionId"),
                "expectedVersion", str(verified.data(), "version"),
                "expectedTaskVersion", str(claimed.data(), "version"),
                "confirmed", true),
            bearer(admin));
    assertEquals(200, approved.status(), approved.redacted());

    // The outbox consumer delivers the notification; wait for the mandatory inbox row.
    long ownerDbId = Long.parseLong(ownerId);
    for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos(); ; ) {
      Integer count =
          db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM notification WHERE receiver_id=? AND mandatory_inbox=1",
              Integer.class, ownerDbId);
      if (count != null && count >= 1) break;
      if (System.nanoTime() >= deadline) fail("approval notification was not delivered");
      Thread.sleep(200);
    }

    String inbox = "/api/v1/c/notifications";
    assertEquals(401, send("GET", inbox, null, Map.of()).status());
    assertEquals(400, send("GET", inbox + "?page=0", null, bearer(token)).status());
    assertEquals(400, send("GET", inbox + "?pageSize=51", null, bearer(token)).status());
    assertEquals(400, send("GET", inbox + "?receiverId=1", null, bearer(token)).status());

    Reply list = send("GET", inbox, null, bearer(token));
    assertEquals(200, list.status(), list.redacted());
    assertEquals(1, ((Number) list.data().get("total")).intValue());
    List<?> items = (List<?>) list.data().get("items");
    assertEquals(1, items.size());
    Map<String, Object> item = map(items.get(0));
    assertEquals("MERCHANT_APPLICATION_REVIEWED", item.get("messageType"));
    assertEquals("MERCHANT_APPLICATION", item.get("bizType"));
    assertEquals("SYSTEM", item.get("category"));
    assertEquals("商家入驻审核结果", item.get("title"));
    assertTrue(String.valueOf(item.get("content")).contains("审核通过"));
    assertNull(item.get("readAt"));
    String notificationId = str(item, "id");

    Reply detail = send("GET", inbox + "/" + notificationId, null, bearer(token));
    assertEquals(200, detail.status(), detail.redacted());
    assertEquals("商家入驻审核结果", detail.data().get("title"));

    // Cross-user isolation: empty list and anti-enumeration 404.
    Reply otherList = send("GET", inbox, null, bearer(str(other, "accessToken")));
    assertEquals(200, otherList.status(), otherList.redacted());
    assertTrue(((List<?>) otherList.data().get("items")).isEmpty());
    assertEquals(
        404,
        send("GET", inbox + "/" + notificationId, null, bearer(str(other, "accessToken")))
            .status());
    assertEquals(
        404,
        send(
                "POST",
                inbox + "/" + notificationId + "/read",
                Map.of(),
                bearer(str(other, "accessToken")))
            .status());
    assertEquals(400, send("GET", inbox + "/abc", null, bearer(token)).status());

    // Read marking is result-idempotent; replays return the same timestamp.
    Reply read =
        send(
            "POST",
            inbox + "/" + notificationId + "/read",
            Map.of(),
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(200, read.status(), read.redacted());
    String firstReadAt = str(read.data(), "readAt");
    assertTrue(firstReadAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    Reply replay =
        send(
            "POST",
            inbox + "/" + notificationId + "/read",
            Map.of(),
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(200, replay.status(), replay.redacted());
    assertEquals(firstReadAt, str(replay.data(), "readAt"));
    Reply after = send("GET", inbox + "/" + notificationId, null, bearer(token));
    assertEquals(firstReadAt, map(after.data()).get("readAt"));
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send("POST", "/api/v1/c/auth/attempts", Map.of("purpose", "WECHAT_LOGIN"), Map.of());
    Reply grant =
        send(
            "POST",
            "/api/v1/c/auth/wechat-login",
            Map.of(
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of("X-Auth-Attempt", str(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status(), grant.redacted());
    return grant.data();
  }

  private String adminLogin() throws Exception {
    Reply attempt =
        send(
            "POST",
            "/api/v1/admin/auth/attempts",
            Map.of(),
            Map.of("Origin", ORIGIN, "X-Request-Id", UUID.randomUUID().toString()));
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin", ORIGIN,
                "Cookie",
                    attempt.raw().headers().firstValue("Set-Cookie").orElseThrow()
                        .split(";", 2)[0],
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
    Reply challenge =
        send(
            "POST",
            "/api/v1/admin/auth/captcha/challenges",
            Map.of("attemptId", attemptId),
            headers);
    db.jdbc.update(
        "UPDATE admin_captcha SET answer_mac=? WHERE id=?",
        AdminSecretCodec.fixed("qa-key", adminMac, adminAes).mac("qa-key", "CAPTCHA", "ABC234"),
        Long.parseLong(str(challenge.data(), "captchaId")));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply proof =
        send(
            "POST",
            "/api/v1/admin/auth/captcha/verify",
            Map.of(
                "attemptId", attemptId,
                "captchaId", str(challenge.data(), "captchaId"),
                "answer", "ABC234"),
            headers);
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply grant =
        send(
            "POST",
            "/api/v1/admin/auth/login",
            Map.of(
                "attemptId", attemptId,
                "account", "qa-reviewer",
                "password", PASSWORD,
                "captchaProof", str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, grant.status(), grant.redacted());
    return str(grant.data(), "accessToken");
  }

  private record Reply(int status, Map<String, Object> envelope, HttpResponse<String> raw) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + "]";
    }
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    if (!headers.containsKey("X-Request-Id") && !method.equals("GET"))
      builder.header("X-Request-Id", UUID.randomUUID().toString());
    HttpResponse<String> response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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

  private static Map<String, Object> draft() {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "星河宠物生活馆");
    draft.put("contactName", "张三");
    draft.put("contactPhone", "13800138000");
    draft.put("email", "owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of("101"));
    draft.put("businessLicenseAssetId", "102");
    draft.put("idCardFrontAssetId", "103");
    draft.put("idCardBackAssetId", "104");
    return draft;
  }

  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> result = new LinkedHashMap<>();
      m.forEach((k, v) -> result.put(String.valueOf(k), v));
      return result;
    }
    throw new IllegalArgumentException("Expected object, got: " + value);
  }

  private static String str(Map<String, Object> value, String key) {
    Object found = value.get(key);
    if (found == null) throw new IllegalArgumentException("Missing " + key);
    return String.valueOf(found);
  }

  private static byte[] key(int seed) {
    byte[] value = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(value);
    return value;
  }

  private static String sha(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private static String testIdentity() {
    String body = "51010419900101001";
    int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += (body.charAt(i) - '0') * weights[i];
    return body + "10X98765432".charAt(sum % 11);
  }

  private static String testCreditCode() {
    String body = "91510100MA0000000", alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += alphabet.indexOf(body.charAt(i)) * weights[i];
    return body + alphabet.charAt((31 - sum % 31) % 31);
  }
}
