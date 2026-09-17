package com.petplatform.merchant.api.dto;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Approved MER-001 application/review contract value types. */
public final class MerchantApplicationTypes {
  private MerchantApplicationTypes() {}

  public record DraftRevisionInput(
      String merchantName,
      String contactName,
      String contactPhone,
      String email,
      String merchantTypeCode,
      String cityCode,
      String address,
      BigDecimal longitude,
      BigDecimal latitude,
      String introduction,
      List<String> storePhotoAssetIds,
      String businessLicenseAssetId,
      String idCardFrontAssetId,
      String idCardBackAssetId,
      String industryLicenseAssetId) {}

  public record AdminAuthorizationReference(String sessionId, long sessionGeneration) {}

  public record CreateMerchantApplicationCommand(
      DraftRevisionInput draft, CommandContext context) {}

  public record SaveMerchantApplicationDraftCommand(
      String applicationId,
      long expectedVersion,
      DraftRevisionInput draft,
      CommandContext context) {}

  public record SubmitMerchantApplicationCommand(
      String applicationId, long expectedVersion, String revisionId, CommandContext context) {}

  public record ClaimMerchantApplicationCommand(
      String applicationId,
      long expectedTaskVersion,
      AdminAuthorizationReference authorization,
      CommandContext context) {}

  public record ReleaseMerchantApplicationCommand(
      String applicationId,
      long expectedTaskVersion,
      AdminAuthorizationReference authorization,
      CommandContext context) {}

  public record ManualEvidenceItem(
      String materialType,
      String subjectName,
      String identifier,
      String validityKind,
      LocalDate validFrom,
      LocalDate validTo,
      String validityBasis) {}

  public record VerifyMerchantSubjectCommand(
      String applicationId,
      String submissionRevisionId,
      long expectedVersion,
      long expectedTaskVersion,
      List<ManualEvidenceItem> evidenceItems,
      String reason,
      Boolean confirmed,
      AdminAuthorizationReference authorization,
      CommandContext context) {}

  public record DecideMerchantApplicationCommand(
      String applicationId,
      String decisionType,
      String submissionRevisionId,
      long expectedVersion,
      long expectedTaskVersion,
      String opinion,
      String internalNote,
      Boolean confirmed,
      AdminAuthorizationReference authorization,
      CommandContext context) {}

  public record CurrentMerchantApplicationQuery(QueryContext context) {}

  public record MerchantApplicationReviewListQuery(
      int page,
      int pageSize,
      String status,
      String merchantTypeCode,
      String cityCode,
      OffsetDateTime submittedFrom,
      OffsetDateTime submittedTo,
      String keyword,
      AdminAuthorizationReference authorization,
      QueryContext context) {}

  public record MerchantApplicationReviewQuery(
      String applicationId, AdminAuthorizationReference authorization, QueryContext context) {}

  public record MerchantApplicationScopeQuery(String applicationId) {}

  public record MerchantApplicationEligibilityQuery(String merchantId) {}

  public record RevisionView(
      String revisionId,
      String revisionNo,
      String merchantName,
      String contactName,
      String contactPhoneMasked,
      String emailMasked,
      String merchantTypeCode,
      String cityCode,
      String address,
      BigDecimal longitude,
      BigDecimal latitude,
      String introduction,
      List<String> storePhotoAssetIds,
      String businessLicenseAssetId,
      String idCardFrontAssetId,
      String idCardBackAssetId,
      String industryLicenseAssetId) {}

  public record DecisionView(String decisionType, String opinion, OffsetDateTime decidedAt) {}

  public record MerchantApplicationResult(
      String applicationId,
      String applicationNo,
      String reservedMerchantId,
      String status,
      long version,
      RevisionView currentRevision,
      OffsetDateTime submittedAt,
      OffsetDateTime reviewedAt,
      DecisionView latestDecision,
      String subjectVerificationStatus) {}

  public record ReviewTaskResult(
      String applicationId,
      String taskId,
      String status,
      long version,
      String claimedByOperatorId,
      OffsetDateTime claimedAt) {}

  public record MerchantApplicationSummary(
      String applicationId,
      String applicationNo,
      String reservedMerchantId,
      String status,
      long version,
      String merchantName,
      String merchantTypeCode,
      String cityCode,
      OffsetDateTime submittedAt,
      String taskStatus) {}

  public record MerchantApplicationReviewDetail(
      MerchantApplicationResult application, ReviewTaskResult task) {}

  public record MerchantApplicationPage(
      int page, int pageSize, long total, List<MerchantApplicationSummary> items) {}

  public record MerchantApplicationScopeFact(
      String applicationId,
      String reservedMerchantId,
      String cityCode,
      String ownerUserId,
      String submittedRevisionId,
      String scopeVersion) {}

  public record MerchantApplicationEligibilityFact(
      String applicationId,
      String merchantId,
      String status,
      long version,
      String reviewDecisionId,
      OffsetDateTime reviewedAt) {}
}
