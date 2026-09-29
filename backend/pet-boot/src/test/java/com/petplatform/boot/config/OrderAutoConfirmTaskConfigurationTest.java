package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.task.core.AsyncTaskWorker;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderAutoConfirmTaskConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(OrderAutoConfirmTaskConfiguration.class);
    }

    @Test void defaultIsOffAndCreatesNoWorkerOrInspector() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
            assertThat(context).doesNotHaveBean(OrderAutoConfirmTaskInspectionApi.class);
            assertThat(context).doesNotHaveBean(com.petplatform.order.api.command.OrderAutoConfirmApi.class);
            assertThat(context).doesNotHaveBean(com.petplatform.order.api.command.OrderAutoConfirmRepairApi.class);
        });
    }

    @Test void taskProductionRequiresPaymentFoundation() {
        runner().withPropertyValues("pet.order.auto-confirm.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("pet.order.auto-confirm.enabled=true", "pet.payment.foundation.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                });
    }

    @Test void executionAndRepairRequireExplicitProductionAndPaymentFlags() {
        for (String flag : new String[] {"worker", "repair"}) {
            for (boolean payment : new boolean[] {false, true}) {
                runner().withPropertyValues("pet.order.auto-confirm." + flag + ".enabled=true",
                                "pet.order.auto-confirm.enabled=false", "pet.payment.foundation.enabled=" + payment)
                        .run(context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                                    "Auto-confirm execution and repair require task production and payment foundation");
                        });
            }
        }
    }

    @Test void explicitRepairComposesCommandAndScannerWithoutStartingWorker() {
        DataSource source = mock(DataSource.class);
        runner().withBean(DataSource.class, () -> source)
                .withBean(com.petplatform.common.SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class,
                        () -> mock(com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi.class))
                .withBean(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class,
                        () -> mock(com.petplatform.payment.api.query.PaymentSuccessFactsApi.class))
                .withBean(com.petplatform.schedule.api.command.ReservationConfirmApi.class,
                        () -> mock(com.petplatform.schedule.api.command.ReservationConfirmApi.class))
                .withBean(com.petplatform.event.api.IntegrationEventPublisher.class,
                        () -> mock(com.petplatform.event.api.IntegrationEventPublisher.class))
                .withPropertyValues("pet.order.auto-confirm.enabled=true",
                        "pet.payment.foundation.enabled=true","pet.order.auto-confirm.repair.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.petplatform.order.api.command.OrderAutoConfirmApi.class);
                    assertThat(context).hasSingleBean(com.petplatform.order.api.command.OrderAutoConfirmRepairApi.class);
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                    verifyNoInteractions(source);
                });
    }

    @Test void inspectionIsAnIndependentOptInWithoutStartupDatabaseAccess() {
        DataSource source = mock(DataSource.class);
        runner().withBean(DataSource.class, () -> source)
                .withPropertyValues("pet.order.auto-confirm.inspection.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderAutoConfirmTaskInspectionApi.class);
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                    verifyNoInteractions(source);
                });
    }
}
