package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
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
 * CCR-W2-ADMISSION-001 HTTP acceptance: real sessions, real fixture MySQL/Redis, the normal
 * application-review-agreement business flow (no fabricated approval state), then the admission
 * matrix including fail-closed and anti-enumeration branches.
 */
class MerchantAdmissionHttpTest {
  private static final String ORIGIN = "https://admission.example.invalid";
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("admission-audit.bin"));
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
                        "admissionDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("admissionIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "admissionWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "admissionProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "admissionPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "admissionMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试服务地址"));
                    beans.registerBean(
                        "admissionSubjects",
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
              "qa-reviewer",
              "QA Reviewer",
              PASSWORD.toCharArray(),
              "Isolated admission acceptance");
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
  void admissionMatrixRunsAcrossRealApplicationAgreementAndStatusFacts() throws Exception {
    Map<String, Object> owner = consumerLogin("admission-owner", "13800007701");
    Map<String, Object> other = consumerLogin("admission-other", "13800007702");
    String token = str(owner, "accessToken"), ownerId = str(owner, "userId");
    for (long id = 101; id <= 104; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("test-asset-" + id), "image/jpeg", 512, "READY"));
    Map<String, Object> draft = draft();
    Reply created = send("POST", "/api/v1/c/merchant-applications", draft, bearer(token));
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
    assertEquals(200, review.status(), review.redacted());
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
    assertEquals("APPROVED", approved.data().get("status"));
    String merchantId = str(approved.data(), "reservedMerchantId");

    // One more store for ordering/pagination; store rows are plain fixtures, not lifecycle state.
    Long baseStoreId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class,
            Long.parseLong(merchantId));
    long secondStoreId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_store(id,merchant_id,store_name,address,longitude,latitude,phone,status,version,"
            + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        secondStoreId,
        Long.parseLong(merchantId),
        "第二门店",
        "测试服务地址二",
        new BigDecimal("121.5"),
        new BigDecimal("31.3"),
        "13800138001",
        "ACTIVE");

    String membershipsPath = "/api/v1/c/auth/merchant-memberships";
    assertEquals(401, send("GET", membershipsPath, null, Map.of()).status());
    assertEquals(400, send("GET", membershipsPath + "?page=0", null, bearer(token)).status());
    assertEquals(400, send("GET", membershipsPath + "?pageSize=51", null, bearer(token)).status());
    assertEquals(400, send("GET", membershipsPath + "?staffId=1", null, bearer(token)).status());
    Reply mine = send("GET", membershipsPath, null, bearer(token));
    assertEquals(200, mine.status(), mine.redacted());
    assertEquals(2, ((Number) mine.data().get("total")).intValue());
    List<?> items = (List<?>) mine.data().get("items");
    assertEquals(2, items.size());
    long listedFirst = Long.parseLong(str(map(items.get(0)), "storeId"));
    long listedSecond = Long.parseLong(str(map(items.get(1)), "storeId"));
    assertTrue(listedFirst < listedSecond);
    assertTrue(
        (listedFirst == baseStoreId && listedSecond == secondStoreId)
            || (listedFirst == secondStoreId && listedSecond == baseStoreId));
    assertEquals("OWNER", map(items.get(0)).get("membershipKind"));
    assertEquals(
        str(map(items.get(0)), "merchantName"), map(items.get(0)).get("merchantName"));
    Reply others = send("GET", membershipsPath, null, bearer(str(other, "accessToken")));
    assertEquals(200, others.status(), others.redacted());
    assertTrue(((List<?>) others.data().get("items")).isEmpty());
    assertEquals(0, ((Number) others.data().get("total")).intValue());

    String admissionPath = "/api/v1/merchant/auth/admission";
    assertEquals(
        401,
        send(
                "GET",
                admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
                null,
                Map.of())
            .status());
    assertEquals(
        404,
        send(
                "GET",
                admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
                null,
                bearer(str(other, "accessToken")))
            .status());
    assertEquals(
        400, send("GET", admissionPath + "?merchantId=" + merchantId, null, bearer(token)).status());
    assertEquals(
        400,
        send(
                "GET",
                admissionPath + "?merchantId=" + merchantId
                    + "&storeId=" + baseStoreId + "&workspace=merchant",
                null,
                bearer(token))
            .status());

    // APPROVED but unsigned: DENIED with signing guidance; signing itself is never gated here.
    Reply unsigned =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertEquals(200, unsigned.status(), unsigned.redacted());
    assertEquals("DENIED", unsigned.data().get("admission"));
    assertEquals(List.of("SIGNING_REQUIRED"), unsigned.data().get("reasonCodes"));
    assertEquals(List.of(Map.of("type", "COMPLETE_SIGNING")), unsigned.data().get("nextSteps"));
    assertEquals(List.of(), unsigned.data().get("allowedActions"));
    assertEquals(
        "APPROVED", map(map(unsigned.data().get("facts")).get("application")).get("status"));
    assertEquals(
        "NOT_SIGNED", map(map(unsigned.data().get("facts")).get("signing")).get("status"));

    // Sign through the real consent surface, then the five-condition ALLOWED row.
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", contentSha = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "admission-v1",
        content,
        contentSha,
        db.jdbc.queryForObject(
            "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class));
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
            + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
        agreementId);
    Reply consent =
        send(
            "POST",
            "/api/v1/merchant/agreement/consent",
            Map.of(
                "merchantId", merchantId,
                "agreementVersion", "admission-v1",
                "contentSha256", contentSha,
                "accepted", true),
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(201, consent.status(), consent.redacted());

    Reply allowed =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertEquals(200, allowed.status(), allowed.redacted());
    assertEquals("ALLOWED", allowed.data().get("admission"));
    assertEquals(List.of(), allowed.data().get("reasonCodes"));
    assertEquals(List.of(), allowed.data().get("nextSteps"));
    assertEquals(
        List.of(
            "merchant.aftersale.read",
            "merchant.order.read",
            "merchant.penalty.read",
            "merchant.schedule.manage",
            "merchant.service.manage",
            "merchant.staff.manage"),
        allowed.data().get("allowedActions"));
    Map<String, Object> facts = map(allowed.data().get("facts"));
    assertEquals("APPROVED", map(facts.get("application")).get("status"));
    assertEquals("SIGNED", map(facts.get("signing")).get("status"));
    assertEquals("ACTIVE", facts.get("storeStatus"));
    assertEquals("ACTIVE", facts.get("merchantStatus"));
    assertNull(facts.get("staffEnabled"));
    assertTrue(String.valueOf(allowed.data().get("authzVersion")).matches("[0-9a-f]{16}"));
    assertTrue(
        String.valueOf(allowed.data().get("checkedAt"))
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})"));

    // LIMITED shapes from authoritative status changes.
    db.jdbc.update("UPDATE merchant_store SET status='FROZEN' WHERE id=?", baseStoreId);
    Reply frozen =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertEquals("LIMITED", frozen.data().get("admission"));
    assertEquals(List.of("STORE_FROZEN"), frozen.data().get("reasonCodes"));
    assertEquals(
        List.of(
            "merchant.aftersale.read",
            "merchant.order.read",
            "merchant.penalty.appeal",
            "merchant.penalty.read"),
        frozen.data().get("allowedActions"));
    assertEquals(
        List.of(
            Map.of("type", "APPEAL"),
            Map.of("type", "VIEW_AFTERSALES"),
            Map.of("type", "VIEW_EXISTING_ORDERS")),
        frozen.data().get("nextSteps"));
    db.jdbc.update("UPDATE merchant_store SET status='ACTIVE' WHERE id=?", baseStoreId);
    db.jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", Long.parseLong(merchantId));
    Reply offline =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertEquals("LIMITED", offline.data().get("admission"));
    assertEquals(List.of("MERCHANT_OFFLINE"), offline.data().get("reasonCodes"));
    assertTrue(
        ((List<?>) offline.data().get("allowedActions")).contains("merchant.order.fulfill"));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", Long.parseLong(merchantId));

    // authzVersion is change-sensitive through the store version component.
    String before = str(allowed.data(), "authzVersion");
    db.jdbc.update("UPDATE merchant_store SET version=version+1 WHERE id=?", baseStoreId);
    Reply bumped =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertNotEquals(before, str(bumped.data(), "authzVersion"));

    // Fail-closed: missing application facts are a 503 with data=null, never a synthesized DENIED.
    // Constraint-safe corruption: break the signed acceptance hash so the signing facts reader
    // fails closed instead of synthesizing DENIED/NOT_SIGNED.
    db.jdbc.update(
        "UPDATE merchant_agreement_acceptance SET content_sha256=? WHERE merchant_id=?",
        "f".repeat(64), Long.parseLong(merchantId));
    Reply broken =
        send(
            "GET",
            admissionPath + "?merchantId=" + merchantId + "&storeId=" + baseStoreId,
            null,
            bearer(token));
    assertEquals(503, broken.status());
    assertNull(broken.data());
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
                    attempt.raw().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
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
