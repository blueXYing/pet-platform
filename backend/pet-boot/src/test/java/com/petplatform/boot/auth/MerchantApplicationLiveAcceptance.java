package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.biz.application.*;
import com.petplatform.merchant.biz.infrastructure.provider.*;
import com.petplatform.thirdparty.biz.application.port.*;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Manual-only synthetic application -> real providers -> review -> agreement happy path. */
class MerchantApplicationLiveAcceptance {
  private static final String ADMIN_ORIGIN = "https://live-merchant-review.example.invalid";
  private static final String ADMIN_PASSWORD = "Synthetic_ONLY_92!";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void syntheticApplicationUsesRealPrivateAssetsMapCredentialsAndPersistence() throws Exception {
    Assumptions.assumeTrue(
        "true".equalsIgnoreCase(System.getenv("MERCHANT_APPLICATION_LIVE_ACCEPTANCE")),
        "manual merchant live acceptance flag is required");
    LiveLocation location = LiveLocation.fromEnvironment();
    System.out.println("MERCHANT_LIVE stage=wechat-boundary result=FIXED_WECHAT_TEST_DOUBLE");
    System.out.println("MERCHANT_LIVE stage=location-boundary result=LOCAL_INPUT_VALIDATION_ONLY");

    OssConnection oss = OssConnection.fromEnv();
    CAuthHttpTest.HttpFixture db = new CAuthHttpTest.HttpFixture();
    ConfigurableApplicationContext context = null;
    List<Long> privateAssetIds = new ArrayList<>();
    int cleanupFailures = 0;
    byte[] protectedAes = randomKey(), protectedHmac = randomKey(), subjectHmac = randomKey();
    byte[] adminMac = randomKey(), adminAes = randomKey(), grantHmac = randomKey();
    var protector =
        new AesGcmProtectedValueProvider("live-synthetic-v1", protectedAes, protectedHmac);
    try {
      PrivateAssetLiveSupport.initializePrivateSchemas(db.source);
      LocalMerchantAcceptanceServer.ensureSubjectLookupPolicy(db.jdbc, "live-synthetic-v1");
      LocalMerchantAcceptanceServer.ensureSubjectLookupPolicy(db.jdbc, "live-synthetic-v1");
      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
          () -> LocalMerchantAcceptanceServer.ensureSubjectLookupPolicy(db.jdbc, "different-version"));
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  configurable -> {
                    var beans = (GenericApplicationContext) configurable;
                    beans.registerBean("merchantLiveDataSource", DataSource.class, () -> db.source);
                    beans.registerBean("merchantLiveIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "merchantLiveWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "merchantLiveProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "merchantLiveSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(
                                protector, "live-synthetic-v1", subjectHmac));
                    beans.registerBean(
                        "merchantLiveGrantKey",
                        PrivateAssetGrantKeyProvider.class,
                        () ->
                            () ->
                                new PrivateAssetGrantKeyProvider.KeyMaterial(
                                    "live-synthetic-v1",
                                    new SecretKeySpec(grantHmac, "HmacSHA256")));
                    beans.registerBean(
                        "merchantLiveReason",
                        PrivateAssetReasonProtector.class,
                        () ->
                            (purpose, value) ->
                                sha256Bytes(
                                    (purpose + ":" + value)
                                        .getBytes(StandardCharsets.UTF_8)));
                  })
              .run(
                  "--server.port=0",
                  "--spring.flyway.enabled=false",
                  "--spring.main.banner-mode=off",
                  "--spring.jmx.enabled=false",
                  "--pet.auth.c.enabled=true",
                  "--pet.auth.c.redis-host=" + db.redisHost,
                  "--pet.auth.c.redis-port=" + db.redisPort,
                  "--pet.auth.c.cache-prefix=" + db.prefix,
                  "--pet.auth.admin.enabled=true",
                  "--pet.auth.admin.migration-enabled=false",
                  "--pet.auth.admin.origin=" + ADMIN_ORIGIN,
                  "--pet.auth.admin.redis-host=" + db.redisHost,
                  "--pet.auth.admin.redis-port=" + db.redisPort,
                  "--pet.auth.admin.cache-prefix=" + db.name + "_admin:",
                  "--pet.auth.admin.key-id=live-synthetic-key",
                  "--pet.auth.admin.mac-key-base64=" + Base64.getEncoder().encodeToString(adminMac),
                  "--pet.auth.admin.encryption-key-base64="
                      + Base64.getEncoder().encodeToString(adminAes),
                  "--pet.auth.admin.audit-path=" + db.directory.resolve("merchant-live-audit.bin"),
                  "--pet.private-assets.enabled=true",
                  "--pet.private-assets.worker-owner=merchant-live-acceptance",
                  "--PRIVATE_ASSET_GRANT_KEY_VERSION=live-synthetic-v1",
                  "--PRIVATE_ASSET_REASON_KEY_VERSION=live-synthetic-v1",
                  "--PRIVATE_ASSET_CLAMAV_HOST=127.0.0.1",
                  "--PRIVATE_ASSET_CLAMAV_PORT=13310",
                  "--PRIVATE_ASSET_CLAMAV_TIMEOUT_MILLIS=10000",
                  "--pet.merchant.application.enabled=true",
                  "--pet.merchant.application.open-cities[0].code=chengdu",
                  "--pet.merchant.application.open-cities[0].name=成都",
                  "--pet.merchant.application.notifications-enabled=true",
                  "--pet.outbox.enabled=true",
                  "--pet.merchant.map.enabled=false");
      String origin =
          "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      context
          .getBean(AdminAuthService.class)
          .bootstrap(
              "synthetic-reviewer",
              "Synthetic Reviewer",
              ADMIN_PASSWORD.toCharArray(),
              "Manual synthetic provider acceptance");

      Map<String, Object> login = consumerLogin(origin);
      String consumerToken = str(login, "accessToken"), ownerId = str(login, "userId");
      byte[] image = PrivateAssetLiveSupport.syntheticTestPng();
      for (int index = 0; index < 4; index++) {
        Reply uploaded = upload(origin, consumerToken, image);
        assertEquals(201, uploaded.status(), uploaded.redacted());
        assertEquals("READY", uploaded.data().get("status"));
        privateAssetIds.add(Long.parseLong(str(uploaded.data(), "assetId")));
      }
      System.out.println("MERCHANT_LIVE stage=private-assets result=PASS count=4");

      Map<String, Object> draft = syntheticDraft(location, privateAssetIds);
      Reply created =
          send(origin, "POST", "/api/v1/c/merchant-applications", draft, bearer(consumerToken));
      assertEquals(201, created.status(), created.redacted());
      String applicationId = str(created.data(), "applicationId");
      Reply submitted =
          send(
              origin,
              "POST",
              "/api/v1/c/merchant-applications/" + applicationId + "/submit",
              Map.of(
                  "expectedVersion",
                  str(created.data(), "version"),
                  "revisionId",
                  str(created.data(), "currentRevisionId")),
              bearer(consumerToken));
      assertEquals(200, submitted.status(), submitted.redacted());
      System.out.println("MERCHANT_LIVE stage=location-input-submit result=PASS externalMapCall=false");

      String adminToken = adminLogin(origin, db, adminMac, adminAes);
      String reviewPath = "/api/v1/admin/merchant-applications/" + applicationId;
      Reply review = send(origin, "GET", reviewPath, null, bearer(adminToken));
      Reply claimed =
          send(
              origin,
              "POST",
              reviewPath + "/claim",
              Map.of("expectedTaskVersion", str(map(review.data().get("task")), "version")),
              bearer(adminToken));
      assertEquals(200, claimed.status(), claimed.redacted());
      Reply readGrant =
          send(
              origin,
              "POST",
              reviewPath
                  + "/private-assets/"
                  + privateAssetIds.get(1)
                  + "/read-grants",
              Map.of(
                  "submissionRevisionId",
                  str(submitted.data(), "currentRevisionId"),
                  "purposeCode",
                  "APPLICATION_REVIEW",
                  "reason",
                  "SYNTHETIC TEST one-time watermarked review",
                  "confirmed",
                  true),
              bearer(adminToken));
      assertEquals(200, readGrant.status(), readGrant.redacted());
      String readUrl = str(readGrant.data(), "readUrl");
      HttpResponse<byte[]> watermarked = getBytes(origin, readUrl, bearer(adminToken));
      assertEquals(200, watermarked.statusCode());
      assertEquals(
          "no-store, private", watermarked.headers().firstValue("Cache-Control").orElse(null));
      String watermarkedType =
          watermarked.headers().firstValue("Content-Type").orElse("").split(";", 2)[0];
      assertTrue(Set.of("image/jpeg", "image/png").contains(watermarkedType));
      BufferedImage decodedWatermark =
          ImageIO.read(new ByteArrayInputStream(watermarked.body()));
      assertNotNull(decodedWatermark);
      assertTrue(decodedWatermark.getWidth() > 0 && decodedWatermark.getHeight() > 0);
      decodedWatermark.flush();
      Reply consumedAgain = send(origin, "GET", readUrl, null, bearer(adminToken));
      assertEquals(410, consumedAgain.status(), consumedAgain.redacted());
      System.out.println("MERCHANT_LIVE stage=one-time-watermarked-read result=PASS");
      List<Map<String, Object>> evidence = credentialEvidence(db, applicationId);
      Reply verified =
          send(
              origin,
              "POST",
              reviewPath + "/manual-verification",
              Map.of(
                  "submissionRevisionId",
                  str(submitted.data(), "currentRevisionId"),
                  "expectedVersion",
                  str(submitted.data(), "version"),
                  "expectedTaskVersion",
                  str(claimed.data(), "version"),
                  "evidenceItems",
                  evidence,
                  "reason",
                  "SYNTHETIC TEST evidence checked through real providers",
                  "confirmed",
                  true),
              bearer(adminToken));
      assertEquals(200, verified.status(), verified.redacted());
      Reply approved =
          send(
              origin,
              "POST",
              reviewPath + "/decision",
              Map.of(
                  "decisionType",
                  "APPROVE",
                  "submissionRevisionId",
                  str(submitted.data(), "currentRevisionId"),
                  "expectedVersion",
                  str(verified.data(), "version"),
                  "expectedTaskVersion",
                  str(claimed.data(), "version"),
                  "confirmed",
                  true),
              bearer(adminToken));
      assertEquals(200, approved.status(), approved.redacted());
      assertEquals("APPROVED", approved.data().get("status"));
      System.out.println("MERCHANT_LIVE stage=manual-review-approval result=PASS");

      String merchantId = str(approved.data(), "reservedMerchantId");
      seedSyntheticAgreement(db, merchantId);
      Reply agreement =
          send(
              origin,
              "GET",
              "/api/v1/merchant/agreement?merchantId=" + merchantId,
              null,
              bearer(consumerToken));
      String agreementHash = str(agreement.data(), "contentSha256");
      Reply consent =
          send(
              origin,
              "POST",
              "/api/v1/merchant/agreement/consent",
              Map.of(
                  "merchantId",
                  merchantId,
                  "agreementVersion",
                  "live-synthetic-v1",
                  "contentSha256",
                  agreementHash,
                  "accepted",
                  true),
              bearer(consumerToken));
      assertEquals(201, consent.status(), consent.redacted());
      assertEquals("SIGNED", consent.data().get("signingStatus"));
      System.out.println("MERCHANT_LIVE stage=agreement-consent result=PASS");
      long notificationDeadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
      int inboxCount;
      do {
        inboxCount =
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE receiver_id=? AND mandatory_inbox=1",
                Integer.class,
                Long.parseLong(ownerId));
        if (inboxCount == 0) Thread.sleep(100);
      } while (inboxCount == 0 && System.nanoTime() < notificationDeadline);
      assertTrue(inboxCount >= 1, "approval event must produce an applicant inbox notification");
      assertTrue(
          db.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM integration_event_outbox WHERE aggregate_id=?",
                  Integer.class,
                  Long.parseLong(applicationId))
              >= 1);
      System.out.println("MERCHANT_LIVE stage=approval-inbox-notification result=PASS");
    } finally {
      List<Long> cleanupIds = privateAssetIds;
      try {
        cleanupIds = db.jdbc.queryForList("SELECT id FROM private_asset", Long.class);
      } catch (RuntimeException schemaUnavailable) {
        cleanupFailures++;
      }
      for (Long assetId : cleanupIds)
        cleanupFailures += PrivateAssetLiveSupport.cleanupFixtureAsset(db.jdbc, assetId, oss);
      System.out.println("MERCHANT_LIVE stage=exact-object-cleanup failures=" + cleanupFailures);
      if (context != null) context.close();
      cleanupAdminRedis(db);
      db.close();
      wipe(protectedAes, protectedHmac, subjectHmac, adminMac, adminAes, grantHmac);
    }
    assertEquals(0, cleanupFailures, "all fixture-owned source/final objects must be deleted");
  }

  private Map<String, Object> consumerLogin(String origin) throws Exception {
    Reply attempt =
        send(
            origin,
            "POST",
            "/api/v1/c/auth/attempts",
            Map.of("purpose", "WECHAT_LOGIN"),
            Map.of("X-Request-Id", UUID.randomUUID().toString()));
    Reply grant =
        send(
            origin,
            "POST",
            "/api/v1/c/auth/wechat-login",
            Map.of(
                "attemptId",
                str(attempt.data(), "attemptId"),
                "wechatCode",
                "ok:merchant-live-synthetic",
                "phoneCode",
                "phone:13800007601"),
            Map.of(
                "X-Request-Id",
                UUID.randomUUID().toString(),
                "X-Auth-Attempt",
                str(attempt.data(), "attemptToken")));
    assertEquals(200, grant.status(), grant.redacted());
    return grant.data();
  }

  private String adminLogin(String origin, CAuthHttpTest.HttpFixture db, byte[] mac, byte[] aes)
      throws Exception {
    Reply attempt =
        send(
            origin,
            "POST",
            "/api/v1/admin/auth/attempts",
            Map.of(),
            Map.of("Origin", ADMIN_ORIGIN, "X-Request-Id", UUID.randomUUID().toString()));
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin",
                ADMIN_ORIGIN,
                "Cookie",
                attempt.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                "X-Auth-Attempt",
                str(attempt.data(), "attemptToken"),
                "X-Request-Id",
                UUID.randomUUID().toString()));
    Reply challenge =
        send(
            origin,
            "POST",
            "/api/v1/admin/auth/captcha/challenges",
            Map.of("attemptId", attemptId),
            headers);
    db.jdbc.update(
        "UPDATE admin_captcha SET answer_mac=? WHERE id=?",
        AdminSecretCodec.fixed("live-synthetic-key", mac, aes)
            .mac("live-synthetic-key", "CAPTCHA", "ABC234"),
        Long.parseLong(str(challenge.data(), "captchaId")));
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply proof =
        send(
            origin,
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
    headers.put("X-Request-Id", UUID.randomUUID().toString());
    Reply login =
        send(
            origin,
            "POST",
            "/api/v1/admin/auth/login",
            Map.of(
                "attemptId",
                attemptId,
                "account",
                "synthetic-reviewer",
                "password",
                ADMIN_PASSWORD,
                "captchaProof",
                str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, login.status(), login.redacted());
    return str(login.data(), "accessToken");
  }

  private Reply upload(String origin, String token, byte[] content) throws Exception {
    String boundary = "----merchant-live-" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "MERCHANT_APPLICATION_MATERIAL\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"synthetic-test.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/v1/c/private-assets"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return reply(response);
  }

  private Reply send(
      String origin, String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(30));
    headers.forEach(request::header);
    if (body != null) request.header("Content-Type", "application/json");
    return reply(
        http.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString()));
  }

  private HttpResponse<byte[]> getBytes(
      String origin, String path, Map<String, String> headers) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(30)).GET();
    headers.forEach(request::header);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @SuppressWarnings("unchecked")
  private Reply reply(HttpResponse<String> response) throws Exception {
    return new Reply(
        response.statusCode(), json.readValue(response.body(), Map.class), response.headers());
  }

  private static Map<String, Object> syntheticDraft(LiveLocation location, List<Long> ids) {
    return new LinkedHashMap<>(
        Map.ofEntries(
            Map.entry("merchantName", "合成测试宠物生活馆"),
            Map.entry("contactName", "张三"),
            Map.entry("contactPhone", "13800007601"),
            Map.entry("email", "synthetic@example.invalid"),
            Map.entry("merchantTypeCode", "PET_LIFE_STORE"),
            Map.entry("cityCode", "chengdu"),
            Map.entry("address", location.address()),
            Map.entry("longitude", location.longitude().toPlainString()),
            Map.entry("latitude", location.latitude().toPlainString()),
            Map.entry("introduction", "SYNTHETIC TEST ONLY"),
            Map.entry("storePhotoAssetIds", List.of(ids.get(0).toString())),
            Map.entry("businessLicenseAssetId", ids.get(1).toString()),
            Map.entry("idCardFrontAssetId", ids.get(2).toString()),
            Map.entry("idCardBackAssetId", ids.get(3).toString())));
  }

  private static List<Map<String, Object>> credentialEvidence(
      CAuthHttpTest.HttpFixture db, String applicationId) {
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (String type : List.of("BUSINESS_LICENSE", "ID_CARD_BACK")) {
      Map<String, Object> row =
          db.jdbc.queryForMap(
              "SELECT id,sha256 FROM merchant_application_material WHERE application_id=?"
                  + " AND material_type=?",
              Long.parseLong(applicationId),
              type);
      evidence.add(
          Map.of(
              "materialId",
              row.get("id").toString(),
              "materialSha256",
              row.get("sha256").toString(),
              "credentialType",
              type.equals("BUSINESS_LICENSE") ? "CREDIT_CODE" : "IDENTITY_NUMBER",
              "subjectName",
              type.equals("BUSINESS_LICENSE") ? "合成测试宠物生活馆" : "张三",
              "identifier",
              type.equals("BUSINESS_LICENSE") ? syntheticCreditCode() : syntheticIdentity(),
              "validFrom",
              "2020-01-01",
              "validityKind",
              "LONG_TERM"));
    }
    return evidence;
  }

  private static void seedSyntheticAgreement(CAuthHttpTest.HttpFixture db, String merchantId) {
    long id = db.ids.nextId();
    String content = "SYNTHETIC TEST merchant agreement";
    String hash = sha256(content);
    db.jdbc.update(
        "INSERT INTO merchant_agreement_version"
            + "(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),(SELECT id FROM admin_account"
            + " WHERE account_display='synthetic-reviewer'))",
        id,
        "live-synthetic-v1",
        content,
        hash);
    db.jdbc.update(
        "INSERT INTO merchant_agreement_current"
            + "(agreement_key,agreement_version_id,version,updated_at)"
            + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
        id);
  }

  private static void cleanupAdminRedis(CAuthHttpTest.HttpFixture db) {
    var redis = io.lettuce.core.RedisClient.create("redis://" + db.redisHost + ":" + db.redisPort);
    try (var connection = redis.connect()) {
      for (String key : connection.sync().keys(db.name + "_admin:*")) connection.sync().del(key);
    } catch (RuntimeException ignored) {
      System.err.println("MERCHANT_LIVE cleanup=ADMIN_REDIS_FAILED");
    } finally {
      redis.shutdown();
    }
  }

  private static String syntheticIdentity() {
    String body = "51010419900101001";
    int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    int sum = 0;
    for (int index = 0; index < 17; index++) sum += (body.charAt(index) - '0') * weights[index];
    return body + "10X98765432".charAt(sum % 11);
  }

  private static String syntheticCreditCode() {
    String body = "91510100MA0000000", alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    int sum = 0;
    for (int index = 0; index < 17; index++)
      sum += alphabet.indexOf(body.charAt(index)) * weights[index];
    return body + alphabet.charAt((31 - sum % 31) % 31);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] randomKey() {
    byte[] value = new byte[32];
    new SecureRandom().nextBytes(value);
    return value;
  }

  private static void wipe(byte[]... values) {
    for (byte[] value : values) Arrays.fill(value, (byte) 0);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static String str(Map<String, Object> value, String key) {
    return assertInstanceOf(String.class, value.get(key));
  }

  private static Map<String, String> bearer(String token) {
    return Map.of(
        "Authorization", "Bearer " + token, "X-Request-Id", UUID.randomUUID().toString());
  }

  private record Reply(int status, Map<String, Object> envelope, HttpHeaders headers) {
    Map<String, Object> data() {
      return map(envelope.get("data"));
    }

    String redacted() {
      return "HTTP " + status + " code=" + envelope.get("code");
    }
  }

  private record LiveLocation(String address, BigDecimal longitude, BigDecimal latitude) {
    static LiveLocation fromEnvironment() {
      String address =
          System.getenv().getOrDefault("MERCHANT_LIVE_TEST_ADDRESS", "仅用于联调的测试地址");
      BigDecimal longitude =
          new BigDecimal(System.getenv().getOrDefault("MERCHANT_LIVE_TEST_LONGITUDE", "104.0665"));
      BigDecimal latitude =
          new BigDecimal(System.getenv().getOrDefault("MERCHANT_LIVE_TEST_LATITUDE", "30.5728"));
      if (address.isBlank()
          || address.codePointCount(0, address.length()) > 255
          || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
          || longitude.compareTo(BigDecimal.valueOf(180)) > 0
          || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
          || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
        throw new IllegalArgumentException("Explicit public test location is outside contract bounds");
      }
      return new LiveLocation(address, longitude, latitude);
    }
  }

  private static byte[] sha256Bytes(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
