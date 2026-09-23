package com.petplatform.service.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** service_review_decision row (append-only, 33号). */
public final class ServiceReviewDecisionEntity {
    private Long id;
    private Long serviceId;
    private Integer submissionNo;
    private String decisionType;
    private String opinion;
    private Long decidedByOperatorId;
    private LocalDateTime decidedAt;
    private String authzVersion;
    private String scopeVersion;
    private byte[] requestId;
    private String traceId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public Integer getSubmissionNo() { return submissionNo; }
    public void setSubmissionNo(Integer submissionNo) { this.submissionNo = submissionNo; }
    public String getDecisionType() { return decisionType; }
    public void setDecisionType(String decisionType) { this.decisionType = decisionType; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
    public Long getDecidedByOperatorId() { return decidedByOperatorId; }
    public void setDecidedByOperatorId(Long decidedByOperatorId) { this.decidedByOperatorId = decidedByOperatorId; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getAuthzVersion() { return authzVersion; }
    public void setAuthzVersion(String authzVersion) { this.authzVersion = authzVersion; }
    public String getScopeVersion() { return scopeVersion; }
    public void setScopeVersion(String scopeVersion) { this.scopeVersion = scopeVersion; }
    public byte[] getRequestId() { return requestId; }
    public void setRequestId(byte[] requestId) { this.requestId = requestId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
