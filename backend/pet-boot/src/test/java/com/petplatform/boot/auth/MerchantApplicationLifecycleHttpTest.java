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
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real TCP, sessions, SQL06/26/28/29, Redis, AES, domain and Outbox. Only external facts are test
 * doubles.
 */
class MerchantApplicationLifecycleHttpTest {
  private static final String ORIGIN = "https://merchant-review.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<Long, PrivateAssetQueryPort.PrivateAssetRef> privateAssets =
      new ConcurrentHashMap<>();
  private final byte[] adminMac = key(3), adminAes = key(7);
  private final AesGcmProtectedValueProvider protector =
      new AesGcmProtectedValueProvider("qa-only-v1", key(31), key(47));
  private CAuthHttpTest.HttpFixture db;
  private ConfigurableApplicationContext context;
  private String origin;

  @BeforeEach
  void start() throws Exception {
    db = new CAuthHttpTest.HttpFixture();
    Path root = Path.of("").toAbsolutePath();
    while (root != null
        && !Files.exists(root.resolve("docs/03-database/29-Merchant-Application-Schema-v0.1.sql")))
      root = root.getParent();
    assertNotNull(root);
    try (var connection = db.source.getConnection()) {
      for (String file :
          List.of(
              "26-Admin-Auth-Schema-v0.1.sql",
              "28-Merchant-Agreement-Schema-v0.1.sql",
              "29-Merchant-Application-Schema-v0.1.sql"))
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(
                new FileSystemResource(root.resolve("docs/03-database/" + file)),
                StandardCharsets.UTF_8));
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("merchant-audit.bin"));
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
                    beans.registerBean("merchantTestDataSource", DataSource.class, () -> db.source);
                    beans.registerBean("merchantTestIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "merchantTestWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "merchantTestProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "merchantTestPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "merchantTestMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试服务地址"));
                    beans.registerBean(
                        "merchantTestSubjects",
                        SubjectCredentialPort.class,
                        this::testSubjectProvider);
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
              "Isolated merchant lifecycle validation");
      System.out.println("MER lifecycle test: real sessions and domain runtime started");
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
      if (db != null) {
        var redis =
            io.lettuce.core.RedisClient.create("redis://" + db.redisHost + ":" + db.redisPort);
        try (var connection = redis.connect()) {
          for (String k : connection.sync().keys(db.name + "_admin:*")) connection.sync().del(k);
        } finally {
          redis.shutdown();
          db.close();
        }
      }
    }
  }

  @Test
  void applicationCorrectionReviewConsentAndInboxRunAcrossActualHttpAndPersistence()
      throws Exception {
    Map<String, Object> login = consumerLogin("merchant-flow-owner", "13800007701");
    String token = str(login, "accessToken"), ownerId = str(login, "userId");
    Map<String, Object> other = consumerLogin("merchant-flow-other", "13800007702");
    Reply cities = send("GET", "/api/v1/c/merchant-application-cities", null, bearer(token));
    assertEquals(200, cities.status(), cities.redacted());
    assertEquals(
        List.of(Map.of("cityCode", "chengdu", "cityName", "成都")), cities.data().get("items"));
    assertFalse(
        context
            .getBean(com.petplatform.boot.config.MerchantApplicationCityCatalog.class)
            .isOpen("shanghai"));
    assertEquals(
        401, send("GET", "/api/v1/c/merchant-applications/current", null, Map.of()).status());
    assertEquals(
        404, send("GET", "/api/v1/c/merchant-applications/current", null, bearer(token)).status());
    for (long id = 101; id <= 104; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("test-asset-" + id), "image/jpeg", 512, "READY"));
    Map<String, Object> draft = draft();
    String createKey = UUID.randomUUID().toString();
    Reply first = send("POST", "/api/v1/c/merchant-applications", draft, bearer(token, createKey));
    assertEquals(201, first.status(), first.redacted());
    Reply replay = send("POST", "/api/v1/c/merchant-applications", draft, bearer(token, createKey));
    assertEquals(200, replay.status());
    assertEquals(first.data(), replay.data());
    assertInstanceOf(String.class, first.data().get("version"));
    String appId = str(first.data(), "applicationId"),
        path = "/api/v1/c/merchant-applications/" + appId;
    assertEquals(
        404,
        send(
                "PUT",
                path + "/draft",
                Map.of("expectedVersion", str(first.data(), "version"), "draft", draft),
                bearer(str(other, "accessToken")))
            .status());
    Reply owner = send("GET", "/api/v1/c/merchant-applications/current", null, bearer(token));
    assertEquals(200, owner.status());
    Map<String, Object> ownerRevision = map(owner.data().get("currentRevision"));
    assertEquals("13800138000", map(ownerRevision.get("draft")).get("contactPhone"));
    assertNotNull(ownerRevision.get("createdAt"));
    Reply submitted =
        send(
            "POST",
            path + "/submit",
            Map.of(
                "expectedVersion",
                str(first.data(), "version"),
                "revisionId",
                str(first.data(), "currentRevisionId")),
            bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    String admin = adminLogin();
    assertEquals(
        401, send("GET", "/api/v1/c/merchant-applications/current", null, bearer(admin)).status());
    assertEquals(
        401, send("GET", "/api/v1/admin/merchant-applications", null, bearer(token)).status());
    assertEquals(
        400,
        send("PUT", path + "/draft", Map.of("expectedVersion", 1, "draft", draft), bearer(token))
            .status());
    String reviewPath = "/api/v1/admin/merchant-applications/" + appId;
    assertEquals(400, send("GET", reviewPath + "?reveal=true", null, bearer(admin)).status());
    Reply list =
        send("GET", "/api/v1/admin/merchant-applications?page=1&pageSize=10", null, bearer(admin));
    assertEquals(200, list.status(), list.redacted());
    Reply review = send("GET", reviewPath, null, bearer(admin));
    assertEquals(200, review.status(), review.redacted());
    assertFalse(review.body().contains("13800138000"));
    assertFalse(review.body().contains("owner@example.test"));
    Reply claimed =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(map(review.data().get("task")), "version")),
            bearer(admin));
    assertEquals(200, claimed.status(), claimed.redacted());
    Reply released =
        send(
            "POST",
            reviewPath + "/release",
            Map.of("expectedTaskVersion", str(claimed.data(), "version")),
            bearer(admin));
    assertEquals(200, released.status(), released.redacted());
    assertEquals("AVAILABLE", released.data().get("status"));
    claimed =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(released.data(), "version")),
            bearer(admin));
    assertEquals(200, claimed.status(), claimed.redacted());
    Map<String, Object> correction = new LinkedHashMap<>();
    correction.put("decisionType", "REQUEST_CORRECTION");
    correction.put("submissionRevisionId", str(submitted.data(), "currentRevisionId"));
    correction.put("expectedVersion", str(submitted.data(), "version"));
    correction.put("expectedTaskVersion", str(claimed.data(), "version"));
    correction.put("opinion", "请补充完整清晰的申请说明材料");
    correction.put("internalNote", "仅内部审核使用");
    correction.put("confirmed", true);
    Reply rejected = send("POST", reviewPath + "/decision", correction, bearer(admin));
    assertEquals(200, rejected.status(), rejected.redacted());
    assertEquals("REJECTED", rejected.data().get("status"));
    draft.put("introduction", "补正后的申请说明");
    Reply saved =
        send(
            "PUT",
            path + "/draft",
            Map.of("expectedVersion", str(rejected.data(), "version"), "draft", draft),
            bearer(token));
    assertEquals(200, saved.status(), saved.redacted());
    assertNotEquals(
        str(submitted.data(), "currentRevisionId"), str(saved.data(), "currentRevisionId"));
    Reply resubmitted =
        send(
            "POST",
            path + "/submit",
            Map.of(
                "expectedVersion",
                str(saved.data(), "version"),
                "revisionId",
                str(saved.data(), "currentRevisionId")),
            bearer(token));
    assertEquals(200, resubmitted.status(), resubmitted.redacted());
    Reply newReview = send("GET", reviewPath, null, bearer(admin));
    Reply newClaim =
        send(
            "POST",
            reviewPath + "/claim",
            Map.of("expectedTaskVersion", str(map(newReview.data().get("task")), "version")),
            bearer(admin));
    assertEquals(200, newClaim.status(), newClaim.redacted());
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (String type : List.of("BUSINESS_LICENSE", "ID_CARD_BACK")) {
      List<?> refs =
          (List<?>) map(newReview.data().get("submittedRevision")).get("materialReferences");
      assertEquals(4, refs.size());
      Map<String, Object> row =
          refs.stream()
              .map(MerchantApplicationLifecycleHttpTest::map)
              .filter(value -> type.equals(value.get("materialType")))
              .findFirst()
              .orElseThrow();
      assertEquals(
          Set.of("materialId", "assetId", "materialSha256", "materialType", "position"),
          row.keySet());
      assertNotEquals(row.get("assetId"), row.get("materialId"));
      evidence.add(
          Map.of(
              "materialId",
              row.get("materialId").toString(),
              "materialSha256",
              row.get("materialSha256").toString(),
              "credentialType",
              type.equals("BUSINESS_LICENSE") ? "CREDIT_CODE" : "IDENTITY_NUMBER",
              "subjectName",
              type.equals("BUSINESS_LICENSE") ? "星河宠物生活馆" : "张三",
              "identifier",
              type.equals("BUSINESS_LICENSE") ? testCreditCode() : testIdentity(),
              "validFrom",
              "2020-01-01",
              "validityKind",
              "LONG_TERM"));
    }
    // Public references must be used verbatim: asset IDs and rendered hashes cannot substitute.
    for (String invalidField : List.of("materialId", "materialSha256")) {
      var changedEvidence = new ArrayList<>(evidence);
      var changedItem = new LinkedHashMap<>(evidence.getFirst());
      var references =
          (List<?>) map(newReview.data().get("submittedRevision")).get("materialReferences");
      var firstReference =
          references.stream()
              .map(MerchantApplicationLifecycleHttpTest::map)
              .filter(value -> "BUSINESS_LICENSE".equals(value.get("materialType")))
              .findFirst()
              .orElseThrow();
      changedItem.put(
          invalidField,
          "materialId".equals(invalidField) ? firstReference.get("assetId") : "f".repeat(64));
      changedEvidence.set(0, changedItem);
      Reply invalidReference =
          send(
              "POST",
              reviewPath + "/manual-verification",
              Map.of(
                  "submissionRevisionId",
                  str(resubmitted.data(), "currentRevisionId"),
                  "expectedVersion",
                  str(resubmitted.data(), "version"),
                  "expectedTaskVersion",
                  str(newClaim.data(), "version"),
                  "evidenceItems",
                  changedEvidence,
                  "reason",
                  "Material reference negative verification",
                  "confirmed",
                  true),
              bearer(admin));
      assertEquals(409, invalidReference.status(), invalidReference.redacted());
    }
    Reply verified =
        send(
            "POST",
            reviewPath + "/manual-verification",
            Map.of(
                "submissionRevisionId",
                str(resubmitted.data(), "currentRevisionId"),
                "expectedVersion",
                str(resubmitted.data(), "version"),
                "expectedTaskVersion",
                str(newClaim.data(), "version"),
                "evidenceItems",
                evidence,
                "reason",
                "已逐项核对当前提交的原件材料",
                "confirmed",
                true),
            bearer(admin));
    assertEquals(200, verified.status(), verified.redacted());
    Map<String, Object> approve =
        Map.of(
            "decisionType",
            "APPROVE",
            "submissionRevisionId",
            str(resubmitted.data(), "currentRevisionId"),
            "expectedVersion",
            str(verified.data(), "version"),
            "expectedTaskVersion",
            str(newClaim.data(), "version"),
            "confirmed",
            true);
    String approveKey = UUID.randomUUID().toString();
    Reply approved = send("POST", reviewPath + "/decision", approve, bearer(admin, approveKey));
    assertEquals(200, approved.status(), approved.redacted());
    assertEquals("APPROVED", approved.data().get("status"));
    assertEquals(
        approved.data(),
        send("POST", reviewPath + "/decision", approve, bearer(admin, approveKey)).data());
    String merchantId = str(approved.data(), "reservedMerchantId");
    assertEquals(
        0,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM merchant_agreement_acceptance", Integer.class));
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "qa-http-v1",
        content,
        hash,
        db.jdbc.queryForObject(
            "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class));
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
            + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
        agreementId);
    Reply agreement =
        send("GET", "/api/v1/merchant/agreement?merchantId=" + merchantId, null, bearer(token));
    assertEquals(200, agreement.status(), agreement.redacted());
    assertEquals("NOT_SIGNED", agreement.data().get("signingStatus"));
    assertEquals(
        404,
        send(
                "GET",
                "/api/v1/merchant/agreement?merchantId=" + merchantId,
                null,
                bearer(str(other, "accessToken")))
            .status());
    Reply signed =
        send(
            "POST",
            "/api/v1/merchant/agreement/consent",
            Map.of(
                "merchantId",
                merchantId,
                "agreementVersion",
                "qa-http-v1",
                "contentSha256",
                hash,
                "accepted",
                true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());
    assertEquals("SIGNED", signed.data().get("signingStatus"));
    verifyFrontendDecoders(login);
    long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE receiver_id=?",
                Integer.class,
                Long.parseLong(ownerId))
            < 2
        && System.nanoTime() < until) Thread.sleep(100);
    assertEquals(
        2,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE receiver_id=? AND mandatory_inbox=1",
            Integer.class,
            Long.parseLong(ownerId)));
    assertEquals(
        2,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM integration_event_outbox WHERE aggregate_id=?",
            Integer.class,
            Long.parseLong(appId)));
    assertFalse(
        db.jdbc
            .queryForList("SELECT content FROM notification", String.class)
            .toString()
            .contains("仅内部审核使用"));
    String adminSession =
        context.getBean(AdminAuthService.class).resolveSession(admin).principal().sessionId();
    db.jdbc.update(
        "UPDATE admin_web_session SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3) WHERE id=?",
        Long.parseLong(adminSession));
    assertEquals(401, send("GET", reviewPath, null, bearer(admin)).status());
  }

  private Map<String, Object> consumerLogin(String openid, String phone) throws Exception {
    Reply attempt =
        send(
            "POST",
            "/api/v1/c/auth/attempts",
            Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status(), attempt.redacted());
    Reply grant =
        send(
            "POST",
            "/api/v1/c/auth/wechat-login",
            Map.of(
                "attemptId",
                str(attempt.data(), "attemptId"),
                "wechatCode",
                "ok:" + openid,
                "phoneCode",
                "phone:" + phone),
            Map.of(
                "X-Request-Id",
                UUID.randomUUID().toString(),
                "X-Auth-Attempt",
                str(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status(), grant.redacted());
    return grant.data();
  }

  private void verifyFrontendDecoders(Map<String, Object> grant) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("frontend-miniapp")))
      root = root.getParent();
    assertNotNull(root);
    Path mini = root.resolve("frontend-miniapp");
    var process =
        new ProcessBuilder(
                "node", "--import", "tsx", "src/shared/tests/merchant-http-integration.ts")
            .directory(mini.toFile())
            .redirectErrorStream(true);
    process.environment().put("MERCHANT_TEST_ORIGIN", origin);
    process.environment().put("MERCHANT_TEST_GRANT", json.writeValueAsString(grant));
    var child = process.start();
    if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      child.destroyForcibly();
      fail("frontend HTTP contract verification timed out");
    }
    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, child.exitValue(), output);
  }

  private String adminLogin() throws Exception {
    Reply attempt =
        send(
            "POST",
            "/api/v1/admin/auth/attempts",
            Map.of(),
            Map.of("Origin", ORIGIN, "X-Request-Id", UUID.randomUUID().toString()));
    assertEquals(201, attempt.status(), attempt.redacted());
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin",
                ORIGIN,
                "Cookie",
                attempt.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                "X-Auth-Attempt",
                str(attempt.data(), "attemptToken"),
                "X-Request-Id",
                UUID.randomUUID().toString()));
    Reply challenge =
        send(
            "POST",
            "/api/v1/admin/auth/captcha/challenges",
            Map.of("attemptId", attemptId),
            headers);
    assertEquals(200, challenge.status(), challenge.redacted());
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
                "attemptId",
                attemptId,
                "captchaId",
                str(challenge.data(), "captchaId"),
                "answer",
                "ABC234"),
            headers);
    assertEquals(200, proof.status(), proof.redacted());
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply grant =
        send(
            "POST",
            "/api/v1/admin/auth/login",
            Map.of(
                "attemptId",
                attemptId,
                "account",
                "qa-reviewer",
                "password",
                PASSWORD,
                "captchaProof",
                str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, grant.status(), grant.redacted());
    return str(grant.data(), "accessToken");
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    var response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    System.out.println(
        "MER lifecycle HTTP: "
            + method
            + " "
            + path.split("\\?", 2)[0]
            + " status="
            + response.statusCode());
    return new Reply(
        response.statusCode(),
        json.readValue(response.body(), Map.class),
        response.headers(),
        response.body());
  }

  private record Reply(int status, Map<String, Object> envelope, HttpHeaders headers, String body) {
    Map<String, Object> data() {
      return map(envelope.get("data"));
    }

    String redacted() {
      return "HTTP " + status + " code=" + envelope.get("code");
    }

    @Override
    public String toString() {
      return redacted();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static String str(Map<String, Object> value, String key) {
    assertInstanceOf(String.class, value.get(key), key);
    return (String) value.get(key);
  }

  private static Map<String, String> bearer(String token) {
    return bearer(token, UUID.randomUUID().toString());
  }

  private static Map<String, String> bearer(String token, String request) {
    return Map.of("Authorization", "Bearer " + token, "X-Request-Id", request);
  }

  private static byte[] key(int fill) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  private static String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException();
    }
  }

  private static Map<String, Object> draft() {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("merchantName", "星河宠物生活馆"),
            Map.entry("contactName", "张三"),
            Map.entry("contactPhone", "13800138000"),
            Map.entry("email", "owner@example.test"),
            Map.entry("merchantTypeCode", "PET_LIFE_STORE"),
            Map.entry("cityCode", "chengdu"),
            Map.entry("address", "测试服务地址"),
            Map.entry("longitude", "121.4"),
            Map.entry("latitude", "31.2"),
            Map.entry("storePhotoAssetIds", List.of("101")),
            Map.entry("businessLicenseAssetId", "102"),
            Map.entry("idCardFrontAssetId", "103"),
            Map.entry("idCardBackAssetId", "104")));
  }

  private SubjectCredentialPort testSubjectProvider() {
    return new com.petplatform.merchant.biz.infrastructure.provider
        .MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63));
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
