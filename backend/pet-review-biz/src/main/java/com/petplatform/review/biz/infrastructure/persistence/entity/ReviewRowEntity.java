package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/** 06号 review projection the create guard needs (existence + the score fact). */
public final class ReviewRowEntity {
    private Long id;
    private Long orderId;
    private Boolean scoreIncluded;
    private BigDecimal compositeScore;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public Boolean getScoreIncluded() { return scoreIncluded; }
    public void setScoreIncluded(Boolean value) { scoreIncluded = value; }
    public BigDecimal getCompositeScore() { return compositeScore; }
    public void setCompositeScore(BigDecimal value) { compositeScore = value; }
}
