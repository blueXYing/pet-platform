package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 06号 review projection. REV-001 used the existence/score columns only; REV-002 extends the
 * same row with the read-face columns (authority coordinates, dimension scores, visibility,
 * content, timestamps) — one entity, one table, no schema change.
 */
public final class ReviewRowEntity {
    private Long id;
    private Long orderId;
    private Long merchantId;
    private Long storeId;
    private Boolean scoreIncluded;
    private BigDecimal compositeScore;
    private BigDecimal storeScore;
    private BigDecimal serviceScore;
    private BigDecimal staffScore;
    private String visibilityStatus;
    private String content;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public Boolean getScoreIncluded() { return scoreIncluded; }
    public void setScoreIncluded(Boolean value) { scoreIncluded = value; }
    public BigDecimal getCompositeScore() { return compositeScore; }
    public void setCompositeScore(BigDecimal value) { compositeScore = value; }
    public BigDecimal getStoreScore() { return storeScore; }
    public void setStoreScore(BigDecimal value) { storeScore = value; }
    public BigDecimal getServiceScore() { return serviceScore; }
    public void setServiceScore(BigDecimal value) { serviceScore = value; }
    public BigDecimal getStaffScore() { return staffScore; }
    public void setStaffScore(BigDecimal value) { staffScore = value; }
    public String getVisibilityStatus() { return visibilityStatus; }
    public void setVisibilityStatus(String value) { visibilityStatus = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
