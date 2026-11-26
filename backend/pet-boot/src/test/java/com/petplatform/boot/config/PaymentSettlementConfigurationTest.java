package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.command.SettlementAdminApi;
import com.petplatform.payment.api.command.SettlementTriggerApi;
import com.petplatform.payment.api.query.SettlementQueryApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.SettlementRefundGate;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.settlement.SettlementChannel;
import com.petplatform.payment.biz.settlement.SettlementEventConsumers;
import com.petplatform.payment.biz.settlement.SettlementExecutionService;
import com.petplatform.payment.biz.settlement.SettlementFallbackService;
import com.petplatform.payment.biz.settlement.SettlementQueryCoordinator;
import com.petplatform.payment.biz.settlement.SettlementReceiptWriter;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RestController;

/** Offline Contract 78 §7 configuration matrix tests. No MySQL required. */
class PaymentSettlementConfigurationTest {

    @Test
    void lowestRatioValidationMatrix() {
        assertThatCode(() -> PaymentSettlementConfiguration.check(
                false, new PaymentSettlementConfiguration.SettlementSettings("invalid", null, null, false, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaymentSettlementConfiguration.check(
                true, new PaymentSettlementConfiguration.SettlementSettings("0.05", null, null, false, null)))
                .doesNotThrowAnyException();
        for (String bad : new String[] {"-0.01", "1", "1.0000", "0.12345", "abc"}) {
            assertThatThrownBy(() -> PaymentSettlementConfiguration.check(
                    true, new PaymentSettlementConfiguration.SettlementSettings(bad, null, null, false, null)))
                    .as(bad).hasMessage(PaymentSettlementConfiguration.INVALID_LOWEST_RATIO);
        }
    }

    private ApplicationContextRunner runner(PaymentMerchantBindings bindings) {
        var dispatch = new PaymentDispatchConfiguration.DispatchSettings(
                LakalaHttpClient.Environment.SIT, "app", "serial", "unused", "serial", "unused",
                "unused", "ORG", "subject", "127.0.0.1", "https://notify.invalid/pay", false);
        var lakala = new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "Asia/Shanghai", null, "1");
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentSettlementConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(IntegrationEventPublisher.class, () -> event -> {})
                .withBean(JdbcOutboxConsumeGuard.class, () -> mock(JdbcOutboxConsumeGuard.class))
                .withBean(PaymentMerchantBindings.class, () -> bindings)
                .withBean(SettlementChannel.class, () -> mock(SettlementChannel.class))
                .withBean(PaymentDispatchConfiguration.DispatchSettings.class, () -> dispatch)
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class, () -> lakala);
    }

    private static PaymentMerchantBindings platform() {
        return new PaymentMerchantBindings() {
            @Override public Binding require(String merchantId, String storeId) { throw new AssertionError(); }
            @Override public Binding requirePlatform() { return new Binding("PLATFORM_MCH", "TERM", "wxApp"); }
        };
    }

    @Test
    void defaultsRegisterNoSettlementRuntimeBeans() {
        runner(platform()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SettlementAdminApi.class);
            assertThat(ctx).doesNotHaveBean(SettlementExecutionService.class);
            assertThat(ctx).doesNotHaveBean(SettlementFallbackService.class);
            assertThat(ctx).doesNotHaveBean(SettlementRefundGate.class);
            assertThat(ctx).doesNotHaveBean(SettlementReceiptWriter.class);
            assertThat(ctx).doesNotHaveBean(SettlementQueryCoordinator.class);
        });
    }

    @Test
    void invalidLowestRatioFailsStartupWhenPaymentEnabled() {
        runner(platform()).withPropertyValues(
                "pet.payment.enabled=true",
                "pet.payment.settlement.lowest-ratio=1.5").run(ctx ->
                assertThat(ctx).getFailure().hasStackTraceContaining(PaymentSettlementConfiguration.INVALID_LOWEST_RATIO));
    }

    @Test
    void enabledWithPaymentWiresServicesWorkerAndConsumersWithoutControllers() {
        runner(platform()).withPropertyValues(
                "pet.payment.enabled=true",
                "pet.payment.settlement.lowest-ratio=0.02").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SettlementAdminApi.class);
            assertThat(ctx).hasSingleBean(SettlementTriggerApi.class);
            assertThat(ctx).hasSingleBean(SettlementQueryApi.class);
            assertThat(ctx).hasSingleBean(SettlementExecutionService.class);
            assertThat(ctx).hasSingleBean(SettlementFallbackService.class);
            assertThat(ctx).hasSingleBean(SettlementRefundGate.class);
            assertThat(ctx).hasSingleBean(SettlementReceiptWriter.class);
            assertThat(ctx).hasSingleBean(SettlementQueryCoordinator.class);
            assertThat(ctx).hasSingleBean(SettlementEventConsumers.OnPaymentSucceeded.class);
            assertThat(ctx).hasSingleBean(SettlementEventConsumers.OnOrderVerified.class);
            assertThat(ctx).hasSingleBean(SettlementEventConsumers.OnRefundCreated.class);
            assertThat(ctx).hasSingleBean(SettlementEventConsumers.OnRefundSucceeded.class);
            assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
        });
    }
}
