package com.petplatform.boot.config;

import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.order.biz.apiimpl.OrderAutoConfirmTaskInspectionApiImpl;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.biz.application.OrderAutoConfirmService;
import com.petplatform.order.biz.application.OrderAutoConfirmTaskRegistration;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** All task production, execution and recovery are independently opt-in. */
@Configuration(proxyBeanMethods = false)
public class OrderAutoConfirmTaskConfiguration {
    @Bean InitializingBean autoConfirmTaskConfigurationGuard(
            @Value("${pet.order.auto-confirm.enabled:false}") boolean enabled,
            @Value("${pet.payment.foundation.enabled:false}") boolean paymentEnabled,
            @Value("${pet.order.auto-confirm.worker.enabled:false}") boolean worker,
            @Value("${pet.order.auto-confirm.repair.enabled:false}") boolean repair) {
        return () -> {
            if ((worker || repair) && (!enabled || !paymentEnabled))
                throw new IllegalStateException("Auto-confirm execution and repair require task production and payment foundation");
            if (enabled && !paymentEnabled)
                throw new IllegalStateException("Auto-confirm task production requires payment foundation");
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "pet.order.auto-confirm.inspection", name = "enabled", havingValue = "true")
    OrderAutoConfirmTaskInspectionApi orderAutoConfirmTaskInspectionApi(DataSource source) {
        return new OrderAutoConfirmTaskInspectionApiImpl(source);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(
            "${pet.order.auto-confirm.enabled:false} && ${pet.payment.foundation.enabled:false}"
            + " && (${pet.order.auto-confirm.worker.enabled:false} || ${pet.order.auto-confirm.repair.enabled:false})")
    static class Execution {
        @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RefundOrderFactsApi.class)
        RefundOrderFactsApi refundOrderFactsApi(DataSource source, ScheduleCapacityGuardApi guard) {
            return new RefundOrderFactsApiImpl(source, guard);
        }
        @Bean OrderAutoConfirmService orderAutoConfirmService(
                DataSource source, SnowflakeIdGenerator ids, ScheduleCapacityGuardApi guard,
                PaymentSuccessFactsApi payments, ReservationConfirmApi reservations,
                RefundOrderFactsApi refunds, IntegrationEventPublisher outbox) {
            return new OrderAutoConfirmService(source, ids, guard, payments, reservations, refunds, outbox);
        }
        @Bean(initMethod="start",destroyMethod="close")
        @ConditionalOnProperty(prefix="pet.order.auto-confirm.worker",name="enabled",havingValue="true")
        AsyncTaskWorker orderAutoConfirmWorker(DataSource source,
                SnowflakeIdGenerator ids, OrderAutoConfirmService service) {
            return AsyncTaskWorker.create(source, ids, "order-auto-confirm-" + UUID.randomUUID(),
                    Clock.systemUTC(), TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of("ORDER_AUTO_CONFIRM",
                            List.of(Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5)))),
                    List.of(OrderAutoConfirmTaskRegistration.create(source, service)));
        }
        @Bean(initMethod="start",destroyMethod="close")
        Reconciler orderAutoConfirmReconciler(OrderAutoConfirmService service,
                @Value("${pet.order.auto-confirm.repair.enabled:false}") boolean repair) {
            return new Reconciler(service,repair);
        }
    }

    static final class Reconciler implements AutoCloseable {
        private final OrderAutoConfirmService service;
        private final boolean repair;
        private long cursor;
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("order-auto-confirm-reconcile").factory());
        Reconciler(OrderAutoConfirmService service,boolean repair) {
            this.service=service; this.repair=repair;
        }
        public void start() {
            scheduler.scheduleWithFixedDelay(() -> {
                try { cursor=service.reconcile(cursor,100,repair); }
                catch (RuntimeException failure) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                            "Auto-confirm scan unavailable: {0}",failure.getClass().getSimpleName());
                }
            },60,60,TimeUnit.SECONDS);
        }
        @Override public void close() { scheduler.shutdown(); }
    }
}
