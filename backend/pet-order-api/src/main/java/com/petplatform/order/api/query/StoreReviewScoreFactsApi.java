package com.petplatform.order.api.query;

import java.time.OffsetDateTime;

/**
 * Review-side score summary port for the stats read (contract 70 §4): an aggregate over one
 * store's score_included reviews in a created_at window. It is an internal facts read like
 * OrderQueryApi.firstPaidAt — the consuming stats service has already re-proven the OWNER
 * coordinate under the store guard, and the payload (count + one-decimal average) opens no
 * enumeration surface. Boot adapts the review domain's face to this port; without the review
 * kernel the port is absent and reviewAvg renders null (--).
 */
public interface StoreReviewScoreFactsApi {

    ScoreSummary summary(SummaryQuery query);

    /** ids are canonical decimal strings; the window is a half-open UTC instant range. */
    record SummaryQuery(String merchantId, String storeId, OffsetDateTime from, OffsetDateTime to) {}

    /** avgScore is "0.0".."5.0" (one decimal) or null when the window holds no reviews. */
    record ScoreSummary(long reviewCount, String avgScore) {}
}
