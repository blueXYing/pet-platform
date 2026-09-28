package com.petplatform.boot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.petplatform.boot.config.PaymentDispatchConfiguration;
import com.petplatform.boot.config.PaymentFoundationConfiguration;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.api.command.PaymentPreparationApi;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentDispatchConfigurationTest {
    @TempDir Path temporary;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PaymentDispatchConfiguration.class);
    }

    private ApplicationContextRunner dependencies() {
        return runner().withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(com.petplatform.common.SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(ScheduleCapacityGuardApi.class, () -> mock(ScheduleCapacityGuardApi.class))
                .withBean(OrderPaymentFactsApi.class, () -> mock(OrderPaymentFactsApi.class))
                .withBean(PaymentPreparationApi.class, () -> mock(PaymentPreparationApi.class))
                .withBean(PaymentNotificationService.class, () -> mock(PaymentNotificationService.class))
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class,
                        () -> new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "Asia/Shanghai"));
    }

    @Test void defaultAndSingleFlagNeverRegisterChannelOrDispatch() {
        runner().run(context -> assertThat(context).doesNotHaveBean(PaymentDispatchService.class));
        runner().withPropertyValues("pet.payment.dispatch.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentDispatchService.class));
        runner().withPropertyValues("pet.payment.foundation.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentChannel.class));
    }

    @Test void enabledWithoutCredentialsFailsBeforeAnyChannelCall() {
        dependencies().withPropertyValues("pet.payment.foundation.enabled=true", "pet.payment.dispatch.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void explicitlyConfiguredInternalServiceRemainsDormantAtStartup() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Path keyFile = temporary.resolve("temporary-test-key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
        PaymentChannel channel = mock(PaymentChannel.class);
        dependencies().withBean(PaymentChannel.class, () -> channel)
                .withPropertyValues("pet.payment.foundation.enabled=true", "pet.payment.dispatch.enabled=true",
                        "pet.payment.dispatch.parameter-key-path=" + keyFile,
                        "pet.payment.dispatch.out-org-code=TEST_ORG",
                        "pet.payment.dispatch.subject=pet service",
                        "pet.payment.dispatch.request-ip=127.0.0.1",
                        "pet.payment.dispatch.notify-url=https://example.test/notify")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentDispatchService.class);
                    org.mockito.Mockito.verifyNoInteractions(channel);
                });
    }
}
