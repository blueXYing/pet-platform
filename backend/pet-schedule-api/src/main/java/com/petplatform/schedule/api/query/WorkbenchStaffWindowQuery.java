package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;

/** Workbench staff availability list filters; storeId and staffId are decimal String IDs. */
public record WorkbenchStaffWindowQuery(
        String merchantId, String storeId, String staffId, QueryContext context) {}
