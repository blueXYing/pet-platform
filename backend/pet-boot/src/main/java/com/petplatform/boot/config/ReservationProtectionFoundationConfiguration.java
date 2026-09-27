package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityProofApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal foundation only: does not install schema or expose booking/management routes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.schedule.protection", name = "enabled", havingValue = "true")
public class ReservationProtectionFoundationConfiguration {
    @Bean
    ScheduleCapacityGuardApi scheduleCapacityGuardApi(DataSource source) {
        return new ScheduleCapacityGuardApiImpl(source);
    }

    @Bean
    ScheduleProtectionFactsApi scheduleProtectionFactsApi(DataSource source, ScheduleCapacityGuardApi guard) {
        return new ScheduleProtectionFactsApiImpl(source, guard);
    }

    @Bean
    MerchantCurrentStaffFactsApi merchantCurrentStaffFactsApi(DataSource source, ScheduleCapacityGuardApi guard) {
        return new MerchantCurrentStaffFactsApiImpl(source, guard);
    }

    @Bean
    OrderProtectionFactsApi orderProtectionFactsApi(DataSource source, ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi schedule, MerchantCurrentStaffFactsApi staff, ObjectProvider<Clock> clocks) {
        return new OrderProtectionFactsApiImpl(source, guard, schedule, staff, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ScheduleCapacityProofApi scheduleCapacityProofApi(DataSource source, ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi schedule, MerchantCurrentStaffFactsApi staff,
            OrderProtectionFactsApi orders, ObjectProvider<Clock> clocks,
            @Value("${pet.schedule.protection.proof-budget-millis:250}") long budgetMillis) {
        return new ScheduleCapacityProofApiImpl(source, guard, schedule, staff, orders,
                clocks.getIfAvailable(Clock::systemUTC), budgetMillis);
    }
}
