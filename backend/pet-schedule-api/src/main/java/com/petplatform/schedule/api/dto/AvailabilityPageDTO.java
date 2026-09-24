package com.petplatform.schedule.api.dto;

import java.time.LocalDate;
import java.util.List;

/** One availability query answer: the OPEN windows of the date range, start-ascending. */
public record AvailabilityPageDTO(
    String storeId, String serviceId, LocalDate startDate, LocalDate endDate,
    List<AvailabilityWindowDTO> items) {}
