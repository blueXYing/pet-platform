package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;

/** Workbench window list filters; every field is a decimal String ID, status/kind optional. */
public record WorkbenchWindowQuery(
        String merchantId, String storeId, String serviceId, String windowKind, String status,
        QueryContext context) {}
