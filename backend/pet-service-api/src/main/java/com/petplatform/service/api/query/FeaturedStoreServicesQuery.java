package com.petplatform.service.api.query;

import com.petplatform.common.QueryContext;
import java.util.List;

/**
 * Contract 74 §3.4/§6.5 batch service read for the featured page slice (B-CARD-01): every ACTIVE
 * service of the requested stores in one query so the top-2-per-store selection never needs a
 * query per store. storeIds are canonical decimal Snowflake strings, at most 50 values (one page
 * of cards is 1..20 stores); duplicates are collapsed. The context is linkage-only.
 */
public record FeaturedStoreServicesQuery(List<String> storeIds, QueryContext context) {

    /** One card page is at most 20 stores; 50 keeps a bounded headroom for callers. */
    public static final int MAX_STORE_IDS = 50;

    public FeaturedStoreServicesQuery {
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }
}
