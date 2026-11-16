package com.petplatform.boot.config;

import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end schedule availability (CCR-W2-API-001 schedule domain, SCH-D6 ruling 2026-09-23).
 * CCR-SCH-STAFF-OFFLINE-001 (2026-11-16): the qualified-staff facts provider assembly is gone
 * — capacity is the configured slot capacity, so the read needs only service visibility facts.
 * Switch pet.schedule.query.enabled defaults to off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.schedule.query", name = "enabled", havingValue = "true")
public class ScheduleQueryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix="pet.schedule.selection",name="enabled",havingValue="true")
    com.petplatform.schedule.api.query.ScheduleSelectionQueryApi scheduleSelectionQueryApi(DataSource source,
            ServiceQueryApiImpl serviceFacts) {
        return new com.petplatform.schedule.biz.apiimpl.ScheduleSelectionQueryApiImpl(source,serviceFacts);
    }

    @Bean
    ScheduleQueryApiImpl scheduleQueryApi(DataSource source, ServiceQueryApiImpl serviceFacts) {
        return new ScheduleQueryApiImpl(source, serviceFacts);
    }
}
