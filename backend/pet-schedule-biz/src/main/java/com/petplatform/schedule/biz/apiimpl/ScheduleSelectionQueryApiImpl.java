package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;
import com.petplatform.schedule.api.query.ScheduleSelectionQueryApi;
import com.petplatform.schedule.api.query.SelectionWindowQuery;
import com.petplatform.schedule.biz.application.SelectionWindowQueryService;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore;
import com.petplatform.service.api.query.ServiceQueryApi;
import java.time.Clock;
import javax.sql.DataSource;

/** Injectable SCH read implementation for the consumer selection route
 * (CCR-SCH-STAFF-OFFLINE-001: capacity is the configured slot capacity). */
public final class ScheduleSelectionQueryApiImpl implements ScheduleSelectionQueryApi {
    private final SelectionWindowQueryService service;

    public ScheduleSelectionQueryApiImpl(DataSource source, ServiceQueryApi services) {
        service = new SelectionWindowQueryService(new SelectionReadStore(source), services,
                Clock.systemUTC());
    }

    @Override
    public SelectionWindowPageDTO querySelectableWindows(SelectionWindowQuery query) {
        return service.page(query);
    }
}
