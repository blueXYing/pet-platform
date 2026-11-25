package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.ReconcileAdminApi;
import com.petplatform.payment.api.query.SubsidyReportQueryApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReconcileChannel;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RestController;

/** Offline Contract 80 Stage 6 configuration matrix tests. No MySQL required. */
class PaymentReconcileConfigurationTest {

    @Test
    void flagAndScheduleValidationMatrix() {
        assertThatCode(() -> PaymentReconcileConfiguration.check(
                false, new PaymentReconcileConfiguration.ReconcileSettings(false, false, null, 0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaymentReconcileConfiguration.check(
                true, new PaymentReconcileConfiguration.ReconcileSettings(true, true, "03:30", 5)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PaymentReconcileConfiguration.check(
                false, new PaymentReconcileConfiguration.ReconcileSettings(true, false, null, 5)))
                .hasMessage(PaymentReconcileConfiguration.REQUIRES_SETTLEMENT);
        assertThatThrownBy(() -> PaymentReconcileConfiguration.check(
                true, new PaymentReconcileConfiguration.ReconcileSettings(true, true, "25:99", 5)))
                .hasMessage(PaymentReconcileConfiguration.INVALID_DAILY_RUN_TIME);
        assertThatThrownBy(() -> PaymentReconcileConfiguration.check(
                true, new PaymentReconcileConfiguration.ReconcileSettings(true, false, "03:00", -1)))
                .hasMessage(PaymentReconcileConfiguration.INVALID_SCAN_INTERVAL);
        assertThatThrownBy(() -> PaymentReconcileConfiguration.check(
                true, new PaymentReconcileConfiguration.ReconcileSettings(true, false, "03:00", 1441)))
                .hasMessage(PaymentReconcileConfiguration.INVALID_SCAN_INTERVAL);
    }

    private ApplicationContextRunner runner() {
        var dispatch = new PaymentDispatchConfiguration.DispatchSettings(
                LakalaHttpClient.Environment.SIT, "app", "serial", "unused", "serial", "unused",
                "unused", "ORG", "subject", "127.0.0.1", "https://notify.invalid/pay", false);
        var lakala = new PaymentFoundationConfiguration.LakalaSettings(Map.of(), null, "Asia/Shanghai", null, "1");
        PaymentMerchantBindings bindings = new PaymentMerchantBindings() {
            @Override public Binding require(String merchantId, String storeId) { throw new AssertionError(); }
            @Override public Binding requirePlatform() { return new Binding("PLATFORM_MCH", "TERM", "wxApp"); }
        };
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentReconcileConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(PaymentMerchantBindings.class, () -> bindings)
                .withBean(SettlementReconcileChannel.class, () -> mock(SettlementReconcileChannel.class))
                .withBean(PaymentDispatchConfiguration.DispatchSettings.class, () -> dispatch)
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class, () -> lakala);
    }

    @Test
    void defaultsRegisterNoReconcileRuntimeBeans() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SubsidyReportQueryApi.class);
            assertThat(ctx).doesNotHaveBean(ReconcileAdminApi.class);
            assertThat(ctx).doesNotHaveBean(SettlementCompensationScanner.class);
            assertThat(ctx).doesNotHaveBean(PaymentReconcileConfiguration.DailyReconcileScheduler.class);
        });
    }

    @Test
    void enabledWithoutSettlementFailsStartup() {
        runner().withPropertyValues("pet.payment.reconcile.enabled=true").run(ctx ->
                assertThat(ctx).getFailure().hasStackTraceContaining(PaymentReconcileConfiguration.REQUIRES_SETTLEMENT));
    }

    @Test
    void enabledWithSettlementWiresStage6BeansAndOptionalSchedulerWithoutControllers() {
        runner().withPropertyValues(
                "pet.payment.settlement.enabled=true",
                "pet.payment.reconcile.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SubsidyReportQueryApi.class);
            assertThat(ctx).hasSingleBean(ReconcileAdminApi.class);
            assertThat(ctx).hasSingleBean(SettlementCompensationScanner.class);
            assertThat(ctx).doesNotHaveBean(PaymentReconcileConfiguration.DailyReconcileScheduler.class);
            assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
        });

        runner().withPropertyValues(
                "pet.payment.settlement.enabled=true",
                "pet.payment.reconcile.enabled=true",
                "pet.payment.reconcile.daily-schedule-enabled=true",
                "pet.payment.reconcile.daily-run-time=03:15",
                "pet.payment.reconcile.scan-interval-minutes=10").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SubsidyReportQueryApi.class);
            assertThat(ctx).hasSingleBean(ReconcileAdminApi.class);
            assertThat(ctx).hasSingleBean(SettlementCompensationScanner.class);
            assertThat(ctx).hasSingleBean(PaymentReconcileConfiguration.DailyReconcileScheduler.class);
            assertThat(ctx.getBeanNamesForAnnotation(RestController.class)).isEmpty();
        });
    }
}
