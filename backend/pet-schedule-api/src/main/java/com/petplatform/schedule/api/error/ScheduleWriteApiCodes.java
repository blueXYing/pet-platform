package com.petplatform.schedule.api.error;

/**
 * Stable schedule write-side codes (12号 §4 additions; SCHW-D10 direction approved by the joint
 * schedule review receipt, planning/ccr/CCR-W2-API-001/schedule-review-decisions.md). HTTP
 * mapping lives in the terminal adapter: both codes are 409.
 */
public final class ScheduleWriteApiCodes {
    private ScheduleWriteApiCodes() {}

    /** A create/reopen would overlap an existing OPEN window of the same store, service and
     * window_kind, or an OPEN availability window of the same staff; adjacent half-open
     * intervals are allowed (SCHW-D3). */
    public static final String SCHEDULE_WINDOW_OVERLAP = "SCHEDULE_WINDOW_OVERLAP";

    /** A protected window/availability/capability change is blocked by already-held occupancy
     * or by the merchant/store state: close, capacity decrease or time move while
     * TEMP_LOCKED/CONFIRMED claims exist, reopen conflicts, or a staff-availability/capability
     * reduction that breaks a protected assignment or an existing reservation (SCHW-D4/D6/D7). */
    public static final String SCHEDULE_WINDOW_STATE_NOT_ALLOWED = "SCHEDULE_WINDOW_STATE_NOT_ALLOWED";
}
