package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** Candidate merchant/store only; ownership is resolved server-side from the session. */
public record MerchantAdmissionQuery(String merchantId, String storeId, QueryContext context) {}
