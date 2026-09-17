package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

public record MerchantStaffQuery(String merchantId, String storeId, String staffId, QueryContext context) {}
