package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.schedule.api.dto.AvailabilityPageDTO;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.api.query.ScheduleQueryApi;
import com.petplatform.schedule.biz.application.AvailabilityQueryService;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleReadStore;
import com.petplatform.service.api.query.ServiceQueryApi;
import java.time.Clock;
import javax.sql.DataSource;

/**
 * Local implementation of the approved availability query (API07 6.1, SCH-001/002). Production
 * wiring supplies the real staff facts provider. The nullable port retains the fail-closed
 * defense for incomplete assemblies.
 */
public final class ScheduleQueryApiImpl implements ScheduleQueryApi {
    private final AvailabilityQueryService service;

    public ScheduleQueryApiImpl(
            DataSource dataSource,
            ServiceQueryApi serviceFacts,
            QualifiedStaffFactsPort staffFacts) {
        this.service =
                new AvailabilityQueryService(
                        new ScheduleReadStore(dataSource),
                        serviceFacts,
                        staffFacts,
                        Clock.systemUTC());
    }

    @Override
    public AvailabilityPageDTO queryAvailability(AvailabilityQuery query) {
        return service.page(query);
    }
}
