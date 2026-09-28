package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;
import java.time.LocalDate;

/** Display query for original, selectable SCH windows. Null kind means every applicable kind. */
public record SelectionWindowQuery(
        String serviceId, String storeId, LocalDate startDate, LocalDate endDate,
        String kind, QueryContext context) {}
