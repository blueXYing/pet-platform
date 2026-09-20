package com.petplatform.merchant.biz;

import static com.petplatform.merchant.biz.MerchantApplicationRuntimeMySqlTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantApplicationHttpProjectionMySqlTest {
  private final MerchantApplicationRuntimeMySqlTest fixtures =
      new MerchantApplicationRuntimeMySqlTest();

  @Test
  void ownerEditableProjectionDoesNotLeakIntoAdminOrDurableReceiptAndCreateTracksReplay()
      throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var command =
          new CreateMerchantApplicationCommand(draft(), user(UUID.randomUUID().toString()));
      var first = api.createDraftOutcome(command);
      assertTrue(first.created());
      var second = api.createDraftOutcome(command);
      assertFalse(second.created());
      assertEquals(first.receipt(), second.receipt());
      var owner =
          api.getCurrentDetail(
              new CurrentMerchantApplicationQuery(
                  new QueryContext("owner-detail-test", OperatorType.USER, Long.toString(OWNER))));
      assertEquals(draft().contactPhone(), owner.currentRevision().draft().contactPhone());
      assertEquals(draft().email(), owner.currentRevision().draft().email());
      assertNotNull(owner.currentRevision().createdAt());
      assertEquals(owner.currentRevisionId(), owner.currentRevision().revisionId());
      assertFalse(owner.toString().contains(draft().contactPhone()));
      assertEquals(
          CommonApiCodes.NOT_FOUND,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.getCurrentDetail(
                          new CurrentMerchantApplicationQuery(
                              new QueryContext(
                                  "other-owner", OperatorType.USER, Long.toString(OWNER + 1)))))
              .code());
      var submitted =
          api.submit(
              new SubmitMerchantApplicationCommand(
                  owner.applicationId(),
                  owner.version(),
                  owner.currentRevisionId(),
                  user(UUID.randomUUID().toString())));
      var review =
          api.getForReview(
              new MerchantApplicationReviewQuery(owner.applicationId(), auth(), queryOperator()));
      assertEquals(owner.currentRevisionId(), review.task().submittedRevisionId());
      assertEquals("**", review.application().currentRevision().contactName());
      assertFalse(
          review
              .application()
              .currentRevision()
              .contactPhoneMasked()
              .contains(draft().contactPhone()));
      var page =
          api.listForReview(
              new MerchantApplicationReviewListQuery(
                  1, 10, null, null, null, null, null, null, auth(), queryOperator()));
      assertEquals(owner.currentRevisionId(), page.items().getFirst().submittedRevisionId());
      assertEquals(
          "SUBJECT_VERIFICATION_PENDING", page.items().getFirst().subjectVerificationStatus());
      var task =
          api.claim(
              new ClaimMerchantApplicationCommand(
                  owner.applicationId(), 0, auth(), operator(UUID.randomUUID().toString())));
      var rejected =
          api.decide(
              new DecideMerchantApplicationCommand(
                  owner.applicationId(),
                  "REQUEST_CORRECTION",
                  owner.currentRevisionId(),
                  submitted.version(),
                  task.version(),
                  "请补充完整清晰的申请材料",
                  "内部意见不可回显",
                  true,
                  auth(),
                  operator(UUID.randomUUID().toString())));
      var decision = rejected.latestDecision();
      assertNotNull(decision.reviewDecisionId());
      assertEquals(owner.currentRevisionId(), decision.submittedRevisionId());
      for (String receipt :
          db.jdbc()
              .queryForList(
                  "SELECT receipt_json FROM merchant_command_idempotency WHERE status='SUCCEEDED'",
                  String.class)) {
        assertFalse(receipt.contains(draft().contactPhone()));
        assertFalse(receipt.contains(draft().email()));
        assertFalse(receipt.contains("内部意见不可回显"));
      }
    }
  }

  @Test
  void manualEvidenceCannotReferenceAnotherMaterialOrWrongHash() throws Exception {
    try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
      seedPolicy(db);
      var api = fixtures.new Fixture(db, true).api();
      var created =
          api.createDraft(
              new CreateMerchantApplicationCommand(draft(), user(UUID.randomUUID().toString())));
      var app =
          api.submit(
              new SubmitMerchantApplicationCommand(
                  created.applicationId(),
                  created.version(),
                  created.currentRevision().revisionId(),
                  user(UUID.randomUUID().toString())));
      var task =
          api.claim(
              new ClaimMerchantApplicationCommand(
                  app.applicationId(), 0, auth(), operator(UUID.randomUUID().toString())));
      var referenced = bindEvidence(db, app.applicationId(), evidence());
      var missingReference = new java.util.ArrayList<>(referenced);
      var missing = missingReference.getFirst();
      missingReference.set(
          0,
          new ManualEvidenceItem(
              missing.materialType(),
              missing.subjectName(),
              missing.identifier(),
              missing.validityKind(),
              missing.validFrom(),
              missing.validTo(),
              missing.validityBasis()));
      assertEquals(
          CommonApiCodes.INVALID_ARGUMENT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.recordManualVerification(
                          new VerifyMerchantSubjectCommand(
                              app.applicationId(),
                              app.currentRevision().revisionId(),
                              app.version(),
                              task.version(),
                              missingReference,
                              "已检查当前提交原件材料",
                              true,
                              auth(),
                              operator(UUID.randomUUID().toString()))))
              .code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
      var wrong = new java.util.ArrayList<>(referenced);
      var first = wrong.getFirst();
      wrong.set(
          0,
          new ManualEvidenceItem(
              first.materialType(),
              first.subjectName(),
              first.identifier(),
              first.validityKind(),
              first.validFrom(),
              first.validTo(),
              first.validityBasis(),
              first.materialId(),
              "0".repeat(64)));
      assertEquals(
          CommonApiCodes.CONFLICT,
          assertThrows(
                  ApiException.class,
                  () ->
                      api.recordManualVerification(
                          new VerifyMerchantSubjectCommand(
                              app.applicationId(),
                              app.currentRevision().revisionId(),
                              app.version(),
                              task.version(),
                              wrong,
                              "已检查当前提交原件材料",
                              true,
                              auth(),
                              operator(UUID.randomUUID().toString()))))
              .code());
      assertEquals(
          0,
          db.jdbc()
              .queryForObject("SELECT COUNT(*) FROM merchant_credential_evidence", Integer.class));
      var verified =
          api.recordManualVerification(
              new VerifyMerchantSubjectCommand(
                  app.applicationId(),
                  app.currentRevision().revisionId(),
                  app.version(),
                  task.version(),
                  referenced,
                  "已检查当前提交原件材料",
                  true,
                  auth(),
                  operator(UUID.randomUUID().toString())));
      assertEquals("VERIFIED", verified.subjectVerificationStatus());
    }
  }
}
