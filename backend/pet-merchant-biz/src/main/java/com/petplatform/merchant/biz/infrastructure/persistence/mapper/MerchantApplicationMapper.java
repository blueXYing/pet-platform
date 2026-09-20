package com.petplatform.merchant.biz.infrastructure.persistence.mapper;

import com.petplatform.merchant.biz.infrastructure.persistence.entity.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MerchantApplicationMapper {
  void setTimeZoneUtc();

  void setLockWaitTimeout2Seconds();

  MerchantApplicationEntity selectByOwner(@Param("ownerUserId") long ownerUserId);

  MerchantApplicationEntity selectById(@Param("id") long id);

  MerchantApplicationEntity selectByIdForUpdate(@Param("id") long id);

  MerchantApplicationEntity selectEligibilityByMerchant(@Param("merchantId") long merchantId);

  MerchantApplicationRevisionEntity selectRevision(
      @Param("applicationId") long applicationId, @Param("revisionId") long revisionId);

  List<MerchantMaterialEntity> selectRevisionMaterials(
      @Param("applicationId") long applicationId, @Param("revisionId") long revisionId);

  MerchantMaterialEntity selectMaterialByAsset(
      @Param("applicationId") long applicationId, @Param("privateAssetId") long privateAssetId);

  MerchantMaterialEntity selectRevisionMaterialByAsset(
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("privateAssetId") long privateAssetId);

  MerchantReviewTaskEntity selectTask(
      @Param("applicationId") long applicationId, @Param("taskId") long taskId);

  MerchantReviewTaskEntity selectTaskForUpdate(
      @Param("applicationId") long applicationId, @Param("taskId") long taskId);

  MerchantDecisionEntity selectDecision(
      @Param("applicationId") long applicationId, @Param("decisionId") long decisionId);

  List<MerchantEvidenceEntity> selectVerifiedEvidenceForUpdate(
      @Param("applicationId") long applicationId, @Param("revisionId") long revisionId);

  List<MerchantEvidenceEntity> selectKnownEvidenceForRevision(
      @Param("applicationId") long applicationId, @Param("revisionId") long revisionId);

  List<MerchantClaimEntity> selectActiveClaimsForUpdate(@Param("applicationId") long applicationId);

  String selectPolicyVersionForUpdate();

  int insertApplication(
      @Param("id") long id,
      @Param("ownerUserId") long ownerUserId,
      @Param("reservedMerchantId") long reservedMerchantId,
      @Param("now") LocalDateTime now);

  int insertRevision(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("revisionNo") int revisionNo,
      @Param("merchantName") String merchantName,
      @Param("contactName") String contactName,
      @Param("contactPhoneProtected") byte[] contactPhoneProtected,
      @Param("emailProtected") byte[] emailProtected,
      @Param("merchantTypeCode") String merchantTypeCode,
      @Param("cityCode") String cityCode,
      @Param("address") String address,
      @Param("longitude") BigDecimal longitude,
      @Param("latitude") BigDecimal latitude,
      @Param("introduction") String introduction,
      @Param("canonicalSha256") String canonicalSha256,
      @Param("createdByUserId") long createdByUserId,
      @Param("now") LocalDateTime now);

  int pointCurrentRevision(
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") LocalDateTime now);

  int insertMaterial(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("materialType") String materialType,
      @Param("privateAssetId") long privateAssetId,
      @Param("sha256") String sha256,
      @Param("mediaType") String mediaType,
      @Param("bytes") int bytes,
      @Param("uploadedByUserId") long uploadedByUserId,
      @Param("now") LocalDateTime now);

  int linkRevisionMaterial(
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("materialId") long materialId,
      @Param("materialType") String materialType,
      @Param("position") int position);

  int insertTask(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("submissionNo") int submissionNo,
      @Param("now") LocalDateTime now);

  int submit(
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("taskId") long taskId,
      @Param("applicationNo") String applicationNo,
      @Param("expectedVersion") long expectedVersion,
      @Param("subjectStatus") String subjectStatus,
      @Param("now") LocalDateTime now);

  int claimTask(
      @Param("applicationId") long applicationId,
      @Param("taskId") long taskId,
      @Param("expectedVersion") long expectedVersion,
      @Param("operatorId") long operatorId,
      @Param("now") LocalDateTime now);

  int releaseTask(
      @Param("applicationId") long applicationId,
      @Param("taskId") long taskId,
      @Param("expectedVersion") long expectedVersion,
      @Param("operatorId") long operatorId,
      @Param("now") LocalDateTime now);

  int insertEvidence(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("materialId") long materialId,
      @Param("materialType") String materialType,
      @Param("materialSha256") String materialSha256,
      @Param("credentialType") String credentialType,
      @Param("subjectNameProtected") byte[] subjectNameProtected,
      @Param("identifierProtected") byte[] identifierProtected,
      @Param("digest") byte[] digest,
      @Param("policySlot") int policySlot,
      @Param("keyVersion") String keyVersion,
      @Param("validityKind") String validityKind,
      @Param("validFrom") LocalDate validFrom,
      @Param("validTo") LocalDate validTo,
      @Param("validityBasisProtected") byte[] validityBasisProtected,
      @Param("operatorId") long operatorId,
      @Param("reason") String reason,
      @Param("now") LocalDateTime now);

  int insertClaim(
      @Param("id") long id,
      @Param("claimType") String claimType,
      @Param("digest") byte[] digest,
      @Param("policySlot") int policySlot,
      @Param("keyVersion") String keyVersion,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("evidenceId") long evidenceId,
      @Param("now") LocalDateTime now);

  int releaseClaim(@Param("id") long id, @Param("now") LocalDateTime now);

  int markVerified(
      @Param("applicationId") long applicationId,
      @Param("creditClaimId") long creditClaimId,
      @Param("identityClaimId") long identityClaimId,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") LocalDateTime now);

  int insertDecision(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("taskId") long taskId,
      @Param("decisionType") String decisionType,
      @Param("opinion") String opinion,
      @Param("internalNote") String internalNote,
      @Param("operatorId") long operatorId,
      @Param("authzVersion") String authzVersion,
      @Param("scopeVersion") String scopeVersion,
      @Param("requestId") byte[] requestId,
      @Param("traceId") String traceId,
      @Param("creditEvidence") MerchantEvidenceEntity creditEvidence,
      @Param("identityEvidence") MerchantEvidenceEntity identityEvidence,
      @Param("creditClaim") MerchantClaimEntity creditClaim,
      @Param("identityClaim") MerchantClaimEntity identityClaim,
      @Param("now") LocalDateTime now);

  int insertAudit(
      @Param("id") long id,
      @Param("applicationId") long applicationId,
      @Param("revisionId") Long revisionId,
      @Param("actorType") String actorType,
      @Param("actorId") long actorId,
      @Param("actionCode") String actionCode,
      @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus,
      @Param("requestId") byte[] requestId,
      @Param("traceId") String traceId,
      @Param("decisionId") Long decisionId,
      @Param("now") LocalDateTime now);

  int closeTask(
      @Param("applicationId") long applicationId,
      @Param("taskId") long taskId,
      @Param("expectedVersion") long expectedVersion,
      @Param("operatorId") long operatorId,
      @Param("now") LocalDateTime now);

  int finalizeDecision(
      @Param("applicationId") long applicationId,
      @Param("decisionId") long decisionId,
      @Param("decisionType") String decisionType,
      @Param("auditId") long auditId,
      @Param("status") String status,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") LocalDateTime now);

  int insertMerchant(
      @Param("id") long id,
      @Param("ownerUserId") long ownerUserId,
      @Param("merchantName") String merchantName,
      @Param("now") LocalDateTime now);

  int insertStore(
      @Param("id") long id,
      @Param("merchantId") long merchantId,
      @Param("storeName") String storeName,
      @Param("address") String address,
      @Param("longitude") BigDecimal longitude,
      @Param("latitude") BigDecimal latitude,
      @Param("now") LocalDateTime now);

  int insertProfile(
      @Param("merchantId") long merchantId,
      @Param("applicationId") long applicationId,
      @Param("revisionId") long revisionId,
      @Param("merchantTypeCode") String merchantTypeCode,
      @Param("cityCode") String cityCode,
      @Param("now") LocalDateTime now);

  long countForReview(
      @Param("status") String status,
      @Param("merchantTypeCode") String merchantTypeCode,
      @Param("cityCode") String cityCode);

  List<java.util.Map<String, Object>> listForReview(
      @Param("status") String status,
      @Param("merchantTypeCode") String merchantTypeCode,
      @Param("cityCode") String cityCode,
      @Param("submittedFrom") LocalDateTime submittedFrom,
      @Param("submittedTo") LocalDateTime submittedTo,
      @Param("keyword") String keyword,
      @Param("offset") int offset,
      @Param("limit") int limit);

  int insertBinding(
      @Param("id") long id,
      @Param("requestKey") byte[] requestKey,
      @Param("canonicalVersion") String canonicalVersion,
      @Param("paramsSha256") String paramsSha256,
      @Param("paramsCanonical") byte[] paramsCanonical,
      @Param("traceId") String traceId);

  MerchantCommandBindingEntity selectBindingForUpdate(@Param("requestKey") byte[] requestKey);

  int markBindingSucceeded(
      @Param("requestKey") byte[] requestKey, @Param("receiptJson") String receiptJson);
}
