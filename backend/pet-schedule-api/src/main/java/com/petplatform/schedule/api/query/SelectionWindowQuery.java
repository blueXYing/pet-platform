package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;
import java.time.LocalDate;

/** Display query for original, selectable SCH unified slots (CCR-SCH-SLOT-001: the slot list
 * is store-wide; per-service bookability is derived from capability coverage, not a filter). */
public record SelectionWindowQuery(
        String serviceId, String storeId, LocalDate startDate, LocalDate endDate,
        QueryContext context) {}
