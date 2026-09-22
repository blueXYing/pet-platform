package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** SVC-D5 display eligibility input; the context is linkage-only and never an authority. */
public record MerchantDisplayEligibilityQuery(String merchantId, String storeId, QueryContext context) {}
