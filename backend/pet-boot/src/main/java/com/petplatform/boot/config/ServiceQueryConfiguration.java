package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end service catalog (CCR-W2-API-001 service domain): assembled only with the C session slice
 * and the SVC-D5 display eligibility query; enabling requires every real dependency bean to exist.
 * The cover presigner (2026-09-22 ruling #3) is injected when a real signer bean exists; without
 * one, visible rows that carry a cover fail closed (503) instead of returning an unsigned URL —
 * rows without a cover binding keep working.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class ServiceQueryConfiguration {

    @Bean
    ServiceQueryApiImpl serviceQueryApi(
            DataSource source,
            MerchantDisplayEligibilityApi merchantFacts,
            ObjectProvider<ServiceCoverUrlPort> coverUrls) {
        return new ServiceQueryApiImpl(source, merchantFacts, coverUrls.getIfAvailable());
    }
}
