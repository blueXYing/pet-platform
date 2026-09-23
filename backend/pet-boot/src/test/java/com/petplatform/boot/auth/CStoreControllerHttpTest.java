package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantStoreDisplayPageQuery;
import com.petplatform.merchant.api.query.StoreIdQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantQueryApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreDisplayApiImpl;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import com.petplatform.thirdparty.biz.apiimpl.PrivateAssetApiImpl;
import com.petplatform.thirdparty.biz.application.port.PrivateAssetScanner;
import com.petplatform.thirdparty.biz.application.port.PrivateObjectStore;
import com.petplatform.user.biz.application.WechatSessionProvider;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
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
 * CCR-W2-API-001 store read acceptance (W2-STR-001..006): a REAL approved-and-signed
 * merchant/store pair (application -> review -> APPROVE -> agreement consent) plus SQL-seeded
 * counterexamples drive the C store catalog. Visibility follows the approved three-condition
 * conjunction; detail 404 is STORE_NOT_FOUND and indistinguishable; facts failures and the
 * compat-missing integrity counterexample answer a whole-page 503 and are repaired through the
 * INTEGRITY-RUNBOOK path; the four browse routes accept an anonymous GET (STR-D8) while a carried
 * invalid bearer still 401s; SERVICE_COVER uploads stay scoped to merchant main accounts.
 */
class CStoreControllerHttpTest {
  private static final String ORIGIN = "https://store.example.invalid";
  private static final String PASSWORD = "Example_ONLY_93!";
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
              "29-Merchant-Application-Schema-v0.1.sql",
              "13-Async-Infra-Schema-v0.1.sql",
              "31-Private-Asset-Schema-v0.1.sql")) {
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("store-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.merchant.application.notifications-enabled", true);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.query.enabled", true);
    props.put("pet.store.query.enabled", true);
    props.put("pet.private-assets.enabled", true);
    props.put("pet.private-assets.worker-owner", "qa-store-read-worker");
    props.put("PRIVATE_ASSET_GRANT_KEY_VERSION", "qa-grant-v1");
    props.put("PRIVATE_ASSET_GRANT_HMAC_KEY_BASE64", base64(41));
    props.put("PRIVATE_ASSET_REASON_KEY_VERSION", "qa-reason-v1");
    props.put("PRIVATE_ASSET_REASON_AES_KEY_BASE64", base64(53));
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "storeDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean("storeIds", SnowflakeIdGenerator.class, () -> db.ids);
                    beans.registerBean(
                        "storeWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "storeProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "storePrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "storeMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () ->
                            (city, address, lng, lat) ->
                                city.equals("chengdu") && address.equals("测试门店地址"));
                    beans.registerBean(
                        "storeSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                    beans.registerBean(
                        "storeObjects",
                        com.petplatform.thirdparty.biz.application.port.PrivateObjectStore.class,
                        MemoryObjects::new);
                    beans.registerBean(
                        "storeScanner",
                        PrivateAssetScanner.class,
                        () ->
                            content ->
                                new PrivateAssetScanner.ScanResult(true, "QA_SCANNER_1", "CLEAN"));
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
              "Isolated store catalog acceptance");
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
  void catalogVisibilityPaginationCityIntegrityAnonymousAndServiceCoverFollowApprovedContract()
      throws Exception {
    // Real chain: application -> review -> APPROVE creates merchant/store/compat, then the owner
    // signs so acceptsNewOrders becomes true.
    Map<String, Object> owner = consumerLogin("store-owner", "13800007721");
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long id = 301; id <= 304; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("store-asset-" + id), "image/jpeg", 512, "READY"));
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
              "subjectName", license ? "锦江区萌宠之家" : "李四",
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

    // W2-STR-001 N3: approved but unsigned breaks only acceptsNewOrders - hidden and 404 before
    // the consent, the exact approved-but-unsigned window the eligibility policy denies.
    Reply unsignedList = send("GET", "/api/v1/c/stores", null, bearer(token));
    assertEquals(200, unsignedList.status(), unsignedList.redacted());
    assertTrue(((List<?>) unsignedList.data().get("items")).isEmpty());
    assertEquals(0L, ((Number) unsignedList.data().get("total")).longValue());
    assertEquals(404, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());

    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "store-http-v1",
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
                "agreementVersion", "store-http-v1",
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());

    // No store writer exists in V1 (stores are born from the application chain), so the second
    // store of the same merchant is SQL-seeded like the SVC-D4 service rows; ordering uses a
    // much larger id so a lexical sort would fail.
    long storeIdBig = storeId + 900_000_000L;
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_store(id,merchant_id,store_name,address,longitude,latitude,phone,status,"
            + " version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        storeIdBig, merchantId, "萌宠之家·高新分店", "四川省成都市高新区天府大道XX号",
        new java.math.BigDecimal("104.0623451"), new java.math.BigDecimal("30.5412345"),
        "13900007721");
    long categoryId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        categoryId, "美容");
    long serviceId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO"
            + " service_item(id,merchant_id,store_id,category_id,service_name,description,price,"
            + " duration_minutes,fulfillment_type,status,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,'IN_STORE','ACTIVE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        serviceId, merchantId, storeId, categoryId, "门店列表联动-基础洗护", "含洗护",
        new java.math.BigDecimal("99.00"), 40);

    // W2-STR-002 P1/P3/P4: default (all open cities) equals the explicit chengdu filter; nine
    // fields, masked phone, no authority/always-true fields.
    Reply list = send("GET", "/api/v1/c/stores?page=1&pageSize=20", null, bearer(token));
    assertEquals(200, list.status(), list.redacted());
    assertEquals(2L, ((Number) list.data().get("total")).longValue());
    List<?> items = (List<?>) list.data().get("items");
    Map<String, Object> first = map(items.get(0));
    assertEquals(String.valueOf(storeId), first.get("storeId"));
    assertEquals(9, first.keySet().size());
    assertEquals(String.valueOf(merchantId), first.get("merchantId"));
    assertEquals("锦江区萌宠之家", first.get("storeName"));
    assertEquals("测试门店地址", first.get("address"));
    assertEquals("chengdu", first.get("cityCode"));
    // The chain-born store may legitimately carry no phone (phoneMasked is nullable); the
    // SQL-seeded second store asserts the masked projection shape.
    if (first.get("phoneMasked") != null)
      assertTrue(String.valueOf(first.get("phoneMasked")).contains("****"));
    for (String forbidden : List.of("merchantStatus", "storeStatus", "version", "bookability"))
      assertFalse(first.containsKey(forbidden), forbidden + " must not leak");
    Reply cityList = send("GET", "/api/v1/c/stores?city=chengdu", null, bearer(token));
    assertEquals(200, cityList.status(), cityList.redacted());
    assertEquals(list.data().get("total"), cityList.data().get("total"));

    // P2: numeric ordering (small id first although the second id has an extra digit), paging,
    // honest total on the far page.
    Reply pageOne = send("GET", "/api/v1/c/stores?pageSize=1&page=1", null, bearer(token));
    assertEquals(String.valueOf(storeId), str(map(((List<?>) pageOne.data().get("items")).get(0)), "storeId"));
    Reply pageTwo = send("GET", "/api/v1/c/stores?pageSize=1&page=2", null, bearer(token));
    Map<String, Object> second = map(((List<?>) pageTwo.data().get("items")).get(0));
    assertEquals(String.valueOf(storeIdBig), second.get("storeId"));
    assertEquals("139****7721", second.get("phoneMasked"));
    assertEquals(2L, ((Number) pageTwo.data().get("total")).longValue());
    assertTrue(((List<?>) send("GET", "/api/v1/c/stores?page=10000", null, bearer(token))
            .data().get("items")).isEmpty());

    // P5: detail shares the list field set.
    Reply detail = send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token));
    assertEquals(200, detail.status(), detail.redacted());
    assertEquals(first, detail.data());

    // P6/P7 (W2-STR-005): the four browse routes accept an anonymous GET; visibility is
    // subject-independent (same payload as the logged-in call above).
    assertEquals(200, send("GET", "/api/v1/c/stores", null, Map.of()).status());
    Reply anonymousDetail = send("GET", "/api/v1/c/stores/" + storeId, null, Map.of());
    assertEquals(200, anonymousDetail.status(), anonymousDetail.redacted());
    assertEquals(detail.data(), anonymousDetail.data());
    Reply anonymousServices =
        send("GET", "/api/v1/c/stores/" + storeId + "/services", null, Map.of());
    assertEquals(200, anonymousServices.status(), anonymousServices.redacted());
    assertEquals(1, ((List<?>) anonymousServices.data().get("items")).size());
    Reply anonymousService = send("GET", "/api/v1/c/services/" + serviceId, null, Map.of());
    assertEquals(200, anonymousService.status(), anonymousService.redacted());
    assertEquals("门店列表联动-基础洗护", anonymousService.data().get("serviceName"));

    // N7: a carried invalid bearer still 401s on all four routes (STR-D8: anonymity never masks
    // a bad credential).
    for (String path :
        List.of(
            "/api/v1/c/stores",
            "/api/v1/c/stores/" + storeId,
            "/api/v1/c/stores/" + storeId + "/services",
            "/api/v1/c/services/" + serviceId)) {
      assertEquals(401, send("GET", path, null, bearer("not-a-real-token")).status(), path);
    }

    // W2-STR-002 negatives: unknown/closed/invalid city, unknown parameters, paging bounds.
    for (String query :
        List.of(
            "city=beijing",
            "city=Abc",
            "city=",
            "sort=distance",
            "keyword=meng",
            "categoryId=1",
            "page=0",
            "pageSize=51",
            "page=abc",
            "city=chengdu&city=chengdu")) {
      assertEquals(400, send("GET", "/api/v1/c/stores?" + query, null, bearer(token)).status(), query);
    }
    assertEquals(400, send("GET", "/api/v1/c/stores/not-a-number", null, bearer(token)).status());
    assertEquals(400, send("GET", "/api/v1/c/stores/12345678901234567890123", null, bearer(token)).status());
    assertEquals(400, send("GET", "/api/v1/c/stores/" + storeId + "?extra=1", null, bearer(token)).status());

    // W2-STR-003 N4: absent store is 404 STORE_NOT_FOUND.
    Reply absent = send("GET", "/api/v1/c/stores/" + (storeId + 999_999), null, bearer(token));
    assertEquals(404, absent.status(), absent.redacted());
    assertEquals("STORE_NOT_FOUND", absent.envelope().get("code"));

    // W2-STR-001 N1: merchant OFFLINE hides the list (empty page 200, not 503) and the detail is
    // byte-shape indistinguishable from the absent-store answer.
    db.jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", merchantId);
    Reply hiddenList = send("GET", "/api/v1/c/stores", null, bearer(token));
    assertEquals(200, hiddenList.status(), hiddenList.redacted());
    assertTrue(((List<?>) hiddenList.data().get("items")).isEmpty());
    assertEquals(0L, ((Number) hiddenList.data().get("total")).longValue());
    Reply hiddenDetail = send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token));
    assertEquals(404, hiddenDetail.status(), hiddenDetail.redacted());
    assertEquals(absent.envelope().get("code"), hiddenDetail.envelope().get("code"));
    assertEquals(absent.envelope().get("message"), hiddenDetail.envelope().get("message"));
    // N11: the frozen-visibility store services route stays 200 empty (approved SVC semantics).
    Reply hiddenServices = send("GET", "/api/v1/c/stores/" + storeId + "/services", null, bearer(token));
    assertEquals(200, hiddenServices.status(), hiddenServices.redacted());
    assertTrue(((List<?>) hiddenServices.data().get("items")).isEmpty());
    assertEquals(404, send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token)).status());
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);

    // N2: store FROZEN hides only that store.
    db.jdbc.update("UPDATE merchant_store SET status='FROZEN' WHERE id=?", storeId);
    Reply frozenList = send("GET", "/api/v1/c/stores", null, bearer(token));
    assertEquals(200, frozenList.status(), frozenList.redacted());
    assertEquals(1L, ((Number) frozenList.data().get("total")).longValue());
    assertEquals(404, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());
    db.jdbc.update("UPDATE merchant_store SET status='ACTIVE' WHERE id=?", storeId);

    // W2-STR-004 N8: unknown merchant status is a facts failure: whole page 503, never an empty
    // page; the browse surface (services) fails closed the same way.
    db.jdbc.update("UPDATE merchant SET status='CORRUPTED' WHERE id=?", merchantId);
    assertEquals(503, send("GET", "/api/v1/c/stores", null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/stores?page=2&pageSize=1", null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/stores/" + storeId + "/services", null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token)).status());
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);
    assertEquals(200, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());

    // W2-STR-006 N8: the compat integrity counterexample - whole page 503 AND the runbook stock
    // check SQL (check A) flags exactly the seeded anomaly.
    db.jdbc.update("DELETE FROM merchant_profile_compat WHERE merchant_id=?", merchantId);
    assertEquals(503, send("GET", "/api/v1/c/stores", null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());
    assertEquals(
        1L,
        db.jdbc.queryForObject(
            "SELECT COUNT(DISTINCT m.id) FROM merchant m JOIN merchant_store s ON s.merchant_id=m.id"
                + " LEFT JOIN merchant_profile_compat p ON p.merchant_id=m.id"
                + " WHERE m.status='ACTIVE' AND s.status='ACTIVE' AND p.merchant_id IS NULL",
            Long.class),
        "runbook check A must flag the seeded anomaly (one merchant)");

    // N9: repair through the runbook's idempotent INSERT sourced from the approved revision.
    db.jdbc.update(
        "INSERT INTO merchant_profile_compat"
            + "(merchant_id,application_id,source_revision_id,merchant_type_code,city_code,"
            + " source_kind,version,created_at,updated_at)"
            + " SELECT a.reserved_merchant_id,a.id,a.submitted_revision_id,r.merchant_type_code,"
            + "r.city_code,'APPLICATION',0,NOW(3),NOW(3)"
            + " FROM merchant_application a JOIN merchant_application_revision r"
            + " ON r.id=a.submitted_revision_id"
            + " WHERE a.reserved_merchant_id=? AND a.status='APPROVED'"
            + " AND NOT EXISTS(SELECT 1 FROM merchant_profile_compat p WHERE p.merchant_id=?)",
        merchantId, merchantId);
    Reply repaired = send("GET", "/api/v1/c/stores", null, bearer(token));
    assertEquals(200, repaired.status(), repaired.redacted());
    assertEquals(2L, ((Number) repaired.data().get("total")).longValue());

    // N8 variant: malformed city code is the same integrity failure.
    db.jdbc.update(
        "UPDATE merchant_profile_compat SET city_code='Chengdu' WHERE merchant_id=?", merchantId);
    assertEquals(503, send("GET", "/api/v1/c/stores", null, bearer(token)).status());
    assertEquals(503, send("GET", "/api/v1/c/stores/" + storeId, null, bearer(token)).status());
    db.jdbc.update(
        "UPDATE merchant_profile_compat SET city_code='chengdu' WHERE merchant_id=? AND city_code='Chengdu'",
        merchantId);
    assertEquals(200, send("GET", "/api/v1/c/stores", null, bearer(token)).status());

    // N12: the fifth query has no ownership precondition; the owner-scoped getStore stays
    // owner-only for the same consumer (anti-impersonation negative).
    MerchantStoreDisplayApiImpl display = context.getBean(MerchantStoreDisplayApiImpl.class);
    QueryContext otherCtx = new QueryContext("store-test-other", OperatorType.USER, ownerId);
    assertEquals(
        String.valueOf(storeId),
        display
            .pageDisplayStores(new MerchantStoreDisplayPageQuery(List.of("chengdu"), 1, 20, otherCtx))
            .items()
            .get(0)
            .storeId());
    Map<String, Object> other = consumerLogin("store-other", "13800007722");
    QueryContext strangerCtx =
        new QueryContext("store-test-stranger", OperatorType.USER, str(other, "userId"));
    MerchantQueryApiImpl ownerScoped = context.getBean(MerchantQueryApiImpl.class);
    ApiException ownerScopedRejected =
        assertThrows(
            ApiException.class,
            () ->
                ownerScoped.getStore(
                    new StoreIdQuery(String.valueOf(storeId), strangerCtx)));
    assertEquals(CommonApiCodes.NOT_FOUND, ownerScopedRejected.code());

    // W2-STR-006/N13 (SERVICE_COVER, 31 supplement): uploads are scoped to merchant main
    // accounts; the owner passes the gate and the pipeline answer carries the receipt keys;
    // ownership isolation keeps other users from resolving the asset.
    Reply strangerCover =
        uploadServiceCover(str(other, "accessToken"), UUID.randomUUID().toString(), syntheticPng());
    assertEquals(403, strangerCover.status(), strangerCover.redacted());
    assertEquals("COMMON_FORBIDDEN", strangerCover.envelope().get("code"));
    String coverRequestId = UUID.randomUUID().toString();
    Reply ownerCover =
        uploadServiceCover(token, coverRequestId, syntheticPng());
    assertEquals(201, ownerCover.status(), ownerCover.redacted());
    assertEquals(
        Set.of("assetId", "status", "objectSha256", "mediaType", "bytes"),
        ownerCover.data().keySet());
    assertEquals("READY", ownerCover.data().get("status"));
    PrivateAssetApiImpl assets = context.getBean(PrivateAssetApiImpl.class);
    ApiException notOwned =
        assertThrows(
            ApiException.class,
            () ->
                assets.resolveOwned(
                    new ResolveOwnedPrivateAssetsQuery(
                        str(other, "userId"), List.of(str(ownerCover.data(), "assetId")),
                        "SERVICE_COVER")));
    assertEquals(CommonApiCodes.NOT_FOUND, notOwned.code());

    // STR-D8 boundary: a write route keeps the mandatory session (anonymous upload is 401).
    assertEquals(
        401,
        uploadServiceCover(null, UUID.randomUUID().toString(), syntheticPng()).status());
  }

  @Test
  void emptyOpenCityCatalogAndDisabledSwitchFailClosed() throws Exception {
    // The catalog bean exists but is unconfigured: the city range cannot be established, so the
    // route answers 503 - never an unfiltered full listing.
    try (CAuthHttpTest.HttpFixture isolated = new CAuthHttpTest.HttpFixture()) {
      try (var connection = isolated.source.getConnection()) {
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
      ConfigurableApplicationContext closed = null;
      try {
        closed =
            new SpringApplicationBuilder(PetPlatformApplication.class)
                .initializers(
                    ctx -> {
                      var beans = (GenericApplicationContext) ctx;
                      beans.registerBean(
                          "closedDataSource", javax.sql.DataSource.class, () -> isolated.source);
                      beans.registerBean(
                          "closedIds", SnowflakeIdGenerator.class, () -> isolated.ids);
                      beans.registerBean(
                          "closedWechat",
                          WechatSessionProvider.class,
                          CAuthHttpTest.FixedWechatProvider::new);
                      beans.registerBean(
                          "closedProtection",
                          ApplicationValidationPorts.ProtectedValuePort.class,
                          () -> protector);
                      beans.registerBean(
                          "closedPrivateAssets",
                          PrivateAssetQueryPort.class,
                          () -> (owner, ids) -> List.of());
                      beans.registerBean(
                          "closedMap",
                          ApplicationValidationPorts.MapValidationPort.class,
                          () ->
                              (city, address, lng, lat) ->
                                  city.equals("chengdu") && address.equals("测试门店地址"));
                      beans.registerBean(
                          "closedSubjects",
                          SubjectCredentialPort.class,
                          () ->
                              new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                    })
                .run(
                    "--server.port=0",
                    "--spring.flyway.enabled=false",
                    "--spring.main.banner-mode=off",
                    "--spring.jmx.enabled=false",
                    "--pet.auth.c.enabled=true",
                    "--pet.auth.c.redis-host=" + isolated.redisHost,
                    "--pet.auth.c.redis-port=" + isolated.redisPort,
                    "--pet.auth.c.cache-prefix=" + isolated.prefix,
                    "--pet.auth.admin.enabled=true",
                    "--pet.auth.admin.migration-enabled=false",
                    "--pet.auth.admin.origin=" + ORIGIN,
                    "--pet.auth.admin.redis-host=" + isolated.redisHost,
                    "--pet.auth.admin.redis-port=" + isolated.redisPort,
                    "--pet.auth.admin.cache-prefix=" + isolated.name + "_admin:",
                    "--pet.auth.admin.key-id=qa-key",
                    "--pet.auth.admin.mac-key-base64=" + Base64.getEncoder().encodeToString(adminMac),
                    "--pet.auth.admin.encryption-key-base64="
                        + Base64.getEncoder().encodeToString(adminAes),
                    "--pet.auth.admin.audit-path=" + isolated.directory.resolve("closed-audit.bin"),
                    "--pet.merchant.application.enabled=true",
                    "--pet.merchant.application.notifications-enabled=true",
                    "--pet.outbox.enabled=true",
                    "--pet.store.query.enabled=true");
        String closedOrigin =
            "http://127.0.0.1:" + closed.getEnvironment().getProperty("local.server.port");
        Reply unavailable =
            send(closedOrigin, "GET", "/api/v1/c/stores", null, Map.of());
        assertEquals(503, unavailable.status(), unavailable.redacted());
        assertEquals(
            "COMMON_DEPENDENCY_UNAVAILABLE", unavailable.envelope().get("code"));
      } finally {
        if (closed != null) closed.close();
      }
    }
    // And with the switch off the routes stay unreachable (N14): a bare context without the
    // store slice keeps the path in the global deny.
    try (CAuthHttpTest.HttpFixture isolated = new CAuthHttpTest.HttpFixture()) {
      ConfigurableApplicationContext off = null;
      try {
        off =
            new SpringApplicationBuilder(PetPlatformApplication.class)
                .initializers(
                    ctx -> {
                      var beans = (GenericApplicationContext) ctx;
                      beans.registerBean(
                          "offDataSource", javax.sql.DataSource.class, () -> isolated.source);
                      beans.registerBean("offIds", SnowflakeIdGenerator.class, () -> isolated.ids);
                      beans.registerBean(
                          "offWechat",
                          WechatSessionProvider.class,
                          CAuthHttpTest.FixedWechatProvider::new);
                    })
                .run(
                    "--server.port=0",
                    "--spring.flyway.enabled=false",
                    "--spring.main.banner-mode=off",
                    "--spring.jmx.enabled=false",
                    "--pet.auth.c.enabled=true",
                    "--pet.auth.c.redis-host=" + isolated.redisHost,
                    "--pet.auth.c.redis-port=" + isolated.redisPort,
                    "--pet.auth.c.cache-prefix=" + isolated.prefix,
                    "--pet.auth.admin.enabled=false");
        String offOrigin =
            "http://127.0.0.1:" + off.getEnvironment().getProperty("local.server.port");
        int status = send(offOrigin, "GET", "/api/v1/c/stores", null, Map.of()).status();
        assertTrue(status == 403 || status == 404, "switch off must keep the route unreachable");
      } finally {
        if (off != null) off.close();
      }
    }
  }

  private static Map<String, Object> draft() {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "锦江区萌宠之家");
    draft.put("contactName", "李四");
    draft.put("contactPhone", "13800007721");
    draft.put("email", "store-owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试门店地址");
    draft.put("longitude", "104.0812345");
    draft.put("latitude", "30.6571234");
    draft.put("storePhotoAssetIds", List.of("301"));
    draft.put("businessLicenseAssetId", "302");
    draft.put("idCardFrontAssetId", "303");
    draft.put("idCardBackAssetId", "304");
    return draft;
  }

  private Reply uploadServiceCover(String token, String requestId, byte[] png) throws Exception {
    String boundary = "----pet-cover-" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "SERVICE_COVER\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"cover.png\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.write(png);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(origin + "/api/v1/c/private-assets"))
            .timeout(Duration.ofSeconds(30))
            .header("X-Request-Id", requestId)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    Map<?, ?> envelope = json.readValue(response.body(), Map.class);
    assertTrue(envelope.get("traceId") instanceof String);
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response.headers());
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
    return send(origin, method, path, body, headers);
  }

  private Reply send(
      String base, String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
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

  private static String base64(int fill) {
    byte[] value = new byte[32];
    java.util.Arrays.fill(value, (byte) fill);
    return Base64.getEncoder().encodeToString(value);
  }

  private static byte[] syntheticPng() throws java.io.IOException {
    BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        image.setRGB(x, y, (x * 5 << 16) | (y * 11 << 8) | 0x77);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));
    return output.toByteArray();
  }

  private static String sha(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  /** In-memory immutable object double, same contract as the private-asset upload fixture. */
  private static final class MemoryObjects implements PrivateObjectStore {
    private final Map<String, StoredContent> values = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public StoredObject putIfAbsent(String objectKey, byte[] content, String mediaType, String sha256) {
      StoredContent candidate = new StoredContent(content, mediaType, sha256);
      StoredContent existing = values.putIfAbsent(objectKey, candidate);
      StoredContent stored = existing == null ? candidate : existing;
      if (!stored.sha256().equals(sha256)
          || !stored.mediaType().equals(mediaType)
          || !java.util.Arrays.equals(stored.content(), content))
        throw new IllegalStateException("immutable object mismatch");
      return new StoredObject("version:qa-v1", sha256, content.length, mediaType);
    }

    @Override
    public StoredContent get(String objectKey, String versionRef) {
      if (!"version:qa-v1".equals(versionRef)) throw new IllegalStateException("version mismatch");
      return values.get(objectKey);
    }

    @Override
    public java.util.Optional<StoredObject> head(String objectKey) {
      StoredContent stored = values.get(objectKey);
      return stored == null
          ? java.util.Optional.empty()
          : java.util.Optional.of(
              new StoredObject(
                  "version:qa-v1", stored.sha256(), stored.content().length, stored.mediaType()));
    }
  }

  private record Reply(
      int status, Map<String, Object> envelope, java.net.http.HttpHeaders headers) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return "Reply[status=" + status + "]";
    }
  }
}
