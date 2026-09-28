package com.petplatform.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.payment.api.query.PaymentRefundResultFactsApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskLease;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LateRefundConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(LateRefundConfiguration.class);
    }

    @Test void eitherMissingFlagKeepsAllLateRefundBeansAbsent() {
        runner().run(context -> assertThat(context).doesNotHaveBean(LateRefundService.class));
        runner().withPropertyValues("pet.refund.late.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LateRefundService.class));
        runner().withPropertyValues("pet.payment.foundation.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LateRefundService.class));
    }

    @Test void enabledWithoutChannelCredentialsFailsClosed() {
        dependencies(null).withPropertyValues("pet.refund.late.enabled=true",
                        "pet.payment.foundation.enabled=true", "pet.payment.dispatch.request-ip=127.0.0.1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void injectedOfflineChannelComposesWithoutNetworkOrWorker() {
        PaymentRefundChannel offline = mock(PaymentRefundChannel.class);
        dependencies(offline).withPropertyValues("pet.refund.late.enabled=true",
                        "pet.payment.foundation.enabled=true", "pet.payment.dispatch.request-ip=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LateRefundService.class);
                    assertThat(context).hasSingleBean(OrderLatePaymentFactsApi.class);
                    assertThat(context).hasSingleBean(PaymentRefundApi.class);
                    assertThat(context).hasSingleBean(PaymentRefundResultFactsApi.class);
                    assertThat(context).hasSingleBean(RefundExecutionService.class);
                    assertThat(context).doesNotHaveBean(AsyncTaskWorker.class);
                    verifyNoInteractions(offline);
                });
    }

    @Test void taskPayloadMustMatchImmutableTaskKeyAndVersion() {
        var valid = new TaskLease(1L, "REFUND_SUBMIT:123:0", "REFUND_SUBMIT", 123L,
                0L, "{\"refundOrderId\":\"123\",\"storeId\":\"456\",\"bindingVersion\":0}",
                "qa", 1L, 2L, 1, 0, 8, "REFUND_CHANNEL");
        var registration = LateRefundConfiguration.registration("REFUND_SUBMIT",
                mock(RefundExecutionService.class), mock(DataSource.class));
        assertThat(registration.decode().apply(valid))
                .isEqualTo(new LateRefundConfiguration.RefundPayload("123", "456"));
        var duplicateField = new TaskLease(1L, valid.taskKey(), valid.taskType(), valid.bizId(),
                0L, "{\"refundOrderId\":\"123\",\"refundOrderId\":\"123\",\"storeId\":\"456\",\"bindingVersion\":0}",
                "qa", 1L, 2L, 1, 0, 8, "REFUND_CHANNEL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registration.decode().apply(duplicateField))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongKey = new TaskLease(1L, "REFUND_SUBMIT:124:0", valid.taskType(), valid.bizId(),
                0L, valid.payloadJson(), "qa", 1L, 2L, 1, 0, 8, "REFUND_CHANNEL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registration.decode().apply(wrongKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ApplicationContextRunner dependencies(PaymentRefundChannel channel) {
        var result = runner().withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SnowflakeIdGenerator.class, () -> () -> 1L)
                .withBean(ScheduleCapacityGuardApi.class, () -> mock(ScheduleCapacityGuardApi.class))
                .withBean(ReservationExpiryApi.class, () -> mock(ReservationExpiryApi.class))
                .withBean(PaymentSuccessFactsApi.class, () -> mock(PaymentSuccessFactsApi.class))
                .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class))
                .withBean(PaymentFoundationConfiguration.LakalaSettings.class,
                        () -> new PaymentFoundationConfiguration.LakalaSettings(
                                Map.of(), null, "Asia/Shanghai"));
        return channel == null ? result : result.withBean(PaymentRefundChannel.class, () -> channel);
    }
}
