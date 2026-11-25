package com.petplatform.boot.config;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.application.*;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.verification.api.command.VerificationRescheduleFenceApi;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Real verification provider required; the C HTTP face (10号 §3.8 slice) rides the kernel switches. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pet.payment",name="enabled",havingValue="true")
public class OrderRescheduleConfiguration {
 @Configuration(proxyBeanMethods=false)
 @ConditionalOnProperty(prefix="pet.payment",name="enabled",havingValue="true")
 static class Runtime {
  @Bean ReservationSwapApi reservationSwap(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
    ScheduleProtectionFactsApi facts,ScheduleCapacityProofApiImpl proof,OrderProtectionFactsApi orders){
   return new ReservationSwapApiImpl(s,ids,guard,facts,proof,orders,new com.petplatform.order.biz.apiimpl.OrderRescheduleCommitApiImpl(s,guard));
  }
  @Bean OrderRescheduleService orderRescheduleService(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
   PaymentSuccessFactsApi paid,RefundOrderFactsApi refunds,ReservationConfirmApi confirmed,ReservationSwapApi swap,
   VerificationRescheduleFenceApi verification,IntegrationEventPublisher outbox,MerchantOrderPorts.Protection protection,MerchantOrderPorts.SessionAuthority sessions,com.petplatform.merchant.api.query.MerchantOrderAuthorityApi merchant){
   return new OrderRescheduleService(s,ids,guard,paid,refunds,confirmed,swap,verification,outbox,protection,sessions,merchant);
  }
 }
}
