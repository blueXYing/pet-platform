package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Merchant list join row: the 06号 review columns plus the one-appeal overlay (56号 §3). */
public final class ReviewListRowEntity {
    private Long id;
    private Long orderId;
    private BigDecimal storeScore;
    private BigDecimal serviceScore;
    private BigDecimal staffScore;
    private BigDecimal compositeScore;
    private Boolean scoreIncluded;
    private String visibilityStatus;
    private String content;
    private LocalDateTime createdAt;
    private String appealStatus;
    private Long appealId;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public BigDecimal getStoreScore() { return storeScore; }
    public void setStoreScore(BigDecimal value) { storeScore = value; }
    public BigDecimal getServiceScore() { return serviceScore; }
    public void setServiceScore(BigDecimal value) { serviceScore = value; }
    public BigDecimal getStaffScore() { return staffScore; }
    public void setStaffScore(BigDecimal value) { staffScore = value; }
    public BigDecimal getCompositeScore() { return compositeScore; }
    public void setCompositeScore(BigDecimal value) { compositeScore = value; }
    public Boolean getScoreIncluded() { return scoreIncluded; }
    public void setScoreIncluded(Boolean value) { scoreIncluded = value; }
    public String getVisibilityStatus() { return visibilityStatus; }
    public void setVisibilityStatus(String value) { visibilityStatus = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public String getAppealStatus() { return appealStatus; }
    public void setAppealStatus(String value) { appealStatus = value; }
    public Long getAppealId() { return appealId; }
    public void setAppealId(Long value) { appealId = value; }
}
