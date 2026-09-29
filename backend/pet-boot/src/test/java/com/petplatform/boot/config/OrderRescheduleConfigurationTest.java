package com.petplatform.boot.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.petplatform.order.api.command.OrderRescheduleApi;
import com.petplatform.verification.api.command.VerificationRescheduleFenceApi;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class OrderRescheduleConfigurationTest {
 private ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(OrderRescheduleConfiguration.class);}
 @Test void disabledByDefaultDoesNotTouchDatabaseOrRegisterCommand(){
  DataSource source=mock(DataSource.class);runner().withBean(DataSource.class,()->source).run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(OrderRescheduleApi.class);verifyNoInteractions(source);});
 }
 @Test void httpCannotBeEnabledByConfiguration(){
  runner().withPropertyValues("pet.order.reschedule.http.enabled=true").run(c->assertThat(c).hasFailed());
 }
 @Test void explicitEnablementWithoutOwnerDependenciesFailsClosed(){
  runner().withPropertyValues("pet.order.reschedule.enabled=true").run(c->assertThat(c).hasFailed());
 }
 @Test void missingVerificationProviderCannotComposeEvenWithAllOtherOwners(){
  enabled().run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasStackTraceContaining("VerificationRescheduleFenceApi");});
 }
 @Test void explicitQaVerificationProviderComposesInternalCommandWithoutWorkerOrRoute(){
  enabled().withBean(VerificationRescheduleFenceApi.class,()->mock(VerificationRescheduleFenceApi.class))
   .run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(OrderRescheduleApi.class);assertThat(c).doesNotHaveBean(com.petplatform.task.core.AsyncTaskWorker.class);});
 }
 private ApplicationContextRunner enabled(){return runner().withPropertyValues("pet.order.reschedule.enabled=true","pet.schedule.protection.enabled=true",
   "pet.payment.foundation.enabled=true","pet.order.auto-confirm.enabled=true","pet.order.merchant.enabled=true")
  .withBean(DataSource.class,()->mock(DataSource.class))
  .withBean(com.petplatform.common.SnowflakeIdGenerator.class,()->()->1L)
  .withBean(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class,()->mock(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class))
  .withBean(com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi.class,()->mock(com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi.class))
  .withBean(com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl.class,()->mock(com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl.class))
  .withBean(com.petplatform.order.api.query.OrderProtectionFactsApi.class,()->mock(com.petplatform.order.api.query.OrderProtectionFactsApi.class))
  .withBean(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class,()->mock(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class))
  .withBean(com.petplatform.refund.api.query.RefundOrderFactsApi.class,()->mock(com.petplatform.refund.api.query.RefundOrderFactsApi.class))
  .withBean(com.petplatform.schedule.api.command.ReservationConfirmApi.class,()->mock(com.petplatform.schedule.api.command.ReservationConfirmApi.class))
  .withBean(com.petplatform.event.api.IntegrationEventPublisher.class,()->mock(com.petplatform.event.api.IntegrationEventPublisher.class))
  .withBean(com.petplatform.order.biz.application.MerchantOrderPorts.Protection.class,()->mock(com.petplatform.order.biz.application.MerchantOrderPorts.Protection.class))
  .withBean(com.petplatform.order.biz.application.MerchantOrderPorts.SessionAuthority.class,()->mock(com.petplatform.order.biz.application.MerchantOrderPorts.SessionAuthority.class))
  .withBean(com.petplatform.merchant.api.query.MerchantOrderAuthorityApi.class,()->mock(com.petplatform.merchant.api.query.MerchantOrderAuthorityApi.class));
 }
}
