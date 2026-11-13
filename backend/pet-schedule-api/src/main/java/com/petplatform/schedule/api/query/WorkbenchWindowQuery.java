package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;

/** Workbench window list filters; merchantId/storeId are decimal String IDs, status optional
 * (CCR-SCH-SLOT-001: windows are store-wide unified slots, no service/kind filters remain).
 * Pagination (53号 §3.3) is opt-in: both paging fields null keeps the legacy full list,
 * either one present means paged mode with the other falling back to its default. */
public record WorkbenchWindowQuery(
        String merchantId, String storeId, String status,
        Integer page, Integer pageSize, QueryContext context) {}
