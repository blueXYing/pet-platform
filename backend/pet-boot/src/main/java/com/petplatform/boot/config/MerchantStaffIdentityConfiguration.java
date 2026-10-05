package com.petplatform.boot.config;

import com.petplatform.merchant.biz.apiimpl.MerchantStaffIdentityApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.Clock;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * STAFF login-identity read kernel (contract 52). Explicitly off: the member binding command,
 * grant writers, action catalog and verification mapping are still pending the approved CCR.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.staff-identity", name = "enabled", havingValue = "true")
public class MerchantStaffIdentityConfiguration {
    @Bean
    MerchantStaffIdentityApiImpl merchantStaffIdentityApi(DataSource source,
            ScheduleCapacityGuardApi guard, ObjectProvider<ApplicationReviewFactsReader> applications,
            ObjectProvider<Clock> clock) {
        return new MerchantStaffIdentityApiImpl(source, guard,
                applications.getIfAvailable(() -> MerchantStaffIdentityApiImpl.unavailableApplicationFacts()),
                clock.getIfAvailable(() -> Clock.systemUTC()));
    }
}
