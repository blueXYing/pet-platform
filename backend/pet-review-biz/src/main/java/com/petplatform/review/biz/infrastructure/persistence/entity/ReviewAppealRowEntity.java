package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.time.LocalDateTime;

/** 06号 review_appeal projection (REV-002 read/write row; uk_review_appeal_once enforced). */
public final class ReviewAppealRowEntity {
    private Long id;
    private Long reviewId;
    private Long merchantId;
    private String status;
    private String reason;
    private String decisionReason;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long value) { reviewId = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String value) { decisionReason = value; }
    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long value) { decidedBy = value; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime value) { decidedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
