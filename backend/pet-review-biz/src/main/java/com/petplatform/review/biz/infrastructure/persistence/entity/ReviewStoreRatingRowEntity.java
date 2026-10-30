package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/** Public rating fact row: one store's full score_included aggregate (74号 §6.1). */
public final class ReviewStoreRatingRowEntity {
    private Long storeId;
    private Long reviewCount;
    private BigDecimal avgScore;

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getReviewCount() { return reviewCount; }
    public void setReviewCount(Long reviewCount) { this.reviewCount = reviewCount; }
    public BigDecimal getAvgScore() { return avgScore; }
    public void setAvgScore(BigDecimal avgScore) { this.avgScore = avgScore; }
}
