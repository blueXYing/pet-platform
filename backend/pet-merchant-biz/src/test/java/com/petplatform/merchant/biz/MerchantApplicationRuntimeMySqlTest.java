package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.merchant.api.command.MerchantAgreementConsentCommand;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantAgreementQuery;
import com.petplatform.merchant.biz.apiimpl.MerchantAgreementApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import com.petplatform.merchant.biz.application.*;
import com.petplatform.merchant.biz.application.ApplicationFinalAuthorizationPort.*;
import com.petplatform.merchant.biz.infrastructure.persistence.MerchantApplicationStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** MER-001 lifecycle tests against SQL06+28+29 on isolated real MySQL 8. */
class MerchantApplicationRuntimeMySqlTest {
  static final long OWNER = 7_300_000_001L, OPERATOR = 7_300_000_002L;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC);
  private final AtomicLong ids = new AtomicLong(9_400_000_000_000_000L);

  @Test
  void completeApprovalIsAtomicReplayableAndLeavesAgreementUnsigned() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      Fixture fixture = new Fixture(db, true);
      MerchantApplicationApiImpl api = fixture.api();
      MerchantApplicationResult created =
          api.createDraft(
              new CreateMerchantApplicationCommand(draft(), user(UUID.randomUUID().toString())));
      assertEquals("DRAFT", created.status());
      assertNotNull(created.currentRevision());
      assertNull(created.applicationNo());
      MerchantApplicationResult submitted =
          api.submit(
              new SubmitMerchantApplicationCommand(
                  created.applicationId(),
                  created.version(),
                  created.currentRevision().revisionId(),
                  user(UUID.randomUUID().toString())));
      assertEquals("REVIEWING", submitted.status());
      assertTrue(submitted.applicationNo().matches("SQ20260917[A-Z0-9]{8}"));
      ReviewTaskResult task =
          api.getForReview(
                  new MerchantApplicationReviewQuery(
                      submitted.applicationId(), auth(), queryOperator()))
              .task();
      ReviewTaskResult claimed =
          api.claim(
              new ClaimMerchantApplicationCommand(
                  submitted.applicationId(),
                  task.version(),
                  auth(),
                  operator(UUID.randomUUID().toString())));
      MerchantApplicationResult verified =
          api.recordManualVerification(
              new VerifyMerchantSubjectCommand(
                  submitted.applicationId(),
                  submitted.currentRevision().revisionId(),
                  submitted.version(),
                  claimed.version(),
                  bindEvidence(db, submitted.applicationId(), evidence()),
                  "已逐项核对原件",
                  true,
                  auth(),
                  operator(UUID.randomUUID().toString())));
      String request = UUID.randomUUID().toString();
      DecideMerchantApplicationCommand decision =
          new DecideMerchantApplicationCommand(
              verified.applicationId(),
              "APPROVE",
              verified.currentRevision().revisionId(),
              verified.version(),
              claimed.version(),
              "核验通过",
              null,
              true,
              auth(),
              operator(request));
      MerchantApplicationResult approved = api.decide(decision);
      MerchantApplicationResult replay = api.decide(decision);
      assertEquals("APPROVED", approved.status());
      assertEquals(approved, replay);
      assertEquals(
          "ACTIVE",
          db.jdbc()
              .queryForObject(
                  "SELECT status FROM merchant WHERE id=?",
                  String.class,
                  Long.parseLong(approved.reservedMerchantId())));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_store WHERE merchant_id=? AND phone IS NULL",
                  Integer.class,
                  Long.parseLong(approved.reservedMerchantId())));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_agreement_acceptance WHERE merchant_id=?",
                  Integer.class,
                  Long.parseLong(approved.reservedMerchantId())));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM integration_event_outbox WHERE aggregate_id=? AND"
                      + " event_type='MerchantApplicationReviewedEvent.v1'",
                  Integer.class,
                  Long.parseLong(approved.applicationId())));
      long agreementVersionId = ids.incrementAndGet();
      String agreementContent = "商家电子协议测试版本";
      String agreementHash = sha(agreementContent);
      db.jdbc()
          .update(
              "INSERT INTO"
                  + " merchant_agreement_version(id,agreement_version,content,content_sha256,published_at,published_by_operator_id)"
                  + " VALUES(?,?,?,?,NOW(3),?)",
              agreementVersionId,
              "merchant-runtime-v1",
              agreementContent,
              agreementHash,
              OPERATOR);
      db.jdbc()
          .update(
              "INSERT INTO"
                  + " merchant_agreement_current(agreement_key,agreement_version_id,version,updated_at)"
                  + " VALUES('MERCHANT',?,0,NOW(3))",
              agreementVersionId);
      var persistentFacts =
          new PersistentApplicationReviewFactsReader(
              new MerchantApplicationStore(db.dataSource(), ids::incrementAndGet));
      var agreementApi =
          new MerchantAgreementApiImpl(
              db.dataSource(), ids::incrementAndGet, CLOCK, persistentFacts);
      assertEquals(
          "NOT_SIGNED",
          agreementApi
              .getAgreement(
                  new MerchantAgreementQuery(
                      approved.reservedMerchantId(),
                      new QueryContext("trace", OperatorType.USER, Long.toString(OWNER))))
              .signingStatus());
      assertEquals(
          "SIGNED",
          agreementApi
              .consent(
                  new MerchantAgreementConsentCommand(
                      approved.reservedMerchantId(),
                      "merchant-runtime-v1",
                      agreementHash,
                      true,
                      user(UUID.randomUUID().toString())))
              .signingStatus());
      String payload =
          db.jdbc()
              .queryForObject(
                  "SELECT CAST(payload AS CHAR) FROM integration_event_outbox WHERE aggregate_id=?",
                  String.class,
                  Long.parseLong(approved.applicationId()));
      assertTrue(
          payload.contains("\"decisionType\": \"APPROVE\"")
              || payload.contains("\"decisionType\":\"APPROVE\""));
      assertFalse(payload.contains("internalNote"));
      var merchantQueries =
          new com.petplatform.merchant.biz.apiimpl.MerchantQueryApiImpl(
              db.dataSource(),
              new MerchantAgreementEligibilityFactsAdapter(db.dataSource(), persistentFacts));
      String storeId =
          db.jdbc()
              .queryForObject(
                  "SELECT CAST(id AS CHAR) FROM merchant_store WHERE merchant_id=?",
                  String.class,
                  Long.parseLong(approved.reservedMerchantId()));
      var eligibilityQuery =
          new com.petplatform.merchant.api.query.MerchantOrderEligibilityQuery(
              approved.reservedMerchantId(),
              storeId,
              new QueryContext("trace", OperatorType.USER, Long.toString(OWNER)));
      assertTrue(merchantQueries.checkOrderEligibility(eligibilityQuery).acceptsNewOrders());
      db.jdbc()
          .update(
              "UPDATE merchant SET status='OFFLINE' WHERE id=?",
              Long.parseLong(approved.reservedMerchantId()));
      db.jdbc()
          .update("UPDATE merchant_store SET status='OFFLINE' WHERE id=?", Long.parseLong(storeId));
      assertEquals(
          "APPROVED",
          api.getEligibility(new MerchantApplicationEligibilityQuery(approved.reservedMerchantId()))
              .status());
      assertFalse(merchantQueries.checkOrderEligibility(eligibilityQuery).acceptsNewOrders());
    }
  }

  @Test
  void revokedAuthorizationAndIdempotencyConflictRollBackWithoutDecisionOrEvent() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      Fixture fixture = new Fixture(db, true);
      MerchantApplicationApiImpl api = fixture.api();
      MerchantApplicationResult created =
          api.createDraft(
              new CreateMerchantApplicationCommand(draft(), user(UUID.randomUUID().toString())));
      String submitRequest = UUID.randomUUID().toString();
      SubmitMerchantApplicationCommand submit =
          new SubmitMerchantApplicationCommand(
              created.applicationId(),
              created.version(),
              created.currentRevision().revisionId(),
              user(submitRequest));
      MerchantApplicationResult submitted = api.submit(submit);
      assertEquals(submitted, api.submit(submit));
      ApiException conflict =
          assertThrows(
              ApiException.class,
              () ->
                  api.submit(
                      new SubmitMerchantApplicationCommand(
                          created.applicationId(),
                          created.version() + 1,
                          created.currentRevision().revisionId(),
                          user(submitRequest))));
      assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
      ReviewTaskResult task =
          api.getForReview(
                  new MerchantApplicationReviewQuery(
                      submitted.applicationId(), auth(), queryOperator()))
              .task();
      ReviewTaskResult claimed =
          api.claim(
              new ClaimMerchantApplicationCommand(
                  submitted.applicationId(),
                  task.version(),
                  auth(),
                  operator(UUID.randomUUID().toString())));
      fixture.allowed.set(false);
      ApiException denied =
          assertThrows(
              ApiException.class,
              () ->
                  api.recordManualVerification(
                      new VerifyMerchantSubjectCommand(
                          submitted.applicationId(),
                          submitted.currentRevision().revisionId(),
                          submitted.version(),
                          claimed.version(),
                          bindEvidence(db, submitted.applicationId(), evidence()),
                          "已逐项核对原件",
                          true,
                          auth(),
                          operator(UUID.randomUUID().toString()))));
      assertEquals(CommonApiCodes.UNAUTHORIZED, denied.code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_decision", Integer.class));
      assertEquals(
          0,
          db.jdbc().queryForObject("SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
    }
  }

  @Test
  void correctionDecisionKeepsHistoryAndResubmitsAnImmutableNewRevision() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      Fixture fixture = new Fixture(db, true);
      MerchantApplicationApiImpl api = fixture.api();
      MerchantApplicationResult created =
          api.createDraft(
              new CreateMerchantApplicationCommand(draft(), user(UUID.randomUUID().toString())));
      MerchantApplicationResult submitted =
          api.submit(
              new SubmitMerchantApplicationCommand(
                  created.applicationId(),
                  created.version(),
                  created.currentRevision().revisionId(),
                  user(UUID.randomUUID().toString())));
      ReviewTaskResult available =
          api.getForReview(
                  new MerchantApplicationReviewQuery(
                      submitted.applicationId(), auth(), queryOperator()))
              .task();
      ReviewTaskResult claimed =
          api.claim(
              new ClaimMerchantApplicationCommand(
                  submitted.applicationId(),
                  available.version(),
                  auth(),
                  operator(UUID.randomUUID().toString())));
      MerchantApplicationResult correction =
          api.decide(
              new DecideMerchantApplicationCommand(
                  submitted.applicationId(),
                  "REQUEST_CORRECTION",
                  submitted.currentRevision().revisionId(),
                  submitted.version(),
                  claimed.version(),
                  "请补充更清晰的经营地址说明",
                  "仅供审核记录",
                  true,
                  auth(),
                  operator(UUID.randomUUID().toString())));
      assertEquals("REJECTED", correction.status());
      DraftRevisionInput corrected =
          new DraftRevisionInput(
              draft().merchantName(),
              draft().contactName(),
              draft().contactPhone(),
              draft().email(),
              draft().merchantTypeCode(),
              draft().cityCode(),
              "上海市示例路1号二层",
              draft().longitude(),
              draft().latitude(),
              draft().introduction(),
              draft().storePhotoAssetIds(),
              draft().businessLicenseAssetId(),
              draft().idCardFrontAssetId(),
              draft().idCardBackAssetId(),
              draft().industryLicenseAssetId());
      MerchantApplicationResult saved =
          api.saveDraft(
              new SaveMerchantApplicationDraftCommand(
                  correction.applicationId(),
                  correction.version(),
                  corrected,
                  user(UUID.randomUUID().toString())));
      assertNotEquals(
          submitted.currentRevision().revisionId(), saved.currentRevision().revisionId());
      MerchantApplicationResult resubmitted =
          api.submit(
              new SubmitMerchantApplicationCommand(
                  saved.applicationId(),
                  saved.version(),
                  saved.currentRevision().revisionId(),
                  user(UUID.randomUUID().toString())));
      assertEquals("REVIEWING", resubmitted.status());
      assertEquals(submitted.applicationNo(), resubmitted.applicationNo());
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_revision WHERE application_id=?",
                  Integer.class,
                  Long.parseLong(created.applicationId())));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_decision WHERE"
                      + " application_id=?",
                  Integer.class,
                  Long.parseLong(created.applicationId())));
    }
  }

  final class Fixture {
    final MySqlMerchantApplicationSchemaTestDatabase db;
    final AtomicBoolean allowed;
    final java.util.concurrent.atomic.AtomicInteger authorizationRevision =
        new java.util.concurrent.atomic.AtomicInteger(1);
    java.util.function.Predicate<Resource> rowAccess = resource -> true;
    final long ownerUserId;
    final long assetBase;

    Fixture(MySqlMerchantApplicationSchemaTestDatabase db, boolean allowed) {
      this(db, allowed, OWNER, 101);
    }

    Fixture(
        MySqlMerchantApplicationSchemaTestDatabase db,
        boolean allowed,
        long ownerUserId,
        long assetBase) {
      this.db = db;
      this.allowed = new AtomicBoolean(allowed);
      this.ownerUserId = ownerUserId;
      this.assetBase = assetBase;
    }

    MerchantApplicationApiImpl api() {
      return api(CLOCK);
    }

    MerchantApplicationApiImpl api(Clock runtimeClock) {
      Map<Long, PrivateAssetQueryPort.PrivateAssetRef> assets = new HashMap<>();
      for (long id = assetBase; id < assetBase + 5; id++)
        assets.put(
            id,
            new PrivateAssetQueryPort.PrivateAssetRef(
                id, ownerUserId, sha("asset-" + id), "image/jpeg", 1024, "READY"));
      PrivateAssetQueryPort privateAssets =
          (owner, requested) ->
              requested.stream().map(assets::get).filter(Objects::nonNull).toList();
      ApplicationValidationPorts.ProtectedValuePort protector =
          new ApplicationValidationPorts.ProtectedValuePort() {
            @Override
            public ApplicationValidationPorts.ProtectedValue protect(String purpose, String value) {
              return new ApplicationValidationPorts.ProtectedValue(
                  bytes("cipher:" + purpose + ":" + value),
                  digest("token:" + purpose + ":" + value));
            }

            @Override
            public String reveal(String purpose, byte[] ciphertext) {
              String value = new String(ciphertext, StandardCharsets.UTF_8);
              String prefix = "cipher:" + purpose + ":";
              if (!value.startsWith(prefix)) throw new IllegalArgumentException("wrong purpose");
              return value.substring(prefix.length());
            }
          };
      SubjectCredentialPort credentials =
          new SubjectCredentialPort() {
            @Override
            public ProtectedCredential protect(
                String type, String subject, String identifier, String basis) {
              return new ProtectedCredential(
                  bytes(subject),
                  bytes("cipher:" + identifier),
                  digest("hmac:" + type + ":" + identifier),
                  1,
                  "key-v1",
                  basis == null ? null : bytes(basis));
            }

            @Override
            public String availablePolicyVersion() {
              return "key-v1";
            }

            @Override
            public boolean subjectMatches(byte[] protectedName, String expected) {
              return Arrays.equals(protectedName, bytes(expected));
            }
          };
      ApplicationFinalAuthorizationPort authorization =
          new ApplicationFinalAuthorizationPort() {
            @Override
            public Decision check(Check check) {
              boolean inScope = rowAccess.test(check.resource());
              return decision(
                  allowed.get() && inScope,
                  !allowed.get()
                      ? "SESSION_REVOKED"
                      : inScope ? "ALLOWED" : "RESOURCE_SCOPE_DENIED");
            }

            @Override
            public Decision checkCollection(CollectionCheck check) {
              return decision(allowed.get(), allowed.get() ? "ALLOWED" : "SESSION_REVOKED");
            }

            private Decision decision(boolean permitted, String reason) {
              return new Decision(
                  permitted,
                  OffsetDateTime.now(runtimeClock),
                  "authz-v" + authorizationRevision.get(),
                  reason);
            }
          };
      var events =
          new TransactionalOutboxPublisher(
              db.dataSource(), ids::incrementAndGet, new ObjectMapper());
      var deps =
          new MerchantApplicationDependencies(
              privateAssets,
              city -> "310100".equals(city),
              (city, address, lng, lat) -> true,
              protector,
              credentials,
              authorization,
              events);
      return new MerchantApplicationApiImpl(
          db.dataSource(), ids::incrementAndGet, runtimeClock, deps);
    }
  }

  static DraftRevisionInput draft() {
    return new DraftRevisionInput(
        "星河宠物医院",
        "张三",
        "13800138000",
        "owner@example.test",
        "PET_HOSPITAL",
        "310100",
        "上海市示例路1号",
        new BigDecimal("121.4737000"),
        new BigDecimal("31.2304000"),
        "正规宠物医疗服务",
        List.of("101"),
        "102",
        "103",
        "104",
        "105");
  }

  static List<ManualEvidenceItem> evidence() {
    return List.of(
        new ManualEvidenceItem(
            "BUSINESS_LICENSE",
            "星河宠物医院",
            "91310000TEST",
            "LONG_TERM",
            LocalDate.of(2020, 1, 1),
            null,
            "原件载明长期"),
        new ManualEvidenceItem(
            "ID_CARD_BACK",
            "张三",
            "310101TEST",
            "DATED",
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2030, 1, 1),
            null),
        new ManualEvidenceItem(
            "INDUSTRY_LICENSE",
            "星河宠物医院",
            "VET-TEST-001",
            "DATED",
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2030, 1, 1),
            null));
  }

  static List<ManualEvidenceItem> bindEvidence(
      MySqlMerchantApplicationSchemaTestDatabase db,
      String applicationId,
      List<ManualEvidenceItem> items) {
    return items.stream()
        .map(
            item -> {
              Map<String, Object> row =
                  db.jdbc()
                      .queryForMap(
                          "SELECT m.id,m.sha256 FROM merchant_application_material m "
                              + "JOIN merchant_application_revision_material rm ON rm.application_id=m.application_id AND rm.material_id=m.id "
                              + "JOIN merchant_application a ON a.id=m.application_id AND a.submitted_revision_id=rm.revision_id "
                              + "WHERE m.application_id=? AND m.material_type=?",
                          Long.parseLong(applicationId),
                          item.materialType());
              String credential =
                  switch (item.materialType()) {
                    case "BUSINESS_LICENSE" -> "CREDIT_CODE";
                    case "ID_CARD_BACK" -> "IDENTITY_NUMBER";
                    default -> "INDUSTRY_LICENSE";
                  };
              return ManualEvidenceItem.fromReference(
                  row.get("id").toString(),
                  row.get("sha256").toString(),
                  credential,
                  item.subjectName(),
                  item.identifier(),
                  item.validityKind(),
                  item.validFrom(),
                  item.validTo());
            })
        .toList();
  }

  static CommandContext user(String request) {
    return new CommandContext(request, "trace", OperatorType.USER, Long.toString(OWNER), "MINIAPP");
  }

  static CommandContext operator(String request) {
    return new CommandContext(
        request, "trace", OperatorType.PLATFORM_OPERATOR, Long.toString(OPERATOR), "ADMIN_WEB");
  }

  static QueryContext queryOperator() {
    return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, Long.toString(OPERATOR));
  }

  static AdminAuthorizationReference auth() {
    return new AdminAuthorizationReference("7300000003", 1);
  }

  static void seedPolicy(MySqlMerchantApplicationSchemaTestDatabase db) {
    db.jdbc()
        .update(
            "INSERT INTO"
                + " merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)"
                + " VALUES(1,'key-v1','HMAC-SHA-256',NOW(3))");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha(String value) {
    return HexFormat.of().formatHex(digest(value));
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
