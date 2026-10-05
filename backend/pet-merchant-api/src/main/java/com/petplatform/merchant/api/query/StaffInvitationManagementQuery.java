package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** OWNER invitation list for one store, newest first; page/pageSize bounds as member list. */
public record StaffInvitationManagementQuery(String merchantId, String storeId, int page,
                                             int pageSize, QueryContext context) {}
