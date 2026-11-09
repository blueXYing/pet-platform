package com.petplatform.review.biz.infrastructure.persistence.entity;

import java.math.BigDecimal;

/**
 * CCR-M-REVIEW-VIEW-001 (contract 56 §7) OWNER store aggregate row: one full-history read of
 * the store's reviews. totalReviews counts every row (score_included=0 history included);
 * scoredCount/avgScore/score1..score5 only score_included=1 rows, bucketed by
 * ROUND(composite_score). No individual review content is ever projected.
 */
public final class ReviewStoreAggregateEntity {
    private Long totalReviews;
    private Long scoredCount;
    private BigDecimal avgScore;
    private Long score1;
    private Long score2;
    private Long score3;
    private Long score4;
    private Long score5;

    public Long getTotalReviews() { return totalReviews; }
    public void setTotalReviews(Long value) { totalReviews = value; }
    public Long getScoredCount() { return scoredCount; }
    public void setScoredCount(Long value) { scoredCount = value; }
    public BigDecimal getAvgScore() { return avgScore; }
    public void setAvgScore(BigDecimal value) { avgScore = value; }
    public Long getScore1() { return score1; }
    public void setScore1(Long value) { score1 = value; }
    public Long getScore2() { return score2; }
    public void setScore2(Long value) { score2 = value; }
    public Long getScore3() { return score3; }
    public void setScore3(Long value) { score3 = value; }
    public Long getScore4() { return score4; }
    public void setScore4(Long value) { score4 = value; }
    public Long getScore5() { return score5; }
    public void setScore5(Long value) { score5 = value; }
}
