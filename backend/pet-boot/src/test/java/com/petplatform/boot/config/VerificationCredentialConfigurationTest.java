package com.petplatform.boot.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.petplatform.verification.biz.application.*;
import com.petplatform.verification.api.command.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import java.util.Map;
class VerificationCredentialConfigurationTest {
 private ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(VerificationCredentialConfiguration.class);}
 @Test void defaultOffDoesNotTouchDatabase(){var s=mock(DataSource.class);base().withBean(DataSource.class,()->s).run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(VerificationCredentialApi.class);assertThat(c).doesNotHaveBean(VerificationCompletionApi.class);verifyNoInteractions(s);});}
 @Test void httpIsNotImplemented(){base().withPropertyValues("pet.verification.credential.http.enabled=true").run(c->assertThat(c).hasFailed());}
 @Test void missingFoundationsFailClosed(){base().withPropertyValues("pet.verification.credential.enabled=true").run(c->assertThat(c).hasFailed());}
 @Test void realOwnerAuthorityComposesWithoutQaAdapter(){enabled().withBean(CredentialProtection.class,()->keys()).run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(CredentialPorts.AttemptAuthority.class);assertThat(c).hasSingleBean(VerificationCredentialApi.class);});}
 @Test void missingKeysFailClosed(){enabled().withBean(CredentialPorts.AttemptAuthority.class,()->mock(CredentialPorts.AttemptAuthority.class)).run(c->assertThat(c).hasFailed());}
 @Test void explicitQaDependenciesComposeRealCredentialAndFence(){enabled().withBean(CredentialProtection.class,()->keys()).withBean(CredentialPorts.AttemptAuthority.class,()->mock(CredentialPorts.AttemptAuthority.class)).run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(VerificationCredentialApi.class);assertThat(c).hasSingleBean(VerificationRescheduleFenceApi.class);});}
 @Test void completionRequiresCredentialFoundation(){base().withPropertyValues("pet.verification.completion.enabled=true").run(c->assertThat(c).hasFailed());}
 @Test void explicitCompletionComposesAllRealOwnersWithoutHttp(){enabled().withPropertyValues("pet.verification.completion.enabled=true").withBean(CredentialProtection.class,()->keys()).run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(VerificationCompletionApi.class);assertThat(c).hasSingleBean(com.petplatform.order.api.command.OrderVerificationCommitApi.class);assertThat(c).hasSingleBean(com.petplatform.aftersale.api.command.AfterSaleVerificationApi.class);});}
 private static CredentialProtection keys(){return new CredentialProtection("test",Map.of("test",new byte[32]),Map.of("test",new byte[32]));}
 private ApplicationContextRunner enabled(){return base().withPropertyValues("pet.verification.credential.enabled=true","pet.schedule.protection.enabled=true","pet.payment.foundation.enabled=true","pet.order.auto-confirm.enabled=true","pet.order.merchant.enabled=true")
  .withBean(DataSource.class,()->mock(DataSource.class)).withBean(com.petplatform.common.SnowflakeIdGenerator.class,()->()->1L)
  .withBean(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class,()->mock(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class))
  .withBean(com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi.class,()->mock(com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi.class))
  .withBean(com.petplatform.schedule.api.command.ReservationConfirmApi.class,()->mock(com.petplatform.schedule.api.command.ReservationConfirmApi.class))
  .withBean(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class,()->mock(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class))
  .withBean(com.petplatform.refund.api.query.RefundOrderFactsApi.class,()->mock(com.petplatform.refund.api.query.RefundOrderFactsApi.class))
  .withBean(com.petplatform.merchant.api.query.MerchantOrderAuthorityApi.class,()->mock(com.petplatform.merchant.api.query.MerchantOrderAuthorityApi.class))
  .withBean(com.petplatform.order.biz.application.MerchantOrderPorts.SessionAuthority.class,()->mock(com.petplatform.order.biz.application.MerchantOrderPorts.SessionAuthority.class))
  .withBean(com.petplatform.event.api.IntegrationEventPublisher.class,()->mock(com.petplatform.event.api.IntegrationEventPublisher.class));}
}
