package com.petplatform.merchant.biz.application;

/**
 * Internal port for the authoritative merchant application review projection. An implementation
 * must join the caller's merchant DataSource transaction; absent or unreadable application facts
 * are dependency failures and must never be reconstructed from merchant status.
 */
@FunctionalInterface
public interface ApplicationReviewFactsReader {
    Facts read(long merchantId);

    record Facts(String applicationStatus) {}
}
