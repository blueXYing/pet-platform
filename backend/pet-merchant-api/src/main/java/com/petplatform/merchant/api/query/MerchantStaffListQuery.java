package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

public record MerchantStaffListQuery(String merchantId, String storeId, int page, int pageSize,
                                     String employmentStatus, Boolean serviceEnabled, QueryContext context) {}
