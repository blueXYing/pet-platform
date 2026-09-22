package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantDisplayEligibilityApi;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end service catalog (CCR-W2-API-001 service domain): assembled only with the C session slice
 * and the SVC-D5 display eligibility query; enabling requires every real dependency bean to exist.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.service.query", name = "enabled", havingValue = "true")
public class ServiceQueryConfiguration {

    @Bean
    ServiceQueryApiImpl serviceQueryApi(DataSource source, MerchantDisplayEligibilityApi merchantFacts) {
        return new ServiceQueryApiImpl(source, merchantFacts);
    }
}
