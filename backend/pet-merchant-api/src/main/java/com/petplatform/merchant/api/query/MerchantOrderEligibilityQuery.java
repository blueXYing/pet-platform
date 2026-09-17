package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

public record MerchantOrderEligibilityQuery(String merchantId, String storeId, QueryContext context) {}
