package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** Internal schedule linkage query; context does not grant merchant ownership. */
public record StoreStaffFactsQuery(String storeId, QueryContext context) {}
