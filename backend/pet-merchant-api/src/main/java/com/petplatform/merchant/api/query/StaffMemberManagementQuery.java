package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** OWNER member list for one store; page defaults 1 (1..10000), pageSize defaults 20 (1..50). */
public record StaffMemberManagementQuery(String merchantId, String storeId, int page, int pageSize,
                                         QueryContext context) {}
