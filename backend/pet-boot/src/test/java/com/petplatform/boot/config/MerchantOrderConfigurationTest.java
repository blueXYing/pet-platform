package com.petplatform.boot.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.biz.application.*;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.schedule.api.command.*;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.user.biz.application.UserAuthService;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class MerchantOrderConfigurationTest {
    private ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(MerchantOrderConfiguration.class,LateRefundConfiguration.class,OrderAutoConfirmTaskConfiguration.class);}
    @Test void defaultsCreateNoMerchantCommandWorkerOrChannel(){runner().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(MerchantOrderService.class);assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);assertThat(c).doesNotHaveBean(LateRefundService.class);});}
    @Test void partialOrOrphanFlagsFailClosed(){
        for(String flag:new String[]{"pet.order.merchant.http.enabled=true","pet.order.merchant.worker.enabled=true","pet.order.merchant.enabled=true"})
            runner().withPropertyValues(flag).run(c->assertThat(c).hasFailed());
    }
    @Test void missingModerationAndProtectionAreNotSilentlyReplaced(){
        dependencies().run(c->assertThat(c).hasFailed());
        dependencies().withBean(MerchantOrderPorts.Protection.class,()->new MerchantOrderAesProtection(new byte[32])).run(c->assertThat(c).hasFailed());
    }
    @Test void completeOfflineCompositionDoesNotEnableLateConsumerOrEitherWorker(){
        dependencies().withBean(MerchantOrderPorts.Protection.class,()->new MerchantOrderAesProtection(new byte[32]))
            .withBean(MerchantOrderPorts.Moderation.class,()->mock(MerchantOrderPorts.Moderation.class)).run(c->{
                assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(MerchantOrderService.class);assertThat(c).hasSingleBean(RefundOrderFactsApi.class);
                assertThat(c).doesNotHaveBean(AsyncTaskWorker.class);assertThat(c.getBean(LateRefundService.class).eventTypes()).isEmpty();
                verifyNoInteractions(c.getBean(PaymentRefundChannel.class));
            });
    }
    private ApplicationContextRunner dependencies(){return runner().withPropertyValues("pet.order.merchant.enabled=true","pet.payment.foundation.enabled=true","pet.payment.dispatch.request-ip=127.0.0.1")
        .withBean(DataSource.class,()->mock(DataSource.class)).withBean(SnowflakeIdGenerator.class,()->()->1L)
        .withBean(ScheduleCapacityGuardApi.class,()->mock(ScheduleCapacityGuardApi.class))
        .withBean(ReservationExpiryApi.class,()->mock(ReservationExpiryApi.class)).withBean(ReservationConfirmApi.class,()->mock(ReservationConfirmApi.class))
        .withBean(PaymentSuccessFactsApi.class,()->mock(PaymentSuccessFactsApi.class)).withBean(IntegrationEventPublisher.class,()->mock(IntegrationEventPublisher.class))
        .withBean(PaymentRefundChannel.class,()->mock(PaymentRefundChannel.class)).withBean(UserAuthService.class,()->mock(UserAuthService.class))
        .withBean(PaymentFoundationConfiguration.LakalaSettings.class,()->new PaymentFoundationConfiguration.LakalaSettings(Map.of(),null,"Asia/Shanghai"));}
}
