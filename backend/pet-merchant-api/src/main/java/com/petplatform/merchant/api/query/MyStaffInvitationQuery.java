package com.petplatform.merchant.api.query;

import com.petplatform.common.QueryContext;

/** Employee confirm-page read; the session user is the caller, phone equality is server-side. */
public record MyStaffInvitationQuery(String invitationId, QueryContext context) {}
