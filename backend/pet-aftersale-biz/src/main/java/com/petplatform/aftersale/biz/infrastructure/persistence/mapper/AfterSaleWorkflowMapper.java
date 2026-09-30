package com.petplatform.aftersale.biz.infrastructure.persistence.mapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AfterSaleWorkflowMapper {
 void utc(); void lockWait(); LocalDateTime now();
 int reserve(Map<String,Object> values); Binding binding(Map<String,Object> key); Binding bindingById(@Param("id") long id);
 int succeed(@Param("id") long id,@Param("result") byte[] result);
 CaseRow hint(@Param("id") long id); CaseRow row(@Param("id") long id); List<CaseRow> active(@Param("order") long order);
 List<CaseRow> finals(@Param("order") long order);
 long countCases(Map<String,Object> filters); List<CaseRow> listCases(Map<String,Object> filters);
 int create(Map<String,Object> values); int transition(Map<String,Object> values); int insertTransition(Map<String,Object> values);
 Transition transitionByCommand(@Param("id") long id); Transition latestTransition(@Param("caseId") long caseId); VerificationProof verificationProof(@Param("caseId") long caseId); int log(Map<String,Object> values);
 int insertBatch(Map<String,Object> values); int insertAsset(Map<String,Object> values);
 List<Batch> batches(@Param("caseId") long caseId); Batch batch(@Param("id") long id); List<AssetRow> assets(@Param("batch") long batch);
 int insertSupplement(Map<String,Object> values); Supplement supplement(@Param("id") long id);
 int closeSupplement(Map<String,Object> values); List<Supplement> openSupplements(@Param("caseId") long caseId);
 int insertDecision(Map<String,Object> values); Decision decision(@Param("id") long id); int bindRefund(Map<String,Object> values);
 List<Supplement> scanDue(@Param("after") long after);
 int recordIssue(@Param("supplement") long supplement,@Param("caseId") long caseId,@Param("store") Long store,@Param("code") String code);
 int resolveIssue(@Param("supplement") long supplement);
 class VerificationProof {public Long verificationId,orderId,storeId,aftersaleId,caseVersion;public String caseStatus;public Boolean invalidated;public LocalDateTime verifiedAt;}
 class Binding {public Long id,actorId;public String state,payloadSha256,canonicalVersion;public byte[] commandNamespace,actorType,scope,requestId,canonicalBytes,resultBytes;public Integer resultVersion;}
 class CaseRow {
  public Long id,orderId,userId,merchantId,storeId,version,creatorCommandId,createdEventId,decisionId,refundOrderId,currentSupplementId,duplicateFinalCaseId;
  public Integer activeFlag,workflowRevision;public String status,sourceStage,cityCode,scopeVersion,typeCode,demandCode,finalSetVersion,decisionType;
  public BigDecimal requestedAmount,decisionAmount;public byte[] originCipher,contentCipher;
  public LocalDateTime createdAt,acceptedAt,resolvedAt,invalidatedAt,closedAt,eligibilityAnchor,eligibilityDeadline;
 }
 class Transition {public Long commandId,aftersaleId,orderId,caseVersion,eventId,actorId,evidenceBatchId,supplementId,decisionId,refundOrderId;public String fromStatus,toStatus,action,actorType;public LocalDateTime occurredAt;public byte[] detailCipher;}
 class Batch {public Long id,aftersaleId,commandId,submitterId,supplementId;public String submitterType,contentSha256,moderationVersion;public byte[] contentCipher;public LocalDateTime createdAt;}
 class AssetRow {public Long batchId,assetId,ownerUserId,assetBytes;public String objectSha256,objectVersionRef,assetFactVersion,mediaType;}
 class Supplement {public Long id,aftersaleId,requestCommandId,completionCommandId,completionVerificationId;public String targetParty,status;public byte[] reasonCipher;public LocalDateTime deadline,createdAt,closedAt;}
 class Decision {public Long id,aftersaleId,orderId,storeId,commandId,eventId,actorId,sourceCaseVersion,refundOrderId,refundNo,refundCreatedEventId;
  public String decisionType,authzVersion,scopeVersion,orderTokenHash;public BigDecimal refundAmount,paidAmount;public byte[] reasonCipher,proofCipher;
  public LocalDateTime decidedAt,refundCreatedAt;}
}
