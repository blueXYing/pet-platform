package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.command.PaymentCenterApi;
import com.petplatform.payment.api.query.PaymentCenterFactsApi;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.center.PaymentCallbackRouter;
import com.petplatform.payment.biz.center.PaymentCenterBizPolicy;
import com.petplatform.payment.biz.center.PaymentCenterQueryCoordinator;
import com.petplatform.payment.biz.center.demo.DemoPaymentConfirmConsumer;
import com.petplatform.payment.biz.center.demo.DemoPaymentService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Contract 75 §8 switches: default off, hard dependencies fail startup, no live I/O. No MySQL. */
class PaymentCenterConfigurationTest {
    @TempDir Path dir;

    // ---- pure flag matrix ------------------------------------------------------------------

    @Test void flagMatrix() {
        assertThatCode(() -> PaymentCenterConfiguration.check(false, false, false, false, false, true, "SIT"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaymentCenterConfiguration.check(true, true, true, true, true, true, "SIT"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaymentCenterConfiguration.check(true, true, false, true, true, false, "PRODUCTION"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PaymentCenterConfiguration.check(false, true, false, true, true, true, "SIT"))
                .hasMessage(PaymentCenterConfiguration.REQUIRES_CENTER);
        assertThatThrownBy(() -> PaymentCenterConfiguration.check(false, false, true, true, true, true, "SIT"))
                .hasMessage(PaymentCenterConfiguration.REQUIRES_CENTER);
        assertThatThrownBy(() -> PaymentCenterConfiguration.check(true, false, false, false, true, true, "SIT"))
                .hasMessage(PaymentCenterConfiguration.REQUIRES_FOUNDATION);
        assertThatThrownBy(() -> PaymentCenterConfiguration.check(true, false, false, true, false, true, "SIT"))
                .hasMessage(PaymentCenterConfiguration.REQUIRES_FOUNDATION);
        assertThatThrownBy(() -> PaymentCenterConfiguration.check(true, false, true, true, true, false, "SIT"))
                .hasMessage(PaymentCenterConfiguration.DEMO_REQUIRES_OUTBOX);
        for (String environment : new String[] {"PRODUCTION", "", null, "sit", "PROD"})
            assertThatThrownBy(() -> PaymentCenterConfiguration.check(true, false, true, true, true, true, environment))
                    .as(String.valueOf(environment)).hasMessage(PaymentCenterConfiguration.DEMO_FORBIDDEN_IN_PRODUCTION);
    }

    // ---- context wiring --------------------------------------------------------------------

    private ApplicationContextRunner runner(PaymentMerchantBindings bindings) throws Exception {
        Path key = dir.resolve("param.key");
        Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
        var dispatch = new PaymentDispatchConfiguration.DispatchSettings(LakalaHttpClient.Environment.SIT, "app",
                "serial", "unused", "serial", "unused", key.toString(), "ORG", "subject", "127.0.0.1",
                "https://notify.invalid/pay", false);
        var lakala = new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "UTC", null, "1");
        DataSource source = mock(DataSource.class);
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentCenterConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(PaymentReceiptVerifier.class, () -> (h, b, e) -> null)
                .withBean(IntegrationEventPublisher.class, () -> event -> {})
                .withBean(PaymentMerchantBindings.class, () -> bindings)
                .withBean(PaymentIdentityApi.class, () -> mock(PaymentIdentityApi.class))
                .withBean(PaymentChannel.class, () -> mock(PaymentChannel.class))
                .withBean(PaymentNotificationService.class, () -> mock(PaymentNotificationService.class))
                .withBean(JdbcOutboxConsumeGuard.class, () -> mock(JdbcOutboxConsumeGuard.class))
                .withBean(PaymentDispatchConfiguration.DispatchSettings.class, () -> dispatch)
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class, () -> lakala);
    }

    private static PaymentMerchantBindings platform() {
        return new PaymentMerchantBindings() {
            @Override public Binding require(String merchantId, String storeId) { throw new AssertionError(); }
            @Override public Binding requirePlatform() { return new Binding("PLATFORM", "TERM", "wxApp"); }
        };
    }

    @Test void defaultsRegisterNothingButTheGuard() throws Exception {
        runner(platform()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaymentCenterApi.class);
            assertThat(context).doesNotHaveBean(PaymentCenterFactsApi.class);
            assertThat(context).doesNotHaveBean(PaymentCallbackRouter.class);
            assertThat(context).doesNotHaveBean(DemoPaymentConfirmConsumer.class);
        });
    }

    @Test void centerWithoutFoundationAndDispatchFailsStartup() throws Exception {
        runner(platform()).withPropertyValues("pet.payment.center.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(PaymentCenterConfiguration.REQUIRES_FOUNDATION));
    }

    @Test void workerOrDemoWithoutCenterFailsStartup() throws Exception {
        runner(platform()).withPropertyValues("pet.payment.center.worker.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(PaymentCenterConfiguration.REQUIRES_CENTER));
        runner(platform()).withPropertyValues("pet.payment.center.demo.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(PaymentCenterConfiguration.REQUIRES_CENTER));
    }

    @Test void centerRequiresThePlatformMerchant() throws Exception {
        PaymentMerchantBindings storesOnly = (merchantId, storeId) -> new PaymentMerchantBindings.Binding("M", "T", "A");
        runner(storesOnly).withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                "pet.payment.dispatch.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining("requires the platform merchant"));
    }

    @Test void centerWiresInternalBeansOnlyAndNoDemoByDefault() throws Exception {
        runner(platform()).withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                "pet.payment.dispatch.enabled=true", "pet.payment.dispatch.environment=SIT").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentCenterApi.class);
            assertThat(context).hasSingleBean(PaymentCenterFactsApi.class);
            assertThat(context).hasSingleBean(PaymentCallbackRouter.class);
            assertThat(context).doesNotHaveBean(PaymentCenterQueryCoordinator.class);
            assertThat(context).doesNotHaveBean(DemoPaymentService.class);
            assertThat(context).doesNotHaveBean(DemoPaymentConfirmConsumer.class);
            assertThat(context.getBean(PaymentCenterBizPolicy.class).registered("DEMO")).isFalse();
            assertThat(context.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class)).isEmpty();
        });
    }

    @Test void demoInSitRegistersTheDemoOwnerWithTheConfiguredCeiling() throws Exception {
        runner(platform()).withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                "pet.payment.dispatch.enabled=true", "pet.payment.dispatch.environment=SIT",
                "pet.payment.center.demo.enabled=true", "pet.payment.center.demo.max-amount=0.05").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DemoPaymentService.class);
            assertThat(context).hasSingleBean(DemoPaymentConfirmConsumer.class);
            assertThat(context.getBean(PaymentCenterBizPolicy.class).registered("DEMO")).isTrue();
        });
    }

    @Test void demoInProductionOrWithABadCeilingFailsStartup() throws Exception {
        runner(platform()).withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                "pet.payment.dispatch.enabled=true", "pet.payment.dispatch.environment=PRODUCTION",
                "pet.payment.center.demo.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(PaymentCenterConfiguration.DEMO_FORBIDDEN_IN_PRODUCTION));
        runner(platform()).withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                "pet.payment.dispatch.enabled=true", "pet.payment.dispatch.environment=SIT",
                "pet.payment.center.demo.enabled=true", "pet.payment.center.demo.max-amount=0.001").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining("max-amount"));
    }

    @Test void missingParameterKeyFailsStartup() throws Exception {
        Files.delete(Files.writeString(dir.resolve("gone.key"), "x"));
        var dispatch = new PaymentDispatchConfiguration.DispatchSettings(LakalaHttpClient.Environment.SIT, "app",
                "serial", "unused", "serial", "unused", dir.resolve("gone.key").toString(), "ORG", "subject",
                "127.0.0.1", "https://notify.invalid/pay", false);
        runner(platform()).withBean("override", PaymentDispatchConfiguration.DispatchSettings.class, () -> dispatch,
                        bd -> bd.setPrimary(true))
                .withPropertyValues("pet.payment.center.enabled=true", "pet.payment.foundation.enabled=true",
                        "pet.payment.dispatch.enabled=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining("Payment center channel settings unavailable"));
    }
}
