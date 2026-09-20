package com.petplatform.merchant.biz;

import static com.petplatform.merchant.biz.MerchantApplicationRuntimeMySqlTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.biz.apiimpl.MerchantApplicationApiImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class MerchantApplicationSafetyMySqlTest {
  private final MerchantApplicationRuntimeMySqlTest fixtures =
      new MerchantApplicationRuntimeMySqlTest();

  @Test
  void correctionCanReplaceBothSubjectClaimsWithoutBreakingHistoricalForeignKeys()
      throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var firstApi = fixtures.new Fixture(db, true).api();
      var first = submit(firstApi, draft(), OWNER);
      var firstTask = claim(firstApi, first, OPERATOR);
      var firstProof = verify(db, firstApi, first, firstTask);
      var correction =
          firstApi.decide(
              new DecideMerchantApplicationCommand(
                  first.applicationId(),
                  "REQUEST_CORRECTION",
                  first.currentRevision().revisionId(),
                  firstProof.version(),
                  firstTask.version(),
                  "请更新正确主体对应的证件材料",
                  null,
                  true,
                  auth(),
                  context(OPERATOR, OperatorType.PLATFORM_OPERATOR)));
      var replacementApi = fixtures.new Fixture(db, true, OWNER, 201).api();
      var d = draft();
      var replacement =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              d.contactPhone(),
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              d.longitude(),
              d.latitude(),
              d.introduction(),
              List.of("201"),
              "202",
              "203",
              "204",
              "205");
      var saved =
          replacementApi.saveDraft(
              new SaveMerchantApplicationDraftCommand(
                  first.applicationId(),
                  correction.version(),
                  replacement,
                  context(OWNER, OperatorType.USER)));
      var submitted =
          replacementApi.submit(
              new SubmitMerchantApplicationCommand(
                  first.applicationId(),
                  saved.version(),
                  saved.currentRevision().revisionId(),
                  context(OWNER, OperatorType.USER)));
      var nextTask = claim(replacementApi, submitted, OPERATOR);
      var changed =
          bindEvidence(db, submitted.applicationId(), evidence()).stream()
              .map(
                  e ->
                      new ManualEvidenceItem(
                          e.materialType(),
                          e.subjectName(),
                          e.identifier() + "-NEW",
                          e.validityKind(),
                          e.validFrom(),
                          e.validTo(),
                          e.validityBasis(),
                          e.materialId(),
                          e.materialSha256()))
              .toList();
      var nextProof =
          replacementApi.recordManualVerification(
              new VerifyMerchantSubjectCommand(
                  first.applicationId(),
                  submitted.currentRevision().revisionId(),
                  submitted.version(),
                  nextTask.version(),
                  changed,
                  "已核对补正后新的证件材料",
                  true,
                  auth(),
                  context(OPERATOR, OperatorType.PLATFORM_OPERATOR)));
      assertEquals("VERIFIED", nextProof.subjectVerificationStatus());
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_subject_claim WHERE status='RELEASED'",
                  Integer.class));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_subject_claim WHERE status='ACTIVE'",
                  Integer.class));
      assertEquals(
          6,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
      var approved =
          replacementApi.decide(
              new DecideMerchantApplicationCommand(
                  first.applicationId(),
                  "APPROVE",
                  submitted.currentRevision().revisionId(),
                  nextProof.version(),
                  nextTask.version(),
                  null,
                  null,
                  true,
                  auth(),
                  context(OPERATOR, OperatorType.PLATFORM_OPERATOR)));
      assertEquals("APPROVED", approved.status());
    }
  }

  @Test
  void reviewListFiltersBeforeCountingAndReviewProjectionMasksContactName() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var fixture = fixtures.new Fixture(db, true);
      var api = fixture.api();
      var first = submit(api, draft(), OWNER);
      var d = draft();
      var secondDraft =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              d.contactPhone(),
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              d.longitude(),
              d.latitude(),
              d.introduction(),
              List.of("201"),
              "202",
              "203",
              "204",
              "205");
      var second =
          submit(fixtures.new Fixture(db, true, OWNER + 1, 201).api(), secondDraft, OWNER + 1);
      fixture.rowAccess = resource -> !resource.resourceId().equals(first.applicationId());
      var page =
          api.listForReview(
              new MerchantApplicationReviewListQuery(
                  1, 1, null, null, null, null, null, null, auth(), queryOperator()));
      assertEquals(1, page.total());
      assertEquals(
          List.of(second.applicationId()),
          page.items().stream().map(MerchantApplicationSummary::applicationId).toList());
      assertNotNull(page.items().getFirst().submittedAt());
      var detail =
          api.getForReview(
              new MerchantApplicationReviewQuery(second.applicationId(), auth(), queryOperator()));
      assertEquals("**", detail.application().currentRevision().contactName());
    }
  }

  @Test
  void permissionRevisionChangeDuringListCannotReturnAnEarlierAuthorizedPage() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var fixture = fixtures.new Fixture(db, true);
      var api = fixture.api();
      submit(api, draft(), OWNER);
      fixture.rowAccess =
          resource -> {
            fixture.authorizationRevision.incrementAndGet();
            return true;
          };
      assertEquals(
          CommonApiCodes.CONFLICT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.listForReview(
                          new MerchantApplicationReviewListQuery(
                              1, 10, null, null, null, null, null, null, auth(), queryOperator())))
              .code());
    }
  }

  @Test
  void draftRejectsTooManyPhotosAndLossyCoordinatesBeforePersistence() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      var api = fixtures.new Fixture(db, true).api();
      var d = draft();
      var tooMany =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              d.contactPhone(),
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              d.longitude(),
              d.latitude(),
              d.introduction(),
              List.of("101", "102", "103", "104", "105", "106", "107"),
              null,
              null,
              null,
              null);
      assertEquals(
          CommonApiCodes.INVALID_ARGUMENT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.createDraft(
                          new CreateMerchantApplicationCommand(
                              tooMany, context(OWNER, OperatorType.USER))))
              .code());
      var tooPrecise =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              d.contactPhone(),
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              new java.math.BigDecimal("121.12345678"),
              d.latitude(),
              d.introduction(),
              d.storePhotoAssetIds(),
              d.businessLicenseAssetId(),
              d.idCardFrontAssetId(),
              d.idCardBackAssetId(),
              d.industryLicenseAssetId());
      assertEquals(
          CommonApiCodes.INVALID_ARGUMENT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.createDraft(
                          new CreateMerchantApplicationCommand(
                              tooPrecise, context(OWNER, OperatorType.USER))))
              .code());
      assertEquals(
          0, db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_application", Integer.class));
    }
  }

  @Test
  void twoReviewersCannotBothClaimTheSameSubmittedVersion() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase();
        var pool = Executors.newFixedThreadPool(2)) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var submitted = submit(api, draft(), OWNER);
      Callable<String> first = () -> claimOutcome(api, submitted, OPERATOR);
      Callable<String> second = () -> claimOutcome(api, submitted, OPERATOR + 1);
      var outcomes = pool.invokeAll(List.of(first, second));
      var values = List.of(outcomes.get(0).get(), outcomes.get(1).get());
      assertEquals(1, values.stream().filter("CLAIMED"::equals).count());
      assertEquals(1, values.stream().filter(CommonApiCodes.CONFLICT::equals).count());
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_task WHERE status='CLAIMED'",
                  Integer.class));
    }
  }

  @Test
  void anotherClaimantCannotVerifyOrDecideEvenWithTheSameActionGrant() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var app = submit(api, draft(), OWNER);
      var task = claim(api, app, OPERATOR);
      var other = context(OPERATOR + 1, OperatorType.PLATFORM_OPERATOR);
      var verify =
          new VerifyMerchantSubjectCommand(
              app.applicationId(),
              app.currentRevision().revisionId(),
              app.version(),
              task.version(),
              bindEvidence(db, app.applicationId(), evidence()),
              "已检查原始申请材料",
              true,
              auth(),
              other);
      assertEquals(
          CommonApiCodes.CONFLICT,
          assertThrows(ApiException.class, () -> api.recordManualVerification(verify)).code());
      var decide =
          new DecideMerchantApplicationCommand(
              app.applicationId(),
              "REJECT",
              app.currentRevision().revisionId(),
              app.version(),
              task.version(),
              "需要补充完整且有效的申请材料",
              null,
              true,
              auth(),
              other);
      assertEquals(
          CommonApiCodes.CONFLICT,
          assertThrows(ApiException.class, () -> api.decide(decide)).code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_decision", Integer.class));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
    }
  }

  @Test
  void duplicateSubjectInAnotherApplicationRollsBackEvidenceAndKeepsFirstClaims() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var firstApi = fixtures.new Fixture(db, true).api();
      var first = submit(firstApi, draft(), OWNER);
      var firstTask = claim(firstApi, first, OPERATOR);
      verify(db, firstApi, first, firstTask);

      var secondApi = fixtures.new Fixture(db, true, OWNER + 1, 201).api();
      var d = draft();
      var secondDraft =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              d.contactPhone(),
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              d.longitude(),
              d.latitude(),
              d.introduction(),
              List.of("201"),
              "202",
              "203",
              "204",
              "205");
      var second = submit(secondApi, secondDraft, OWNER + 1);
      var secondTask = claim(secondApi, second, OPERATOR);
      var failure =
          assertThrows(ApiException.class, () -> verify(db, secondApi, second, secondTask));
      assertEquals(CommonApiCodes.CONFLICT, failure.code());
      assertFalse(failure.getMessage().contains(first.applicationId()));
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_credential_evidence WHERE application_id=?",
                  Integer.class,
                  Long.parseLong(second.applicationId())));
      assertEquals(
          2,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_subject_claim WHERE status='ACTIVE'",
                  Integer.class));
    }
  }

  @Test
  void knownExpiredImmutableMaterialCannotBeResubmittedAfterCorrection() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var fixture = fixtures.new Fixture(db, true);
      var api = fixture.api();
      var app = submit(api, draft(), OWNER);
      var task = claim(api, app, OPERATOR);
      var verified = verify(db, api, app, task);
      var rejected =
          api.decide(
              new DecideMerchantApplicationCommand(
                  app.applicationId(),
                  "REQUEST_CORRECTION",
                  app.currentRevision().revisionId(),
                  verified.version(),
                  task.version(),
                  "请补充清晰且完整的申请材料",
                  null,
                  true,
                  auth(),
                  context(OPERATOR, OperatorType.PLATFORM_OPERATOR)));
      var saved =
          api.saveDraft(
              new SaveMerchantApplicationDraftCommand(
                  app.applicationId(),
                  rejected.version(),
                  draft(),
                  context(OWNER, OperatorType.USER)));
      var laterApi =
          fixture.api(Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC));
      var error =
          assertThrows(
              ApiException.class,
              () ->
                  laterApi.submit(
                      new SubmitMerchantApplicationCommand(
                          app.applicationId(),
                          saved.version(),
                          saved.currentRevision().revisionId(),
                          context(OWNER, OperatorType.USER))));
      assertEquals(CommonApiCodes.CONFLICT, error.code());
      assertEquals(
          "REJECTED",
          db.jdbc()
              .queryForObject(
                  "SELECT status FROM merchant_application WHERE id=?",
                  String.class,
                  Long.parseLong(app.applicationId())));
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_task", Integer.class));
    }
  }

  @Test
  void changedHmacConfigurationCannotWriteAnyVerificationEvidence() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var app = submit(api, draft(), OWNER);
      var task = claim(api, app, OPERATOR);
      db.jdbc()
          .update(
              "UPDATE merchant_subject_lookup_policy SET key_version='key-v2' WHERE policy_slot=1");
      assertEquals(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE,
          assertThrows(ApiException.class, () -> verify(db, api, app, task)).code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
      assertEquals(
          0,
          db.jdbc().queryForObject("SELECT COUNT(*) FROM merchant_subject_claim", Integer.class));
    }
  }

  @Test
  void incompleteDraftIsSavedButInvalidProtectedPhoneCannotBeSubmitted() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var partial =
          new DraftRevisionInput(
              "星", "张", "1", null, null, null, null, null, null, null, List.of(), null, null, null,
              null);
      var first =
          api.createDraft(
              new CreateMerchantApplicationCommand(partial, context(OWNER, OperatorType.USER)));
      assertEquals("DRAFT", first.status());
      var d = draft();
      var invalidPhone =
          new DraftRevisionInput(
              d.merchantName(),
              d.contactName(),
              "1",
              d.email(),
              d.merchantTypeCode(),
              d.cityCode(),
              d.address(),
              d.longitude(),
              d.latitude(),
              d.introduction(),
              d.storePhotoAssetIds(),
              d.businessLicenseAssetId(),
              d.idCardFrontAssetId(),
              d.idCardBackAssetId(),
              d.industryLicenseAssetId());
      var saved =
          api.saveDraft(
              new SaveMerchantApplicationDraftCommand(
                  first.applicationId(),
                  first.version(),
                  invalidPhone,
                  context(OWNER, OperatorType.USER)));
      assertEquals(
          CommonApiCodes.INVALID_ARGUMENT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.submit(
                          new SubmitMerchantApplicationCommand(
                              saved.applicationId(),
                              saved.version(),
                              saved.currentRevision().revisionId(),
                              context(OWNER, OperatorType.USER))))
              .code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM merchant_application_review_task", Integer.class));
    }
  }

  private static MerchantApplicationResult submit(
      MerchantApplicationApiImpl api, DraftRevisionInput draft, long owner) {
    var created =
        api.createDraft(
            new CreateMerchantApplicationCommand(draft, context(owner, OperatorType.USER)));
    return api.submit(
        new SubmitMerchantApplicationCommand(
            created.applicationId(),
            created.version(),
            created.currentRevision().revisionId(),
            context(owner, OperatorType.USER)));
  }

  private static ReviewTaskResult claim(
      MerchantApplicationApiImpl api, MerchantApplicationResult app, long operator) {
    return api.claim(
        new ClaimMerchantApplicationCommand(
            app.applicationId(), 0, auth(), context(operator, OperatorType.PLATFORM_OPERATOR)));
  }

  private static String claimOutcome(
      MerchantApplicationApiImpl api, MerchantApplicationResult app, long operator) {
    try {
      return claim(api, app, operator).status();
    } catch (ApiException failure) {
      return failure.code();
    }
  }

  private static MerchantApplicationResult verify(
      MySqlMerchantApplicationSchemaTestDatabase db,
      MerchantApplicationApiImpl api,
      MerchantApplicationResult app,
      ReviewTaskResult task) {
    return api.recordManualVerification(
        new VerifyMerchantSubjectCommand(
            app.applicationId(),
            app.currentRevision().revisionId(),
            app.version(),
            task.version(),
            bindEvidence(db, app.applicationId(), evidence()),
            "已逐项检查原始证件材料",
            true,
            auth(),
            context(OPERATOR, OperatorType.PLATFORM_OPERATOR)));
  }

  private static CommandContext context(long actor, OperatorType type) {
    return new CommandContext(
        UUID.randomUUID().toString(),
        "application-safety-test",
        type,
        Long.toString(actor),
        type == OperatorType.USER ? "MINIAPP" : "ADMIN_WEB");
  }
}
