package com.petplatform.schedule.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * CCR-SCH-SLOT-002 bookable-range rule (SSOT §12 row, 2026-11-13): a customer-selectable
 * slot must satisfy {@code startAt >= now + leadMinutes} and {@code startAt <= now + maxDays
 * days}. Defaults are lead 60 minutes / horizon 7 days; merchants tune them per store
 * (lead 0..1440 minutes, maxDays 1..90 days) through the 53号 booking-window PUT. An absent
 * configuration row means the defaults — nothing is backfilled. Readers fail closed (503)
 * only on structurally impossible stored values; the 0..1440/1..90 envelope itself is a
 * writer-side rule so isolated direct-row fixtures stay usable.
 */
public final class BookingWindowRule {
    /** CCR-SCH-SLOT-002 §2 defaults for an absent configuration row. */
    public static final int DEFAULT_LEAD_MINUTES = 60;
    public static final int DEFAULT_MAX_DAYS = 7;
    /** Writer-side envelope (booking-window PUT rejects outside it with 400). */
    public static final int MAX_LEAD_MINUTES = 1440;
    public static final int MAX_DAYS = 90;

    /** Resolved store range: an absent column falls back to its default independently. */
    public record Range(long leadMinutes, long maxDays) {}

    private BookingWindowRule() {}

    public static Range defaults() {
        return new Range(DEFAULT_LEAD_MINUTES, DEFAULT_MAX_DAYS);
    }

    /** Null columns mean their default; structurally impossible values fail closed (503). */
    public static Range resolve(Long leadMinutes, Long maxDays) {
        long lead = leadMinutes == null ? DEFAULT_LEAD_MINUTES : leadMinutes;
        long days = maxDays == null ? DEFAULT_MAX_DAYS : maxDays;
        if (lead < 0 || days < 1) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "booking window configuration is invalid");
        }
        return new Range(lead, days);
    }

    /** Earliest selectable start (inclusive). */
    public static Instant earliest(Instant now, Range range) {
        return now.plus(range.leadMinutes(), ChronoUnit.MINUTES);
    }

    /** Latest selectable start (inclusive). */
    public static Instant latest(Instant now, Range range) {
        return now.plus(range.maxDays(), ChronoUnit.DAYS);
    }

    public static boolean within(Instant startAt, Instant now, Range range) {
        if (startAt == null) return false;
        return !startAt.isBefore(earliest(now, range)) && !startAt.isAfter(latest(now, range));
    }

    /** Hold/reschedule admission (38号/46号): an out-of-range new reservation time is a 400
     * whose message names the bookable range; no new error code is invented. */
    public static void requireWithin(Instant startAt, Instant now, Range range) {
        if (within(startAt, now, range)) return;
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                "new reservation time is outside the bookable range: startAt must be no earlier than "
                        + earliest(now, range) + " and no later than " + latest(now, range)
                        + " (leadMinutes=" + range.leadMinutes() + ", maxDays=" + range.maxDays()
                        + ")");
    }
}
