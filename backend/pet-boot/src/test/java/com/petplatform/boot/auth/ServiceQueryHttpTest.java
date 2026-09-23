package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.ApiException;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantQueryApiImpl;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.user.biz.application.WechatSessionProvider;
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
 * CCR-W2-API-001 service domain acceptance (W2-SVC-001..003): a REAL approved-and-signed
 * merchant/store pair plus SQL-seeded service_item rows (SVC-D4: no writer exists yet) drive the
 * C catalog. Visibility follows the four-condition conjunction; detail 404 is indistinguishable;
 * facts failures answer 503 and are never conflated with confirmed absence or ineligibility.
 */
class ServiceQueryHttpTest {
  private static final String ORIGIN = "https://svc.example.invalid";
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("svc-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.merchant.application.notifications-enabled", true);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.query.enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "svcDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("svcIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "svcWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "svcProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "svcPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "svcMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试服务地址"));
                    beans.registerBean(
                        "svcSubjects",
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
              "Isolated service catalog acceptance");
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
  void catalogVisibilityBookabilityAndSnapshotFollowApprovedContract() throws Exception {
    // Real flow first: application -> review -> APPROVE creates the merchant/store pair, then the
    // owner signs the agreement so acceptsNewOrders becomes true (five-field projection).
    Map<String, Object> owner = consumerLogin("svc-owner", "13800007711");
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long id = 201; id <= 204; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("svc-asset-" + id), "image/jpeg", 512, "READY"));
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
              "subjectName", license ? "星河服务生活馆" : "张三",
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
    long merchantId = Long.parseLong(String.valueOf(approved.data().get("reservedMerchantId")));
    long storeId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class, merchantId);
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "svc-http-v1",
        content,
        hash,
        db.jdbc.queryForObject(
            "SELECT id FROM admin_account WHERE account_display='qa-reviewer'", Long.class));
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
            + " VALUES('MERCHANT',?,0,UTC_TIMESTAMP(3))",
        agreementId);
    Reply signed =
        send(
            "POST",
            "/api/v1/merchant/agreement/consent",
            Map.of(
                "merchantId", String.valueOf(merchantId),
                "agreementVersion", "svc-http-v1",
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());

    // SVC-D4: no service writer exists in V1 yet, so acceptance seeds service_item via SQL.
    long categoryId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        categoryId, "美容");
    long groomingId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " service_item(id,merchant_id,store_id,category_id,service_name,description,price,"
            + " duration_minutes,fulfillment_type,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,'IN_STORE','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        groomingId, merchantId, storeId, categoryId, "宠物美容-基础洗护", "含洗护、吹干、基础梳理",
        new java.math.BigDecimal("128.00"), 45);
    long offlineId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " service_item(id,merchant_id,store_id,category_id,service_name,description,price,"
            + " duration_minutes,fulfillment_type,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,'IN_STORE','OFFLINE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        offlineId, merchantId, storeId, categoryId, "已下架服务", null,
        new java.math.BigDecimal("88.00"), 30);
    long draftId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " service_item(id,merchant_id,store_id,category_id,service_name,description,price,"
            + " duration_minutes,fulfillment_type,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,'IN_STORE','DRAFT',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        draftId, merchantId, storeId, categoryId, "草稿服务", null,
        new java.math.BigDecimal("66.00"), 20);

    String storePath = "/api/v1/c/stores/" + storeId + "/services";
    String groomingApi = String.valueOf(groomingId);

    // W2-SVC-001/003: visible page carries only ACTIVE services with page fields and money String.
    Reply list = send("GET", storePath + "?page=1&pageSize=20", null, bearer(token));
    assertEquals(200, list.status(), list.redacted());
    assertEquals(1, ((List<?>) list.data().get("items")).size());
    assertEquals(1L, ((Number) list.data().get("total")).longValue());
    Map<String, Object> item = map(((List<?>) list.data().get("items")).get(0));
    assertEquals(groomingApi, item.get("serviceId"));
    assertEquals("宠物美容-基础洗护", item.get("serviceName"));
    assertEquals("128.00", item.get("salePrice"));
    assertEquals(45, ((Number) item.get("durationMinutes")).intValue());
    assertEquals("IN_STORE", item.get("fulfillmentType"));
    assertFalse(item.containsKey("description"), "list items must not carry the full description");
    assertEquals(1, ((Number) list.data().get("page")).intValue());
    assertEquals(20, ((Number) list.data().get("pageSize")).intValue());

    // Detail: visible service answers the snapshot projection.
    Reply detail = send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token));
    assertEquals(200, detail.status(), detail.redacted());
    assertEquals("含洗护、吹干、基础梳理", detail.data().get("description"));
    assertEquals("美容", detail.data().get("categoryName"));

    // W2-SVC-002: snapshot is a value copy - later master-data changes never mutate it.
    ServiceQueryApiImpl api = context.getBean(ServiceQueryApiImpl.class);
    QueryContext ctx = new QueryContext("svc-test", OperatorType.USER, ownerId);
    ServiceSnapshotDTO firstDetailBody =
        api.getServiceSnapshot(new ServiceSnapshotQuery(groomingApi, ctx));
    db.jdbc.update(
        "UPDATE service_item SET price=?, service_name=? WHERE id=?",
        new java.math.BigDecimal("158.00"), "宠物美容-基础洗护改价", groomingId);
    Reply after = send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token));
    assertEquals(200, after.status(), after.redacted());
    assertEquals("158.00", after.data().get("salePrice"));
    assertEquals("宠物美容-基础洗护改价", after.data().get("serviceName"));
    assertEquals(
        new java.math.BigDecimal("128.00"), firstDetailBody.salePrice(), "returned copy must not track later changes");
    assertEquals("宠物美容-基础洗护", firstDetailBody.serviceName());

    // Visibility negatives: OFFLINE/DRAFT/unknown are 404, indistinguishable.
    assertEquals(404, send("GET", "/api/v1/c/services/" + offlineId, null, bearer(token)).status());
    assertEquals(404, send("GET", "/api/v1/c/services/" + draftId, null, bearer(token)).status());
    assertEquals(
        404, send("GET", "/api/v1/c/services/" + (groomingId + 999_999), null, bearer(token)).status());
    // A store with no seeded services answers an empty page, never an error.
    Reply emptyStore =
        send("GET", "/api/v1/c/stores/" + (storeId + 999_999) + "/services", null, bearer(token));
    assertEquals(200, emptyStore.status(), emptyStore.redacted());
    assertTrue(((List<?>) emptyStore.data().get("items")).isEmpty());

    // Merchant disabled: list hides everything and detail 404s (confirmed ineligible, not 503).
    db.jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", merchantId);
    assertEquals(200, send("GET", storePath, null, bearer(token)).status());
    assertTrue(
        ((List<?>) send("GET", storePath, null, bearer(token)).data().get("items")).isEmpty());
    assertEquals(404, send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token)).status());
    // Internal bookability keeps reasons for ORD/SCH even while hidden.
    ServiceBookabilityDTO disabled =
        api.checkBookable(new ServiceBookabilityQuery(groomingApi, String.valueOf(storeId), ctx));
    assertFalse(disabled.bookable());
    assertTrue(disabled.reasonCodes().contains("MERCHANT_DISABLED"));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);

    // Store frozen: same confirmed-ineligible hiding.
    db.jdbc.update("UPDATE merchant_store SET status='FROZEN' WHERE id=?", storeId);
    assertEquals(404, send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token)).status());
    assertTrue(
        ((List<?>) send("GET", storePath, null, bearer(token)).data().get("items")).isEmpty());
    db.jdbc.update("UPDATE merchant_store SET status='ACTIVE' WHERE id=?", storeId);

    // Unknown stored merchant status: fail closed 503, never conflated with hiding.
    db.jdbc.update("UPDATE merchant SET status='CORRUPTED' WHERE id=?", merchantId);
    assertEquals(
        503, send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token)).status());
    assertEquals(503, send("GET", storePath, null, bearer(token)).status());
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);

    // Restored: visible again.
    assertEquals(200, send("GET", "/api/v1/c/services/" + groomingId, null, bearer(token)).status());

    // W2-SVC-003: argument and session errors. STR-D8 (store-read ruling, PRD "all users
    // browse"): the browse routes accept an anonymous GET, so no bearer is 200 - this assertion
    // changed from 401 by the user-approved contract change - while a carried invalid bearer
    // still 401s.
    assertEquals(400, send("GET", "/api/v1/c/services/not-a-number", null, bearer(token)).status());
    assertEquals(400, send("GET", storePath + "?page=0", null, bearer(token)).status());
    assertEquals(400, send("GET", storePath + "?pageSize=51", null, bearer(token)).status());
    assertEquals(200, send("GET", "/api/v1/c/services/" + groomingId, null, Map.of()).status());
    assertEquals(200, send("GET", storePath, null, Map.of()).status());
    assertEquals(
        401, send("GET", "/api/v1/c/services/" + groomingId, null, bearer("invalid-token")).status());
    assertEquals(
        400, send("GET", "/api/v1/c/services/" + groomingId + "?extra=1", null, bearer(token)).status());

    // SVC-D5 negative: the owner-scoped eligibility contract stays owner-only; a consumer who is
    // NOT the owner must not reuse it (the display query is the only C-side facts path). The
    // submitting user here IS the owner, so probe with a second, unrelated consumer session.
    Map<String, Object> other = consumerLogin("svc-other", "13800007712");
    QueryContext otherCtx = new QueryContext("svc-test-other", OperatorType.USER, str(other, "userId"));
    MerchantQueryApiImpl ownerScoped = context.getBean(MerchantQueryApiImpl.class);
    ApiException ownerScopedRejected =
        assertThrows(
            ApiException.class,
            () ->
                ownerScoped.checkOrderEligibility(
                    new MerchantOrderEligibilityQuery(
                        String.valueOf(merchantId), String.valueOf(storeId), otherCtx)));
    assertEquals(com.petplatform.common.CommonApiCodes.NOT_FOUND, ownerScopedRejected.code());
  }

  private static Map<String, Object> draft() {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "星河服务生活馆");
    draft.put("contactName", "张三");
    draft.put("contactPhone", "13800007711");
    draft.put("email", "svc-owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of("201"));
    draft.put("businessLicenseAssetId", "202");
    draft.put("idCardFrontAssetId", "203");
    draft.put("idCardBackAssetId", "204");
    return draft;
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
                "attemptId", str(attempt.data(), "attemptId"),
                "wechatCode", "ok:" + openid,
                "phoneCode", "phone:" + phone),
            Map.of(
                "X-Request-Id", UUID.randomUUID().toString(),
                "X-Auth-Attempt", str(attempt.data(), "attemptToken")));
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
    assertEquals(201, attempt.status(), attempt.redacted());
    String attemptId = str(attempt.data(), "attemptId");
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Origin", ORIGIN,
                "Cookie", attempt.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                "X-Auth-Attempt", str(attempt.data(), "attemptToken"),
                "X-Request-Id", UUID.randomUUID().toString()));
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
                "attemptId", attemptId,
                "captchaId", str(challenge.data(), "captchaId"),
                "answer", "ABC234"),
            headers);
    assertEquals(200, proof.status(), proof.redacted());
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
                        : HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response.headers());
  }

  private static Map<String, String> bearer(String token) {
    return Map.of("Authorization", "Bearer " + token);
  }

  private static Map<String, Object> map(Object value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) value;
    return result;
  }

  private static String str(Map<String, Object> value, String field) {
    return String.valueOf(value.get(field));
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

  private static byte[] key(int seed) {
    byte[] value = new byte[32];
    new SecureRandom(String.valueOf(seed).getBytes(StandardCharsets.UTF_8)).nextBytes(value);
    return value;
  }

  private static String sha(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private record Reply(int status, Map<String, Object> envelope, java.net.http.HttpHeaders headers) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + "]";
    }
  }
}
