package com.petplatform.boot.config;

import com.petplatform.merchant.biz.apiimpl.MerchantStoreDisplayApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.MerchantAgreementEligibilityFactsAdapter;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end store catalog (CCR-W2-API-001 store read, STR-D6): assembled only with the merchant
 * application module (its review-facts reader feeds the shared eligibility policy) and the
 * approved SVC-D5/D6 fifth-query machinery; enabling requires every real dependency bean to
 * exist. Default ON per the 2026-10-16 ruling (doc 35); the routes additionally ride the C session domain's security chain at deployment.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.store.query", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StoreQueryConfiguration {

    @Bean
    MerchantStoreDisplayApiImpl merchantStoreDisplayApi(
            DataSource source, ApplicationReviewFactsReader applications) {
        return new MerchantStoreDisplayApiImpl(
                source, new MerchantAgreementEligibilityFactsAdapter(source, applications));
    }
}
