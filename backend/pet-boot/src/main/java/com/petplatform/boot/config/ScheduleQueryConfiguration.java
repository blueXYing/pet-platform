package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.merchant.biz.apiimpl.MerchantStoreStaffFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.schedule.biz.infrastructure.provider.ScheduleQualifiedStaffFactsProvider;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end schedule availability (CCR-W2-API-001 schedule domain, SCH-D6 ruling 2026-09-23):
 * assembled with MER staff IDs and SCH capability/availability facts when the opt-in query is
 * enabled. Switch pet.schedule.query.enabled defaults to off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.schedule.query", name = "enabled", havingValue = "true")
public class ScheduleQueryConfiguration {

    @Bean
    @ConditionalOnMissingBean(MerchantStoreStaffFactsApi.class)
    MerchantStoreStaffFactsApi merchantStoreStaffFactsApi(DataSource source) {
        return new MerchantStoreStaffFactsApiImpl(source);
    }

    @Bean
    @ConditionalOnMissingBean(QualifiedStaffFactsPort.class)
    QualifiedStaffFactsPort qualifiedStaffFacts(
            DataSource source, MerchantStoreStaffFactsApi merchant) {
        return new ScheduleQualifiedStaffFactsProvider(merchant, source);
    }

    @Bean
    ScheduleQueryApiImpl scheduleQueryApi(
            DataSource source,
            ServiceQueryApiImpl serviceFacts,
            ObjectProvider<QualifiedStaffFactsPort> staffFacts) {
        return new ScheduleQueryApiImpl(source, serviceFacts, staffFacts.getIfAvailable());
    }
}
