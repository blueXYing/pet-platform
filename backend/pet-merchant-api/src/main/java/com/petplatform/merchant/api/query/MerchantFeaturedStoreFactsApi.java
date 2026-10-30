package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * Contract 74 §3.4 featured-card store facts read (B-CARD-02 supplement to SVC-D5/D6, same
 * pattern as {@link MerchantDisplayStoreIdsApi}): one batched read of the card's merchant-owned
 * display facts — names, address, compat city, owner-maintained intro and avatar reference, and
 * the merchant promotion fields — for the stores of one card page. Visibility is re-proven with
 * the same approved three-condition conjunction plus the compat-city integrity rule; a store that
 * fails or vanished is simply absent (the composing read drops the row), a facts-source failure is
 * DEPENDENCY_UNAVAILABLE (whole-page 503). It grants no merchant-operation authority and returns
 * no contact, coordinate or audit field — the card never carries PII.
 */
public interface MerchantFeaturedStoreFactsApi {

    /**
     * One fact per surviving requested store, ascending by numeric storeId. storeIds are canonical
     * decimal Snowflake strings, at most 50 values; duplicates are collapsed; an empty request
     * returns an empty list; malformed ids are rejected with COMMON_INVALID_ARGUMENT (they are
     * internal ids, never user input).
     */
    List<FeaturedStoreFact> listFeaturedStoreFacts(FeaturedStoreFactsQuery query);

    /** At most {@link #MAX_STORE_IDS} canonical decimal store ids plus a linkage-only context. */
    record FeaturedStoreFactsQuery(List<String> storeIds, QueryContext context) {

        /** One card page is 1..20 stores; 50 keeps a bounded headroom for callers. */
        public static final int MAX_STORE_IDS = 50;

        public FeaturedStoreFactsQuery {
            storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
        }
    }

    /**
     * One store's display facts. {@code intro}/{@code avatarAssetId} are null when the merchant
     * never set them (the wire renders null, never an empty facade); {@code promoLevel} is 1..9 or
     * null; the avatar id is an opaque private-asset reference that only the signing face may
     * dereference.
     */
    record FeaturedStoreFact(
            String storeId,
            String merchantId,
            String storeName,
            String merchantName,
            String address,
            String cityCode,
            String intro,
            String avatarAssetId,
            Integer promoLevel,
            String promoTitle,
            String promoText) {}
}
