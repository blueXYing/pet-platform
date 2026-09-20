package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

public class MerchantApplicationEntity {
  private Long id,
      ownerUserId,
      reservedMerchantId,
      currentRevisionId,
      submittedRevisionId,
      currentReviewTaskId,
      currentDecisionId,
      reviewAuditId,
      currentCreditClaimId,
      currentIdentityClaimId,
      version;
  private String applicationNo, status, currentDecisionType, subjectVerificationStatus;
  private Boolean chainValid;
  private LocalDateTime submittedAt, reviewedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(Long v) {
    ownerUserId = v;
  }

  public Long getReservedMerchantId() {
    return reservedMerchantId;
  }

  public void setReservedMerchantId(Long v) {
    reservedMerchantId = v;
  }

  public Long getCurrentRevisionId() {
    return currentRevisionId;
  }

  public void setCurrentRevisionId(Long v) {
    currentRevisionId = v;
  }

  public Long getSubmittedRevisionId() {
    return submittedRevisionId;
  }

  public void setSubmittedRevisionId(Long v) {
    submittedRevisionId = v;
  }

  public Long getCurrentReviewTaskId() {
    return currentReviewTaskId;
  }

  public void setCurrentReviewTaskId(Long v) {
    currentReviewTaskId = v;
  }

  public Long getCurrentDecisionId() {
    return currentDecisionId;
  }

  public void setCurrentDecisionId(Long v) {
    currentDecisionId = v;
  }

  public Long getReviewAuditId() {
    return reviewAuditId;
  }

  public void setReviewAuditId(Long v) {
    reviewAuditId = v;
  }

  public Long getCurrentCreditClaimId() {
    return currentCreditClaimId;
  }

  public void setCurrentCreditClaimId(Long v) {
    currentCreditClaimId = v;
  }

  public Long getCurrentIdentityClaimId() {
    return currentIdentityClaimId;
  }

  public void setCurrentIdentityClaimId(Long v) {
    currentIdentityClaimId = v;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long v) {
    version = v;
  }

  public String getApplicationNo() {
    return applicationNo;
  }

  public void setApplicationNo(String v) {
    applicationNo = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public String getCurrentDecisionType() {
    return currentDecisionType;
  }

  public void setCurrentDecisionType(String v) {
    currentDecisionType = v;
  }

  public String getSubjectVerificationStatus() {
    return subjectVerificationStatus;
  }

  public void setSubjectVerificationStatus(String v) {
    subjectVerificationStatus = v;
  }

  public LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(LocalDateTime v) {
    submittedAt = v;
  }

  public LocalDateTime getReviewedAt() {
    return reviewedAt;
  }

  public void setReviewedAt(LocalDateTime v) {
    reviewedAt = v;
  }

  public Boolean getChainValid() {
    return chainValid;
  }

  public void setChainValid(Boolean v) {
    chainValid = v;
  }
}
