package com.petplatform.schedule.api.query;

import java.time.LocalDateTime;

/**
 * Store schedule utilization facts behind the 70号 stats surface (2026-10-19 CCR expansion,
 * contract 70 §2 排期利用率): a pure read-side aggregate over the SCH-owned minute-level
 * windows (53号 write side) and the SCH-owned confirmed reservations. availableMinutes sums
 * OPEN schedule_availability_window capacity minutes clamped to [from,to); bookedMinutes sums
 * CONFIRMED schedule_reservation overlap minutes in the same window. Minute granularity —
 * every schedule fact is minute-aligned (SSOT §13), so no rounding is applied.
 *
 * <p>Fact-only surface: it performs no OWNER proof and takes no guard — the caller (order
 * stats read service) re-proves the OWNER inside its own store guard transaction before
 * reading, exactly like every other 69/70 derived read.</p>
 */
public interface ScheduleUtilizationQueryApi {

    Utilization utilization(UtilizationQuery query);

    record UtilizationQuery(String merchantId, String storeId, LocalDateTime from,
            LocalDateTime to) {}

    record Utilization(long availableMinutes, long bookedMinutes) {}
}
