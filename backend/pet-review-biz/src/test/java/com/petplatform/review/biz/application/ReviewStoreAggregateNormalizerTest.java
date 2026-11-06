package com.petplatform.review.biz.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.review.api.query.ReviewQueryApi.StoreReviewAggregate;
import com.petplatform.review.biz.infrastructure.persistence.entity.ReviewStoreAggregateEntity;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CCR-M-REVIEW-VIEW-001: the aggregate normalizer turns exactly one well-formed store row into
 * the wire fact (five "1".."5" buckets, one-decimal HALF_UP average, null when nothing scored)
 * and fails closed on damaged/inconsistent reads instead of fabricating 0/null facts.
 */
class ReviewStoreAggregateNormalizerTest {

    private static ReviewStoreAggregateEntity row(
            long total, long scored, String avg, long... buckets) {
        ReviewStoreAggregateEntity row = new ReviewStoreAggregateEntity();
        row.setTotalReviews(total);
        row.setScoredCount(scored);
        row.setAvgScore(avg == null ? null : new BigDecimal(avg));
        row.setScore1(buckets[0]);
        row.setScore2(buckets[1]);
        row.setScore3(buckets[2]);
        row.setScore4(buckets[3]);
        row.setScore5(buckets[4]);
        return row;
    }

    @Test
    void wellFormedRowNormalizesToTheWireFact() {
        StoreReviewAggregate fact = ReviewService.aggregate(row(12, 10, "4.55",
                0, 1, 1, 3, 5));
        assertEquals(12L, fact.totalReviews());
        assertEquals(10L, fact.scoredCount());
        assertEquals("4.6", fact.averageScore(), "one decimal HALF_UP on the wire string");
        assertEquals(Map.of("1", 0L, "2", 1L, "3", 1L, "4", 3L, "5", 5L), fact.scoreCounts());

        StoreReviewAggregate empty = ReviewService.aggregate(row(0, 0, null, 0, 0, 0, 0, 0));
        assertEquals(0L, empty.totalReviews());
        assertEquals(0L, empty.scoredCount());
        assertNull(empty.averageScore());
        assertEquals(Map.of("1", 0L, "2", 0L, "3", 0L, "4", 0L, "5", 0L), empty.scoreCounts());
    }

    @Test
    void damagedOrInconsistentRowsFailClosed() {
        assertThrows(ApiException.class, () -> ReviewService.aggregate(null));
        assertThrows(ApiException.class, () -> ReviewService.aggregate(new ReviewStoreAggregateEntity()));
        // Buckets must partition the scored rows exactly.
        ApiException buckets = assertThrows(ApiException.class,
                () -> ReviewService.aggregate(row(5, 2, "3.7", 0, 1, 0, 0, 0)));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, buckets.code());
        // scored=0 with a non-null average is equally inconsistent.
        ApiException average = assertThrows(ApiException.class,
                () -> ReviewService.aggregate(row(1, 0, "3.7", 0, 0, 0, 0, 0)));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, average.code());
        // scored must never exceed the store total; negative counters are damaged reads.
        assertThrows(ApiException.class, () -> ReviewService.aggregate(row(1, 2, "3.7", 1, 1, 0, 0, 0)));
        assertThrows(ApiException.class, () -> ReviewService.aggregate(row(-1, 0, null, 0, 0, 0, 0, 0)));
        assertThrows(ApiException.class, () -> ReviewService.aggregate(row(1, 0, null, 0, 0, 0, 0, -1)));
    }
}
