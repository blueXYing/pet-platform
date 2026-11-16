package com.petplatform.schedule.api.query;

import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowPage;

/**
 * Merchant workbench schedule reads (SCH-D10: fine-grained states owned by SCH-004). Owner
 * admission, target ownership and fail-closed facts handling mirror the write commands; no
 * guard is taken because these are read-only projections. CCR-SCH-STAFF-OFFLINE-001
 * (2026-11-16) removed the staff availability-window list — staff scheduling is
 * offline-managed.
 */
public interface ScheduleMerchantQueryApi {

    WindowPage listWindows(WorkbenchWindowQuery query);
}
