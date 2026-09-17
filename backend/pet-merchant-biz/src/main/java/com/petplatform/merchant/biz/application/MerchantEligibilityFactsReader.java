package com.petplatform.merchant.biz.application;

/**
 * Module-internal port for the approved application and electronic-signing facts.
 * Implementations must use the merchant DataSource and join the caller's Spring transaction so
 * merchant, store, application and signing are observed in one repeatable-read snapshot.
 */
@FunctionalInterface
public interface MerchantEligibilityFactsReader {

    Facts read(long merchantId, long storeId);

    record Facts(String applicationStatus, String signingStatus) {}
}
