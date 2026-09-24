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
 * Local implementation of the approved availability query (API07 6.1, SCH-001). The staff facts
 * port is deliberately optional: the production wiring passes none (SCH-002 has not delivered
 * the source) and every visible-service query fails closed per the SCH-D6 ruling; module tests
 * pass the SQL-seeded double.
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
