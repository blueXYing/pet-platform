package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** page defaults 1 (1..10000), pageSize defaults 20 (1..50); the user comes from the session context. */
public record MerchantStaffMembershipQuery(int page, int pageSize, QueryContext context) {}
