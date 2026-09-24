package com.petplatform.boot.config;

import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end schedule availability (CCR-W2-API-001 schedule domain, SCH-D6 ruling 2026-09-23):
 * assembled only with the C session slice and the service-domain facts query. The
 * qualified-staff facts provider does NOT exist yet (SCH-002 owns the source), so the real
 * assembly passes none on purpose - every availability query for a visible service then fails
 * closed with COMMON_DEPENDENCY_UNAVAILABLE instead of degrading to a placeholder capacity.
 * Switch pet.schedule.query.enabled defaults to off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.schedule.query", name = "enabled", havingValue = "true")
public class ScheduleQueryConfiguration {

    @Bean
    ScheduleQueryApiImpl scheduleQueryApi(
            DataSource source,
            ServiceQueryApiImpl serviceFacts,
            ObjectProvider<QualifiedStaffFactsPort> staffFacts) {
        return new ScheduleQueryApiImpl(source, serviceFacts, staffFacts.getIfAvailable());
    }
}
