package com.petplatform.schedule.api.query;

import com.petplatform.schedule.api.dto.AvailabilityPageDTO;

/**
 * API07 6.1 (CCR-W2-API-001 schedule domain, approved 2026-09-23). The availability query is the
 * only member delivered by SCH-001; getReservation and ScheduleCommandApi stay with SCH-003 and
 * are not declared here until that slice lands them.
 */
public interface ScheduleQueryApi {

    /**
     * Minute-level bookable windows of one service within a date range (HTTP10 3.4). Visibility
     * follows the approved four-condition conjunction consumed from the service domain; the
     * qualified-staff facts are mandatory for capacity (SSOT 12.2 min formula) and their absence
     * fails closed with DEPENDENCY_UNAVAILABLE - the real assembly carries no provider until
     * SCH-002 delivers the staff facts source.
     */
    AvailabilityPageDTO queryAvailability(AvailabilityQuery query);
}
