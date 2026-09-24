package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicitly off until SQL06, SQL28, SQL29 and SQL35 are installed in the same merchant DB. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.staff", name = "enabled", havingValue = "true")
public class MerchantStaffConfiguration {
    @Bean
    MerchantStaffApiImpl merchantStaffApi(DataSource source, SnowflakeIdGenerator ids,
            ObjectProvider<ApplicationReviewFactsReader> applications,
            ObjectProvider<ApplicationValidationPorts.ProtectedValuePort> protection, ObjectProvider<Clock> clock) {
        ApplicationReviewFactsReader noApplicationFacts = merchantId -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant application review facts are not configured");
        };
        ApplicationValidationPorts.ProtectedValuePort unavailable = (purpose, value) -> {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "merchant staff canonical protection is not configured");
        };
        return new MerchantStaffApiImpl(source, ids,
                applications.getIfAvailable(() -> noApplicationFacts),
                protection.getIfAvailable(() -> unavailable),
                clock.getIfAvailable(Clock::systemUTC));
    }
}
