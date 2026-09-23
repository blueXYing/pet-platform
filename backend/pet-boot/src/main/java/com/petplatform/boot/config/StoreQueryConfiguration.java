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
 * exist. Default off in production until the deployment gate opens it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.store.query", name = "enabled", havingValue = "true")
public class StoreQueryConfiguration {

    @Bean
    MerchantStoreDisplayApiImpl merchantStoreDisplayApi(
            DataSource source, ApplicationReviewFactsReader applications) {
        return new MerchantStoreDisplayApiImpl(
                source, new MerchantAgreementEligibilityFactsAdapter(source, applications));
    }
}
