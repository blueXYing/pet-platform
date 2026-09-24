package com.petplatform.schedule.api.query;

import com.petplatform.common.QueryContext;
import java.time.LocalDate;

/**
 * API07 6.1 shape (approved 2026-09-23): dates are calendar days in the platform business zone
 * (Asia/Shanghai, supplement 23 section 2) interpreted as [startDate 00:00, endDate+1 00:00).
 */
public record AvailabilityQuery(
    String serviceId, String storeId, LocalDate startDate, LocalDate endDate, QueryContext context) {}
