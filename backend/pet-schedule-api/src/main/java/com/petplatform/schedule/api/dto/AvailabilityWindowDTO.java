package com.petplatform.schedule.api.dto;

import java.time.OffsetDateTime;

/**
 * API07 6.3 fields, listed per window (approved 2026-09-23): effectiveCapacity is the SSOT 12.2
 * min of configuredCapacity and qualifiedAvailableStaffCount; occupiedCount reads the
 * schedule_reservation authority. Seed facts and staff-count test doubles are module-test-only -
 * the real assembly fails closed when the staff facts provider is absent.
 */
public record AvailabilityWindowDTO(
    String storeId,
    String serviceId,
    OffsetDateTime start,
    OffsetDateTime end,
    int configuredCapacity,
    int qualifiedAvailableStaffCount,
    int effectiveCapacity,
    int occupiedCount,
    int remainingCapacity,
    boolean available) {}
