package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** Target merchant store for current STAFF identity facts; ids are public String forms. */
public record MerchantStaffIdentityFactsQuery(String merchantId, String storeId, QueryContext context) {}
