package com.petplatform.boot.config;

import com.petplatform.common.*;
import com.petplatform.merchant.api.query.BookingMerchantFactsApi;
import com.petplatform.merchant.biz.apiimpl.BookingMerchantFactsApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.ProtectedValuePort;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.command.OrderCreationApi;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.order.biz.application.OrderCreationInputProtection;
import com.petplatform.order.biz.application.OrderCreationRemarkPolicy;
import com.petplatform.schedule.api.command.ReservationHoldApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.service.api.query.BookingServiceFactsApi;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.api.query.BookingUserFactsApi;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal creation only. No HTTP route, production migration, payment or timeout worker. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pet.order.creation",name="enabled",havingValue="true")
public class BookingCreationConfiguration {
    @Bean BookingUserFactsApi bookingUserFactsApi(DataSource source,ScheduleCapacityGuardApi guard){
        return new BookingUserFactsApiImpl(source,guard);
    }
    @Bean BookingMerchantFactsApi bookingMerchantFactsApi(DataSource source,ScheduleCapacityGuardApi guard,
            SnowflakeIdGenerator ids,ObjectProvider<ApplicationReviewFactsReader> applications){
        return new BookingMerchantFactsApiImpl(source,guard,applications.getIfAvailable(
                ()->new PersistentApplicationReviewFactsReader(source,ids)));
    }
    @Bean BookingServiceFactsApi bookingServiceFactsApi(DataSource source,ScheduleCapacityGuardApi guard){
        return new BookingServiceFactsApiImpl(source,guard);
    }
    @Bean
    @ConditionalOnMissingBean(OrderCreationInputProtection.class)
    OrderCreationInputProtection orderCreationInputProtection(ObjectProvider<ProtectedValuePort> providers){
        ProtectedValuePort provider=providers.getIfAvailable();
        return (purpose,value)->{
            if(provider==null)throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking input protection unavailable");
            var protectedValue=provider.protect(purpose,value);
            if(protectedValue==null)throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking input protection unavailable");
            return new OrderCreationInputProtection.ProtectedInput(protectedValue.ciphertext(),protectedValue.equalityToken());
        };
    }
    @Bean
    @ConditionalOnMissingBean(OrderCreationRemarkPolicy.class)
    OrderCreationRemarkPolicy orderCreationRemarkPolicy(){
        return remark->{throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"booking remark review unavailable");};
    }
    @Bean ReservationHoldApi reservationHoldApi(DataSource source,SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard,ScheduleProtectionFactsApi facts,ScheduleCapacityProofApiImpl proof,
            OrderProtectionFactsApi orders,ObjectProvider<Clock> clocks){
        return new ReservationHoldApiImpl(source,ids,guard,facts,proof,orders,clocks.getIfAvailable(Clock::systemUTC));
    }
    @Bean OrderCreationApi orderCreationApi(DataSource source,SnowflakeIdGenerator ids,
            BookingUserFactsApi users,BookingMerchantFactsApi merchants,BookingServiceFactsApi services,
            ScheduleCapacityGuardApi guard,ReservationHoldApi hold,OrderCreationInputProtection protection,
            OrderCreationRemarkPolicy remarkPolicy,ObjectProvider<Clock> clocks){
        return new OrderCreationApiImpl(source,ids,users,merchants,services,guard,hold,protection,
                remarkPolicy,clocks.getIfAvailable(Clock::systemUTC));
    }
}
