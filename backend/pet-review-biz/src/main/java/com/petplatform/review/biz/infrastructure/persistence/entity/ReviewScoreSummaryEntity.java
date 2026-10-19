package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/** Contract-70 stats aggregate row: one store's score_included reviews in a created window. */
public final class ReviewScoreSummaryEntity {
    private Long reviewCount;
    private BigDecimal avgScore;

    public Long getReviewCount() { return reviewCount; }
    public void setReviewCount(Long reviewCount) { this.reviewCount = reviewCount; }
    public BigDecimal getAvgScore() { return avgScore; }
    public void setAvgScore(BigDecimal avgScore) { this.avgScore = avgScore; }
}
