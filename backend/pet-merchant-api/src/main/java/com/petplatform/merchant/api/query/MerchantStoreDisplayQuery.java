package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** STR-D6 single-store display input; the context is linkage-only and never an authority. */
public record MerchantStoreDisplayQuery(String storeId, QueryContext context) {}
