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

    @Test void paymentEnabledComposesInspectorCommandRepairAndWorker() {
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
                .withPropertyValues("pet.payment.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderAutoConfirmTaskInspectionApi.class);
                    assertThat(context).hasSingleBean(com.petplatform.order.api.command.OrderAutoConfirmApi.class);
                    assertThat(context).hasSingleBean(com.petplatform.order.api.command.OrderAutoConfirmRepairApi.class);
                    assertThat(context).hasSingleBean(AsyncTaskWorker.class);
                });
    }
}
