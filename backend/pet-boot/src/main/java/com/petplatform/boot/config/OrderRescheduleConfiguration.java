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

/** No HTTP or fake verification provider. Explicit enablement still requires every owner dependency. */
@Configuration(proxyBeanMethods=false)
public class OrderRescheduleConfiguration {
 @Bean Object rescheduleFlags(Environment e){
  if(e.getProperty("pet.order.reschedule.http.enabled",Boolean.class,false))throw new IllegalStateException("Reschedule HTTP is not implemented");
  if(e.getProperty("pet.order.reschedule.enabled",Boolean.class,false)){
   for(String dependency:new String[]{"pet.schedule.protection.enabled","pet.payment.foundation.enabled","pet.order.auto-confirm.enabled","pet.order.merchant.enabled"})
    if(!e.getProperty(dependency,Boolean.class,false))throw new IllegalStateException("Reschedule requires "+dependency);
  }
  return new Object();
 }
 @Configuration(proxyBeanMethods=false)
 @ConditionalOnProperty(name="pet.order.reschedule.enabled",havingValue="true")
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
