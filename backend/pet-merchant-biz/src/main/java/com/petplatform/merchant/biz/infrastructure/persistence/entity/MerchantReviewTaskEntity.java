package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

public class MerchantReviewTaskEntity {
  private Long id, applicationId, submittedRevisionId, claimedByOperatorId, version;
  private Integer submissionNo;
  private String status;
  private LocalDateTime claimedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getApplicationId() {
    return applicationId;
  }

  public void setApplicationId(Long v) {
    applicationId = v;
  }

  public Long getSubmittedRevisionId() {
    return submittedRevisionId;
  }

  public void setSubmittedRevisionId(Long v) {
    submittedRevisionId = v;
  }

  public Long getClaimedByOperatorId() {
    return claimedByOperatorId;
  }

  public void setClaimedByOperatorId(Long v) {
    claimedByOperatorId = v;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long v) {
    version = v;
  }

  public Integer getSubmissionNo() {
    return submissionNo;
  }

  public void setSubmissionNo(Integer v) {
    submissionNo = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public LocalDateTime getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(LocalDateTime v) {
    claimedAt = v;
  }
}
