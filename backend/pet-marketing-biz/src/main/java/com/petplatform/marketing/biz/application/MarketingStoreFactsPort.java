package com.petplatform.marketing.biz.application;

/**
 * Merchant-store facts needed by the marketing kernel: C-visible display facts, merchant
 * promotion fields, and ownership/application facts for enrollment. Boot adapts the merchant
 * owner's read APIs; a missing/unavailable adapter fails closed.
 */
public interface MarketingStoreFactsPort {

    record StoreFacts(
            boolean visible,
            String merchantId,
            String storeName,
            String cityCode,
            String address,
            Integer promoLevel,
            String promoTitle,
            String promoText) {}

    record OwnershipFacts(
            boolean owned,
            boolean merchantActive,
            boolean storeActive,
            boolean applicationApproved) {}

    StoreFacts store(long storeId);

    OwnershipFacts ownership(long userId, long merchantId, long storeId);
}
