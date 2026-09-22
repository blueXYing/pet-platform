package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** page defaults 1 (1..10000), pageSize defaults 20 (1..50); owner scope comes from context. */
public record MerchantMembershipQuery(int page, int pageSize, QueryContext context) {}
