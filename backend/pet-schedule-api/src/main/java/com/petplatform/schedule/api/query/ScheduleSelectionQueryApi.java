package com.petplatform.schedule.api.query;

import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;

/** Read-only display projection; the guarded hold is the authority for a reservation. */
public interface ScheduleSelectionQueryApi {
    SelectionWindowPageDTO querySelectableWindows(SelectionWindowQuery query);
}
