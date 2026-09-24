package com.petplatform.schedule.biz.application;

import java.time.OffsetDateTime;

/**
 * Qualified-staff facts port (CCR-W2-API-001 schedule domain, SCH-D6 ruling 2026-09-23): the
 * SSOT 12.2 capacity formula needs "qualified available staff" for a store/service over a time
 * range. The real source (merchant-domain sixth query joined with the schedule-domain capability
 * and staff-time-window tables) arrives with SCH-002 - until then the real assembly provides NO
 * implementation and availability fails closed (503 COMMON_DEPENDENCY_UNAVAILABLE); SQL-seeded
 * test doubles are module-test-only.
 */
public interface QualifiedStaffFactsPort {

    /**
     * Count of staff qualified for the service and available over the whole [from, to) range.
     * Implementations must fail with ApiException DEPENDENCY_UNAVAILABLE when their facts source
     * is unavailable; they must never invent a count.
     */
    int countQualifiedAvailableStaff(
        String storeId, String serviceId, OffsetDateTime from, OffsetDateTime to);
}
