package com.petplatform.schedule.api.dto;

import java.time.LocalDate;
import java.util.List;

/** OPEN original windows of the requested service/date range, ordered by start and ID. */
public record SelectionWindowPageDTO(
        String storeId, String serviceId, LocalDate startDate, LocalDate endDate,
        List<SelectionWindowDTO> items) {
    public SelectionWindowPageDTO { items = List.copyOf(items); }
}
