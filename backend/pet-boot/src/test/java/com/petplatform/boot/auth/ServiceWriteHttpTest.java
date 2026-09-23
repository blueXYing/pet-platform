package com.petplatform.boot.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.biz.application.AdminAuthService;
import com.petplatform.admin.biz.infrastructure.provider.AdminSecretCodec;
import com.petplatform.boot.PetPlatformApplication;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.merchant.biz.application.SubjectCredentialPort;
import com.petplatform.merchant.biz.infrastructure.provider.AesGcmProtectedValueProvider;
import com.petplatform.merchant.biz.infrastructure.provider.MainlandSubjectCredentialProvider;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
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
 * ADM-001 service write slice acceptance (W2-SVCW-001..009): a REAL approved-and-signed
 * merchant/store pair drives the whole state machine through the write-side HTTP surface — for
 * the first time service_item rows are produced by the writer itself, not SQL seeding. Covers the
 * state machine incl. illegal transitions, idempotent replays, the permission negatives, the
 * read-side compatibility regression (REVIEWING/REJECTED stay 404 for consumers but are known
 * statuses), consumer cover display authorization, and the same-transaction
 * ServiceReviewedEvent.v1 outbox append.
 */
class ServiceWriteHttpTest {
  private static final String ORIGIN = "https://svcw.example.invalid";
  private static final String PASSWORD = "Example_ONLY_92!";
  private static final String COVER_ASSET = "590000000000000201";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<Long, PrivateAssetQueryPort.PrivateAssetRef> privateAssets = new HashMap<>();
  private final Map<String, ServiceCoverAssetPort.CoverAssetFact> coverAssets = new HashMap<>();
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
              "33-Service-Write-Schema-v0.1.sql")) {
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
    props.put("pet.auth.admin.audit-path", db.directory.resolve("svcw-audit.bin"));
    props.put("pet.merchant.application.open-cities[0].code", "chengdu");
    props.put("pet.merchant.application.open-cities[0].name", "成都");
    props.put("pet.merchant.application.enabled", true);
    props.put("pet.outbox.enabled", true);
    props.put("pet.service.query.enabled", true);
    props.put("pet.service.command.enabled", true);
    try {
      context =
          new SpringApplicationBuilder(PetPlatformApplication.class)
              .initializers(
                  ctx -> {
                    var beans = (GenericApplicationContext) ctx;
                    beans.registerBean(
                        "svcwDataSource", javax.sql.DataSource.class, () -> db.source);
                    beans.registerBean(
                        "svcwIds",
                        com.petplatform.common.SnowflakeIdGenerator.class,
                        () -> db.ids);
                    beans.registerBean(
                        "svcwWechat",
                        WechatSessionProvider.class,
                        CAuthHttpTest.FixedWechatProvider::new);
                    beans.registerBean(
                        "svcwProtection",
                        ApplicationValidationPorts.ProtectedValuePort.class,
                        () -> protector);
                    beans.registerBean(
                        "svcwPrivateAssets",
                        PrivateAssetQueryPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(privateAssets::get)
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "svcwMap",
                        ApplicationValidationPorts.MapValidationPort.class,
                        () -> (city, address, lng, lat) -> "chengdu".equals(city));
                    beans.registerBean(
                        "svcwSubjects",
                        SubjectCredentialPort.class,
                        () ->
                            new MainlandSubjectCredentialProvider(protector, "qa-only-v1", key(63)));
                    // SERVICE_COVER ownership stub: the real upload pipeline is the MER-domain
                    // writer's deliverable (31号, role B); the validation seam is identical.
                    beans.registerBean(
                        "svcwCoverAssets",
                        ServiceCoverAssetPort.class,
                        () ->
                            (owner, ids) ->
                                ids.stream()
                                    .map(id -> {
                                      ServiceCoverAssetPort.CoverAssetFact fact =
                                          coverAssets.get(id);
                                      return fact != null && fact.ownerUserId().equals(owner)
                                          ? fact : null;
                                    })
                                    .filter(Objects::nonNull)
                                    .toList());
                    beans.registerBean(
                        "svcwCoverUrls",
                        ServiceCoverUrlPort.class,
                        () ->
                            assetId ->
                                new ServiceCoverUrlPort.CoverUrl(
                                    assetId,
                                    "https://cover.example.invalid/signed/" + assetId,
                                    java.time.Instant.now().getEpochSecond() + 3600));
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
              "Isolated service write acceptance");
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
  void serviceWriteLifecycleFollowsApprovedStateMachine() throws Exception {
    // Real flow first: application -> APPROVE creates the merchant/store pair, then the owner
    // signs the agreement so the admission fact becomes ALLOWED (operable).
    Map<String, Object> owner = consumerLogin("svcw-owner", "13800007731");
    String token = str(owner, "accessToken");
    String ownerId = str(owner, "userId");
    for (long id = 301; id <= 304; id++)
      privateAssets.put(
          id,
          new PrivateAssetQueryPort.PrivateAssetRef(
              id, Long.parseLong(ownerId), sha("svcw-asset-" + id), "image/jpeg", 512, "READY"));
    coverAssets.put(
        COVER_ASSET,
        new ServiceCoverAssetPort.CoverAssetFact(
            COVER_ASSET, ownerId, "READY", "image/jpeg", 2048));
    long merchantId =
        Long.parseLong(approveApplication(token, ownerId));
    long storeId =
        db.jdbc.queryForObject(
            "SELECT id FROM merchant_store WHERE merchant_id=?", Long.class, merchantId);
    long categoryId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,created_at,updated_at)"
            + " VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        categoryId, "洗护美容");
    long disabledCategoryId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO service_category(id,category_name,status,created_at,updated_at)"
            + " VALUES(?,'已停用类目','DISABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        disabledCategoryId);

    String merchant = String.valueOf(merchantId), store = String.valueOf(storeId);
    String category = String.valueOf(categoryId);

    // ---- W2-SVCW-001/006: create draft (lenient), then full validation at submit.
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("serviceName", "宠物美容-基础洗护");
    fields.put("categoryId", category);
    fields.put("fulfillmentType", "IN_STORE");
    fields.put("price", "128.00");
    fields.put("listPrice", "158.00");
    fields.put("durationMinutes", 45);
    fields.put("coverAssetId", COVER_ASSET);
    fields.put("applicablePetTypes", List.of("DOG", "CAT"));
    fields.put("description", "含洗护、吹干、基础梳理");

    String createRequestId = UUID.randomUUID().toString();
    Map<String, Object> createBody = new LinkedHashMap<>(fields);
    createBody.put("merchantId", merchant);
    createBody.put("storeId", store);
    Reply created =
        send("POST", "/api/v1/merchant/services", createBody, bearer(token, createRequestId));
    assertEquals(201, created.status(), created.redacted());
    String serviceId = str(created.data(), "serviceId");
    assertEquals("DRAFT", created.data().get("status"));
    assertEquals("0", str(created.data(), "version"));

    // Draft replay returns the first receipt with 200 (23号 §6), no second row.
    Reply createdReplay =
        send("POST", "/api/v1/merchant/services", createBody, bearer(token, createRequestId));
    assertEquals(200, createdReplay.status(), createdReplay.redacted());
    assertEquals(serviceId, str(createdReplay.data(), "serviceId"));
    assertEquals(
        1L,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM service_item WHERE id=?", Long.class, Long.parseLong(serviceId)));
    // Same requestId with different parameters: 409 IDEMPOTENCY_KEY_CONFLICT.
    Map<String, Object> conflictBody = new LinkedHashMap<>(createBody);
    conflictBody.put("price", "99.00");
    assertEquals(
        409,
        send("POST", "/api/v1/merchant/services", conflictBody, bearer(token, createRequestId))
            .status());

    // Update draft: version CAS, full replace.
    Map<String, Object> updated = new LinkedHashMap<>(fields);
    updated.put("staffRequirement", "持证宠物美容师");
    Reply saved =
        send(
            "PUT",
            "/api/v1/merchant/services/" + serviceId,
            withVersion(updated, "0"),
            bearer(token));
    assertEquals(200, saved.status(), saved.redacted());
    assertEquals("1", str(saved.data(), "version"));
    assertEquals(
        409,
        send(
                "PUT",
                "/api/v1/merchant/services/" + serviceId,
                withVersion(updated, "0"),
                bearer(token))
            .status());

    // Submission gate: cover is mandatory (2026-09-22 ruling #3) and the category must be ENABLED.
    Map<String, Object> noCover = new LinkedHashMap<>(updated);
    noCover.put("coverAssetId", null);
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/merchant/services/" + serviceId,
                withVersion(noCover, "1"),
                bearer(token))
            .status());
    Reply submitNoCover =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/online",
            Map.of("expectedVersion", "2"),
            bearer(token));
    assertEquals(400, submitNoCover.status(), submitNoCover.redacted());
    Map<String, Object> disabledCategory = new LinkedHashMap<>(updated);
    disabledCategory.put("categoryId", String.valueOf(disabledCategoryId));
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/merchant/services/" + serviceId,
                withVersion(disabledCategory, "2"),
                bearer(token))
            .status());
    assertEquals(
        400,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/online",
                Map.of("expectedVersion", "3"),
                bearer(token))
            .status(),
        "DISABLED category must fail the submission gate");
    // W2-SVCW-006: a cover bound to a different owner fails the submission gate with the
    // field-level 400 (finalized 10号 §4.10.1 applicability), never a state 409.
    String foreignCover = "590000000000000999";
    coverAssets.put(
        foreignCover,
        new ServiceCoverAssetPort.CoverAssetFact(
            foreignCover, ownerId + "0", "READY", "image/jpeg", 2048));
    Map<String, Object> foreignCoverDraft = new LinkedHashMap<>(fields);
    foreignCoverDraft.put("serviceName", "封面归属反例服务");
    foreignCoverDraft.put("coverAssetId", foreignCover);
    foreignCoverDraft.put("merchantId", merchant);
    foreignCoverDraft.put("storeId", store);
    Reply foreignCreated =
        send("POST", "/api/v1/merchant/services", foreignCoverDraft, bearer(token));
    assertEquals(201, foreignCreated.status(), foreignCreated.redacted());
    Reply foreignSubmit =
        send(
            "POST",
            "/api/v1/merchant/services/" + str(foreignCreated.data(), "serviceId") + "/online",
            Map.of("expectedVersion", "0"),
            bearer(token));
    assertEquals(400, foreignSubmit.status(), foreignSubmit.redacted());
    assertEquals("COMMON_INVALID_ARGUMENT", foreignSubmit.envelope().get("code"));
    // Restore the valid copy and submit for review.
    send("PUT", "/api/v1/merchant/services/" + serviceId, withVersion(updated, "3"),
        bearer(token));
    Reply submitted =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/online",
            Map.of("expectedVersion", "4"),
            bearer(token));
    assertEquals(200, submitted.status(), submitted.redacted());
    assertEquals("REVIEWING", submitted.data().get("status"));
    assertEquals("5", str(submitted.data(), "version"));

    // ---- W2-SVCW-004: REVIEWING is invisible to consumers (404) but a known status (no 503).
    assertEquals(
        404, send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token)).status());
    // Illegal transitions while REVIEWING: edit 409, offline 409, submit 409.
    assertEquals(
        409,
        send("PUT", "/api/v1/merchant/services/" + serviceId, withVersion(updated, "5"),
                bearer(token))
            .status());
    assertEquals(
        "SERVICE_STATE_NOT_ALLOWED",
        send("PUT", "/api/v1/merchant/services/" + serviceId, withVersion(updated, "5"),
                bearer(token))
            .envelope()
            .get("code"));
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/offline",
                Map.of("expectedVersion", "5"),
                bearer(token))
            .status());
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/online",
                Map.of("expectedVersion", "5"),
                bearer(token))
            .status());

    // ---- Admin review surface: list/detail + REJECT requires opinion (W2-SVCW-005).
    String admin = adminLogin();
    // W2-SVCW-003: an operator without the action codes gets 403 (codes are deployed for the
    // bootstrap super admin; full RBAC registration stays with the AUTH owner).
    long noRoleId = db.ids.nextId();
    db.jdbc.update(
        "INSERT INTO admin_account(id,account_display,account_lookup,display_name,password_hash,"
            + "credential_version,status,session_generation,version,created_at,updated_at)"
            + " SELECT ?,'qa-norole','STAFF:qa-norole','QA NoRole',password_hash,0,'ENABLED',0,0,"
            + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM admin_account WHERE account_display="
            + "'qa-reviewer'",
        noRoleId);
    db.jdbc.update(
        "INSERT INTO admin_account_scope(account_id,mode) VALUES(?,'ALL')", noRoleId);
    Reply noRoleList =
        send("GET", "/api/v1/admin/services", null, bearer(adminLogin("qa-norole")));
    assertEquals(403, noRoleList.status(), noRoleList.redacted());
    Reply reviewList =
        send("GET", "/api/v1/admin/services?status=REVIEWING", null, bearer(admin));
    assertEquals(200, reviewList.status(), reviewList.redacted());
    assertTrue(
        ((List<?>) reviewList.data().get("items")).stream()
            .anyMatch(row -> serviceId.equals(map(row).get("serviceId"))));
    Reply detail = send("GET", "/api/v1/admin/services/" + serviceId, null, bearer(admin));
    assertEquals(200, detail.status(), detail.redacted());
    assertEquals(0, ((List<?>) detail.data().get("decisions")).size());
    assertEquals(1, ((Number) detail.data().get("submissionNo")).intValue());
    assertNotNull(detail.data().get("slaRemainingMinutes"));

    Reply rejectNoOpinion =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of("decisionType", "REJECT", "expectedVersion", "5"),
            bearer(admin));
    assertEquals(400, rejectNoOpinion.status(), rejectNoOpinion.redacted());
    assertEquals(
        "SERVICE_REVIEW_REASON_REQUIRED", rejectNoOpinion.envelope().get("code"));
    Reply rejectShort =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of("decisionType", "REJECT", "opinion", "太短", "expectedVersion", "5"),
            bearer(admin));
    assertEquals("SERVICE_REVIEW_REASON_REQUIRED", rejectShort.envelope().get("code"));

    // ---- W2-SVCW-009: REJECT decision -> REJECTED + same-transaction outbox event.
    String rejectRequestId = UUID.randomUUID().toString();
    String opinion = "封面图片不清晰，请重新上传后再次提交审核";
    Reply rejected =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of(
                "decisionType", "REJECT",
                "opinion", opinion,
                "expectedVersion", "5"),
            bearer(admin, rejectRequestId));
    assertEquals(200, rejected.status(), rejected.redacted());
    assertEquals("REJECTED", rejected.data().get("status"));
    String decisionId = str(rejected.data(), "decisionId");
    Map<String, Object> outbox = outboxRow(serviceId);
    assertEquals("ServiceReviewedEvent.v1", outbox.get("event_type"));
    assertEquals("SERVICE", outbox.get("aggregate_type"));
    // Parse instead of substring-matching: assert the agreed 9-field shape and types.
    Map<String, Object> payload = json.readValue(String.valueOf(outbox.get("payload")), Map.class);
    assertEquals(9, payload.size(), "payload fields: " + payload);
    assertEquals(serviceId, payload.get("serviceId"));
    assertEquals("宠物美容-基础洗护", payload.get("serviceName"));
    assertEquals(merchant, payload.get("merchantId"));
    assertEquals(store, payload.get("storeId"));
    assertEquals(1, ((Number) payload.get("submissionNo")).intValue(),
        "submissionNo is a JSON integer");
    assertEquals("REJECT", payload.get("decisionType"));
    assertEquals(opinion, payload.get("opinion"));
    assertEquals(ownerId, payload.get("ownerUserId"));
    assertNotNull(payload.get("decidedAt"));
    assertEquals(
        1L,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM service_review_decision WHERE service_id=?",
            Long.class,
            Long.parseLong(serviceId)));
    // Idempotent decision replay: same requestId -> same receipt, no second event/decision.
    Reply rejectReplay =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of(
                "decisionType", "REJECT",
                "opinion", opinion,
                "expectedVersion", "5"),
            bearer(admin, rejectRequestId));
    assertEquals(200, rejectReplay.status(), rejectReplay.redacted());
    assertEquals("REJECTED", rejectReplay.data().get("status"));
    assertEquals(1L, outboxCount(serviceId));
    assertEquals(
        1L,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM service_review_decision WHERE service_id=?",
            Long.class,
            Long.parseLong(serviceId)));

    // ---- W2-SVCW-004: REJECTED stays 404 for consumers.
    assertEquals(
        404, send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token)).status());

    // ---- Workbench: rejected service shows the latest rejection (M-002 view).
    Reply workbench =
        send(
            "GET",
            "/api/v1/merchant/services?merchantId=" + merchant + "&storeId=" + store,
            null,
            bearer(token));
    assertEquals(200, workbench.status(), workbench.redacted());
    Map<String, Object> row =
        ((List<?>) workbench.data().get("items")).stream()
            .map(ServiceWriteHttpTest::map)
            .filter(r -> serviceId.equals(r.get("serviceId")))
            .findFirst()
            .orElseThrow();
    assertEquals("REJECTED", row.get("status"));
    assertEquals(opinion, map(row.get("latestRejection")).get("opinion"));
    assertEquals(
        1,
        ((Number) map(row.get("latestRejection")).get("submissionNo")).intValue());
    Reply workbenchDetail =
        send(
            "GET",
            "/api/v1/merchant/services/" + serviceId
                + "?merchantId=" + merchant + "&storeId=" + store,
            null,
            bearer(token));
    assertEquals(200, workbenchDetail.status(), workbenchDetail.redacted());
    // Categories: the ENABLED dictionary only.
    Reply categories =
        send("GET", "/api/v1/merchant/service-categories", null, bearer(token));
    assertEquals(200, categories.status(), categories.redacted());
    assertEquals(
        1,
        ((List<?>) categories.data().get("items")).stream()
            .filter(c -> "洗护美容".equals(map(c).get("categoryName")))
            .count());

    // ---- Resubmission after fix: REJECTED -> REVIEWING (submissionNo=2) -> APPROVE -> ACTIVE.
    send(
        "PUT",
        "/api/v1/merchant/services/" + serviceId,
        withVersion(updated, "6"),
        bearer(token));
    Reply resubmitted =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/online",
            Map.of("expectedVersion", "7"),
            bearer(token));
    assertEquals(200, resubmitted.status(), resubmitted.redacted());
    assertEquals("REVIEWING", resubmitted.data().get("status"));
    Reply approved =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/decision",
            Map.of("decisionType", "APPROVE", "expectedVersion", "8"),
            bearer(admin));
    assertEquals(200, approved.status(), approved.redacted());
    assertEquals("ACTIVE", approved.data().get("status"));
    assertEquals(2L, outboxCount(serviceId));

    // ---- W2-SVCW-008: consumer cover display only when visible (ACTIVE + conjunction).
    Reply visible = send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token));
    assertEquals(200, visible.status(), visible.redacted());
    Map<String, Object> cover = map(visible.data().get("cover"));
    assertEquals(COVER_ASSET, cover.get("coverAssetId"));
    assertTrue(String.valueOf(cover.get("coverUrl")).startsWith("https://cover.example.invalid/"));
    assertNotNull(cover.get("coverUrlExpiresAt"));
    Reply storeList =
        send("GET", "/api/v1/c/stores/" + store + "/services?page=1&pageSize=20", null,
            bearer(token));
    assertEquals(200, storeList.status(), storeList.redacted());
    Map<String, Object> listed =
        ((List<?>) storeList.data().get("items")).stream()
            .map(ServiceWriteHttpTest::map)
            .filter(r -> serviceId.equals(r.get("serviceId")))
            .findFirst()
            .orElseThrow();
    assertNotNull(map(listed.get("cover")).get("coverUrl"), "list rows carry the cover too");
    // Hidden again (merchant OFFLINE): 404 and no cover signature is ever issued for it.
    db.jdbc.update("UPDATE merchant SET status='OFFLINE' WHERE id=?", merchantId);
    assertEquals(
        404, send("GET", "/api/v1/c/services/" + serviceId, null, bearer(token)).status());

    // ---- W2-SVCW-003/007: admission gate on the write side.
    // Non-operable facts (merchant OFFLINE): create/submit/offline answer 409, not 403.
    Reply deniedCreate =
        send(
            "POST",
            "/api/v1/merchant/services",
            createBody,
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(409, deniedCreate.status(), deniedCreate.redacted());
    assertEquals("SERVICE_STATE_NOT_ALLOWED", deniedCreate.envelope().get("code"));
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);
    // Facts unreadable: unknown stored merchant status -> 503, fail closed, no half-write.
    db.jdbc.update("UPDATE merchant SET status='CORRUPTED' WHERE id=?", merchantId);
    Reply corrupted =
        send(
            "POST",
            "/api/v1/merchant/services",
            createBody,
            bearer(token, UUID.randomUUID().toString()));
    assertEquals(503, corrupted.status(), corrupted.redacted());
    db.jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantId);

    // Unrelated consumer: no owned-store fact -> 404 anti-enumeration on every surface.
    Map<String, Object> other = consumerLogin("svcw-other", "13800007732");
    String otherToken = str(other, "accessToken");
    Map<String, Object> otherBody = new LinkedHashMap<>(createBody);
    assertEquals(
        404,
        send(
                "POST",
                "/api/v1/merchant/services",
                otherBody,
                bearer(otherToken, UUID.randomUUID().toString()))
            .status());
    assertEquals(
        404,
        send(
                "GET",
                "/api/v1/merchant/services/" + serviceId
                    + "?merchantId=" + merchant + "&storeId=" + store,
                null,
                bearer(otherToken))
            .status());
    // W2-SVCW-003: the write surface answers the same 404 before any state/version fact is
    // checked — an unrelated caller never learns whether the row exists or its status.
    assertEquals(
        404,
        send(
                "PUT",
                "/api/v1/merchant/services/" + serviceId,
                withVersion(updated, "9"),
                bearer(otherToken))
            .status(),
        "cross-merchant update must 404 (anti-enumeration), not a state/version 409");
    assertEquals(
        404,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/online",
                Map.of("expectedVersion", "9"),
                bearer(otherToken))
            .status());
    assertEquals(
        404,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/offline",
                Map.of("expectedVersion", "9"),
                bearer(otherToken))
            .status());

    // ---- ACTIVE protection + offline / force-offline transitions + governance audit.
    assertEquals(
        409,
        send("PUT", "/api/v1/merchant/services/" + serviceId, withVersion(updated, "9"),
                bearer(token))
            .status());
    assertEquals(
        "SERVICE_STATE_NOT_ALLOWED",
        send(
                "POST",
                "/api/v1/admin/services/" + serviceId + "/decision",
                Map.of("decisionType", "APPROVE", "expectedVersion", "9"),
                bearer(admin))
            .envelope()
            .get("code"),
        "decision on ACTIVE (not REVIEWING) must 409");
    Reply staleForceOffline =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/force-offline",
            Map.of("reason", "违规内容，强制下架处理", "expectedVersion", "0"),
            bearer(admin));
    assertEquals(
        409, staleForceOffline.status(),
        "force-offline with a stale version must 409: " + staleForceOffline.redacted());
    Reply offlined =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/offline",
            Map.of("expectedVersion", "9"),
            bearer(token));
    assertEquals(200, offlined.status(), offlined.redacted());
    assertEquals("OFFLINE", offlined.data().get("status"));
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/merchant/services/" + serviceId + "/offline",
                Map.of("expectedVersion", "10"),
                bearer(token))
            .status());
    // Resubmission from OFFLINE re-enters review (anti price-circumvention), then ACTIVE again.
    Reply resubmitFromOffline =
        send(
            "POST",
            "/api/v1/merchant/services/" + serviceId + "/online",
            Map.of("expectedVersion", "10"),
            bearer(token));
    assertEquals(200, resubmitFromOffline.status(), resubmitFromOffline.redacted());
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/admin/services/" + serviceId + "/force-offline",
                Map.of("reason", "投诉成立，强制下架处理", "expectedVersion", "11"),
                bearer(admin))
            .status(),
        "force-offline on REVIEWING must 409");
    send(
        "POST",
        "/api/v1/admin/services/" + serviceId + "/decision",
        Map.of("decisionType", "APPROVE", "expectedVersion", "11"),
        bearer(admin));
    long beforeForce = outboxCount(serviceId);
    Reply forced =
        send(
            "POST",
            "/api/v1/admin/services/" + serviceId + "/force-offline",
            Map.of("reason", "投诉成立，强制下架处理", "expectedVersion", "12"),
            bearer(admin));
    assertEquals(200, forced.status(), forced.redacted());
    assertEquals("OFFLINE", forced.data().get("status"));
    assertNotNull(forced.data().get("actionId"));
    assertEquals(beforeForce, outboxCount(serviceId), "force-offline must not emit an event");
    assertEquals(
        1L,
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM service_governance_action WHERE service_id=?",
            Long.class,
            Long.parseLong(serviceId)));

    // ---- Session negatives.
    assertEquals(
        401,
        send(
                "POST",
                "/api/v1/merchant/services",
                createBody,
                bearer(UUID.randomUUID().toString()))
            .status());
    assertEquals(
        401, send("GET", "/api/v1/admin/services", null, bearer(token)).status());
    assertEquals(
        400,
        sendNoRequestId(
            "POST", "/api/v1/merchant/services", createBody, bearer(token))
            .status());
  }

  /** Runs the real application -> review -> APPROVE chain and signs the agreement. */
  private String approveApplication(String token, String ownerId) throws Exception {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("merchantName", "星河服务生活馆");
    draft.put("contactName", "张三");
    draft.put("contactPhone", "13800007731");
    draft.put("email", "svcw-owner@example.test");
    draft.put("merchantTypeCode", "PET_LIFE_STORE");
    draft.put("cityCode", "chengdu");
    draft.put("address", "测试服务地址");
    draft.put("longitude", "121.4");
    draft.put("latitude", "31.2");
    draft.put("storePhotoAssetIds", List.of("301"));
    draft.put("businessLicenseAssetId", "302");
    draft.put("idCardFrontAssetId", "303");
    draft.put("idCardBackAssetId", "304");
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
    Reply approvedDecision =
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
    assertEquals(200, approvedDecision.status(), approvedDecision.redacted());
    assertEquals("APPROVED", approvedDecision.data().get("status"));
    long merchantId =
        Long.parseLong(String.valueOf(approvedDecision.data().get("reservedMerchantId")));
    long agreementId = db.ids.nextId();
    String content = "测试环境商家服务协议", hash = sha(content);
    db.jdbc.update(
        "INSERT INTO"
            + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",
        agreementId,
        "svcw-http-v1",
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
                "agreementVersion", "svcw-http-v1",
                "contentSha256", hash,
                "accepted", true),
            bearer(token));
    assertEquals(201, signed.status(), signed.redacted());
    return String.valueOf(merchantId);
  }

  private long outboxCount(String serviceId) {
    return db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM integration_event_outbox WHERE aggregate_id=?",
        Long.class,
        Long.parseLong(serviceId));
  }

  private Map<String, Object> outboxRow(String serviceId) {
    return db.jdbc.queryForMap(
        "SELECT event_type,aggregate_type,aggregate_id,payload FROM integration_event_outbox"
            + " WHERE aggregate_id=? ORDER BY id",
        Long.parseLong(serviceId));
  }

  private static Map<String, Object> withVersion(Map<String, Object> fields, String version) {
    Map<String, Object> body = new LinkedHashMap<>(fields);
    body.put("expectedVersion", version);
    return body;
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
    return adminLogin("qa-reviewer");
  }

  private String adminLogin(String account) throws Exception {
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
                "Cookie",
                    attempt.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
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
                "account", account,
                "password", PASSWORD,
                "captchaProof", str(proof.data(), "captchaProof")),
            headers);
    assertEquals(200, grant.status(), grant.redacted());
    return str(grant.data(), "accessToken");
  }

  private Reply send(String method, String path, Object body, Map<String, String> headers)
      throws Exception {
    return send(method, path, body, headers, true);
  }

  private Reply sendNoRequestId(
      String method, String path, Object body, Map<String, String> headers) throws Exception {
    return send(method, path, body, headers, false);
  }

  private Reply send(
      String method, String path, Object body, Map<String, String> headers, boolean autoRequestId)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    if (autoRequestId && !headers.containsKey("X-Request-Id") && !method.equals("GET"))
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
    if (response.statusCode() >= 400) assertNull(envelope.get("data"));
    return new Reply(response.statusCode(), (Map<String, Object>) envelope, response.headers());
  }

  private static Map<String, String> bearer(String token) {
    return Map.of("Authorization", "Bearer " + token);
  }

  private static Map<String, String> bearer(String token, String requestId) {
    return Map.of("Authorization", "Bearer " + token, "X-Request-Id", requestId);
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
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private record Reply(int status, Map<String, Object> envelope, java.net.http.HttpHeaders headers) {
    Map<String, Object> data() {
      return (Map<String, Object>) envelope.get("data");
    }

    String redacted() {
      return status + " " + envelope.get("code") + " " + envelope.get("message");
    }
  }
}
