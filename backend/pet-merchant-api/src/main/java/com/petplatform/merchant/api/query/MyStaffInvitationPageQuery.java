package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** Employee-side invitation list (contract 54 §7): session-scoped, no scope coordinates. */
public record MyStaffInvitationPageQuery(int page, int pageSize, QueryContext context) {}
