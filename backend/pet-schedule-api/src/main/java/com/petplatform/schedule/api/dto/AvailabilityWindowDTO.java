package com.petplatform.schedule.api.dto;

import java.time.OffsetDateTime;

/**
 * API07 6.3 fields, listed per window (approved 2026-09-23). CCR-SCH-STAFF-OFFLINE-001
 * (2026-11-16): effectiveCapacity IS the configuredCapacity (staff scheduling is
 * offline-managed; no qualified-staff minimum). {@link #qualifiedAvailableStaffCount()} is
 * DEPRECATED — it is retained for shape compatibility and always mirrors the configured
 * capacity; the HTTP projection never exposes it. occupiedCount reads the
 * schedule_reservation authority.
 */
public record AvailabilityWindowDTO(
    String storeId,
    String serviceId,
    OffsetDateTime start,
    OffsetDateTime end,
    int configuredCapacity,
    @Deprecated int qualifiedAvailableStaffCount,
    int effectiveCapacity,
    int occupiedCount,
    int remainingCapacity,
    boolean available) {}
