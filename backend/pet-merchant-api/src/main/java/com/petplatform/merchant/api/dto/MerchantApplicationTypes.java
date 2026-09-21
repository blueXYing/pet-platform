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
      String validityBasis,
      String materialId,
      String materialSha256) {
    public ManualEvidenceItem(
        String materialType,
        String subjectName,
        String identifier,
        String validityKind,
        LocalDate validFrom,
        LocalDate validTo,
        String validityBasis) {
      this(
          materialType,
          subjectName,
          identifier,
          validityKind,
          validFrom,
          validTo,
          validityBasis,
          null,
          null);
    }

    public static ManualEvidenceItem fromReference(
        String materialId,
        String materialSha256,
        String credentialType,
        String subjectName,
        String identifier,
        String validityKind,
        LocalDate validFrom,
        LocalDate validTo) {
      String materialType =
          switch (credentialType) {
            case "CREDIT_CODE" -> "BUSINESS_LICENSE";
            case "IDENTITY_NUMBER" -> "ID_CARD_BACK";
            case "INDUSTRY_LICENSE" -> "INDUSTRY_LICENSE";
            default -> throw new IllegalArgumentException("unsupported credential type");
          };
      String basis =
          "LONG_TERM".equals(validityKind)
              ? "MANUAL_LONG_TERM_ATTESTATION:" + materialId + ":" + materialSha256
              : null;
      return new ManualEvidenceItem(
          materialType,
          subjectName,
          identifier,
          validityKind,
          validFrom,
          validTo,
          basis,
          materialId,
          materialSha256);
    }

    @Override
    public String toString() {
      return "ManualEvidenceItem[REDACTED]";
    }
  }

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
      String industryLicenseAssetId,
      OffsetDateTime createdAt) {}

  public record DecisionView(
      String decisionType,
      String opinion,
      OffsetDateTime decidedAt,
      String reviewDecisionId,
      String submittedRevisionId) {}

  /**
   * Owner-only editable projection; never persisted in an idempotency receipt or exposed to admin.
   */
  public record OwnerRevisionView(
      String revisionId, String revisionNo, DraftRevisionInput draft, OffsetDateTime createdAt) {
    @Override
    public String toString() {
      return "OwnerRevisionView[REDACTED]";
    }
  }

  public record OwnerApplicationDetail(
      String applicationId,
      String applicationNo,
      String reservedMerchantId,
      String status,
      long version,
      String currentRevisionId,
      OwnerRevisionView currentRevision,
      OffsetDateTime submittedAt,
      OffsetDateTime reviewedAt,
      DecisionView latestDecision,
      String subjectVerificationStatus) {
    @Override
    public String toString() {
      return "OwnerApplicationDetail[REDACTED]";
    }
  }

  public record ApplicationCommandOutcome(MerchantApplicationResult receipt, boolean created) {}

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
      OffsetDateTime claimedAt,
      String submittedRevisionId) {}

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
      String taskStatus,
      String submittedRevisionId,
      String subjectVerificationStatus) {}

  /** Registered object references for one submitted revision; never watermarked content hashes. */
  public record MaterialReference(
      String materialId,
      String assetId,
      String materialSha256,
      String materialType,
      int position) {}

  public record MerchantApplicationReviewDetail(
      MerchantApplicationResult application,
      ReviewTaskResult task,
      List<MaterialReference> materialReferences) {
    public MerchantApplicationReviewDetail {
      materialReferences = List.copyOf(materialReferences);
    }
  }

  public record MerchantApplicationPage(
      int page, int pageSize, long total, List<MerchantApplicationSummary> items) {}

  public record MerchantApplicationScopeFact(
      String applicationId,
      String reservedMerchantId,
      String cityCode,
      String ownerUserId,
      String submittedRevisionId,
      String scopeVersion) {}

  /**
   * Trusted input for proving that an admin may inspect one submitted private material. The
   * implementation joins the caller's transaction and locks the current application/task.
   */
  public record MerchantPrivateMaterialAccessQuery(
      String applicationId, String submittedRevisionId, String privateAssetId, String operatorId) {}

  /** No object key, public URL, protected subject value, or bearer token is exposed here. */
  public record MerchantPrivateMaterialAccessFact(
      String applicationId,
      String merchantId,
      String cityCode,
      String ownerUserId,
      String scopeVersion,
      String materialId,
      String privateAssetId,
      String materialSha256,
      String materialType,
      String mediaType,
      int bytes,
      String submittedRevisionId,
      String taskStatus,
      String claimantOperatorId,
      String taskVersion) {}

  public record MerchantApplicationEligibilityFact(
      String applicationId,
      String merchantId,
      String status,
      long version,
      String reviewDecisionId,
      OffsetDateTime reviewedAt) {}
}
