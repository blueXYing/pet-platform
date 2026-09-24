package com.petplatform.schedule.biz.application;

import java.time.OffsetDateTime;

/**
 * Qualified-staff facts port (CCR-W2-API-001 schedule domain, SCH-D6 ruling 2026-09-23): the
 * SSOT 12.2 capacity formula needs "qualified available staff" for a store/service over a time
 * range. SCH-002 implements this through the MER sixth query and SCH-owned capability and staff
 * availability tables. Missing facts or a missing provider still fail closed.
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
