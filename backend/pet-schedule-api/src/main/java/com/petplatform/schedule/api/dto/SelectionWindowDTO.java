package com.petplatform.schedule.api.dto;

import java.time.OffsetDateTime;

/** Original window identity and the six existing public availability fields. */
public record SelectionWindowDTO(
        String windowId, String kind, OffsetDateTime start, OffsetDateTime end,
        int effectiveCapacity, int occupiedCount, int remainingCapacity, boolean available) {}
