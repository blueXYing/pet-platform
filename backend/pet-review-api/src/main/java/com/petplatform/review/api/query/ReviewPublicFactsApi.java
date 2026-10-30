package com.petplatform.review.api.query;

import java.util.List;

/**
 * Public C-side review fact surface (74号 服务目录页契约 §6.1; CCR-SVC-CARD-001 B-STAT-01).
 *
 * <p>This port is deliberately separate from the internal OWNER/stats faces on
 * {@link ReviewQueryApi}: it carries no merchant/store ownership context, no session subject
 * and no review detail. One call answers the rating facts of a batch of stores:
 * {@code count + average} over ALL {@code score_included = 1} reviews (no created_at window),
 * with the average rendered as a one-decimal 0.0..5.0 wire string. A store with no included
 * review is reported explicitly as {@code reviewCount = 0, avgScore = null} (never a fake 0
 * score); the "reviewCount < 5" presentation rule belongs to the consuming card aggregator
 * (74号 §5.4/§6.1), not to this fact read. Persistence failure surfaces as
 * COMMON_DEPENDENCY_UNAVAILABLE so the consumer can degrade to {@code available=false}.
 *
 * <p>No review content, user or order coordinate is returned — the fact shape opens no
 * enumeration surface.
 */
public interface ReviewPublicFactsApi {

    /**
     * One fact per distinct requested store, in first-request order; a requested store without
     * included reviews is present with {@code reviewCount = 0, avgScore = null}. An empty
     * request returns an empty list. Malformed store ids are rejected with
     * COMMON_INVALID_ARGUMENT (they are internal Snowflake decimal strings, never user input).
     */
    List<StoreRatingFact> getStoreRatings(StoreRatingQuery query);

    /** storeIds are canonical decimal Snowflake strings; duplicates are collapsed. */
    record StoreRatingQuery(List<String> storeIds) {
        public StoreRatingQuery {
            storeIds = List.copyOf(storeIds);
        }
    }

    /**
     * One store's public rating fact. {@code avgScore} is a one-decimal 0.0..5.0 wire string
     * and is null exactly when {@code reviewCount} is 0.
     */
    record StoreRatingFact(String storeId, long reviewCount, String avgScore) {}
}
