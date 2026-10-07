package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;

/** Workbench window list filters; every field is a decimal String ID, status/kind optional.
 * Pagination (53号 §3.3) is opt-in: both paging fields null keeps the legacy full list,
 * either one present means paged mode with the other falling back to its default. */
public record WorkbenchWindowQuery(
        String merchantId, String storeId, String serviceId, String windowKind, String status,
        Integer page, Integer pageSize, QueryContext context) {}
