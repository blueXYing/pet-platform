package com.petplatform.merchant.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

public class MerchantDecisionEntity {
  private Long id, submittedRevisionId;

  public Long getSubmittedRevisionId() { return submittedRevisionId; }
  public void setSubmittedRevisionId(Long value) { submittedRevisionId = value; }
  private String decisionType, opinion;
  private LocalDateTime decidedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getDecisionType() {
    return decisionType;
  }

  public void setDecisionType(String v) {
    decisionType = v;
  }

  public String getOpinion() {
    return opinion;
  }

  public void setOpinion(String v) {
    opinion = v;
  }

  public LocalDateTime getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(LocalDateTime v) {
    decidedAt = v;
  }
}
