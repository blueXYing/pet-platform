package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.command.AdminMerchantDispositionApi;
import com.petplatform.merchant.biz.apiimpl.AdminMerchantDispositionApiImpl;
import com.petplatform.merchant.biz.application.AdminDispositionAuthorizationPort;
import com.petplatform.order.api.command.AdminOrderDispositionApi;
import com.petplatform.order.api.query.OrderAdminCloseFactsApi;
import com.petplatform.order.biz.apiimpl.AdminOrderDispositionApiImpl;
import com.petplatform.order.biz.apiimpl.AdminOrderRefundProjectionConsumer;
import com.petplatform.order.biz.apiimpl.OrderAdminCloseFactsApiImpl;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.command.AdminAbnormalCloseRefundApi;
import com.petplatform.refund.api.command.AdminRefundDispositionApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.apiimpl.AdminRefundDispositionApiImpl;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.RefundAuthorizationPort;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Contract-63 v0.2 disposal runtime (HTTP10 §5.2/§5.5/§5.6 drafts; user ruling 2026-10-15,
 * doc 33): the four disposal actions behind the SAME {@code pet.admin.trade.http.enabled} face
 * switch as the v0.1 read kernels, default off. Enabling requires the payment foundation (the
 * abnormal close creates a real full refund and the channel-query/retry re-arms real channel
 * coordination) and the admin authorization owner — the switch validation below fails fast on
 * incomplete flag combinations, mirroring the merchant-order slice. Each kernel re-proves the
 * ADMIN_WEB session, its disposal action code and the resource scope inside its own commit via
 * the module-local ports adapted here from the admin authorization owner (no biz depends on
 * another biz). The ADMIN refund task family reuses the existing durable
 * REFUND_SUBMIT/REFUND_CHANNEL_QUERY worker machinery — no new task type.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pet",
    name = {"auth.admin.enabled", "admin.trade.http.enabled"},
    havingValue = "true")
public class AdminTradeDispositionConfiguration {

    @Bean Object adminTradeDispositionSwitchValidation(Environment e) {
        boolean payment = e.getProperty("pet.payment.foundation.enabled", Boolean.class, false);
        boolean worker = e.getProperty("pet.admin.trade.worker.enabled", Boolean.class, false);
        if (worker && !payment) {
            throw new IllegalStateException(
                    "Admin trade disposal worker requires the payment foundation (channel refund execution)");
        }
        return new Object();
    }

    @Bean
    @ConditionalOnMissingBean(OrderAdminCloseFactsApi.class)
    OrderAdminCloseFactsApi adminCloseFacts(DataSource source, ScheduleCapacityGuardApi guard) {
        return new OrderAdminCloseFactsApiImpl(source, guard);
    }

    @Bean
    @ConditionalOnMissingBean(RefundOrderFactsApi.class)
    RefundOrderFactsApi dispositionRefundPresence(DataSource source, ScheduleCapacityGuardApi guard) {
        return new RefundOrderFactsApiImpl(source, guard);
    }

    @Bean
    @ConditionalOnMissingBean(ReservationRefundReleaseApi.class)
    ReservationRefundReleaseApi dispositionRefundRelease(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, LateRefundService refunds) {
        return new com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl(source, ids, guard, refunds);
    }

    @Bean
    AdminOrderRefundProjectionConsumer adminRefundProjection(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderAdminCloseFactsApi orders, LateRefundService refunds,
            ReservationRefundReleaseApi release) {
        return new AdminOrderRefundProjectionConsumer(source, ids, guard, orders, refunds, release);
    }

    @Bean
    AdminOrderDispositionApi adminOrderDispositionApi(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ObjectProvider<AdminAuthorizationQueryApi> authorization,
            PaymentSuccessFactsApi payments, RefundOrderFactsApi refundPresence,
            LateRefundService refunds) {
        return new AdminOrderDispositionApiImpl(source, ids, guard,
                new AdminTradeConfiguration.OrderTradeAuthorizationAdapter(requireAuthorization(authorization)),
                payments, refundPresence, refunds);
    }

    @Bean
    AdminRefundDispositionApi adminRefundDispositionApi(DataSource source, SnowflakeIdGenerator ids,
            LateRefundService refunds, ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        return new AdminRefundDispositionApiImpl(source, ids, refunds,
                new AdminTradeConfiguration.RefundTradeAuthorizationAdapter(requireAuthorization(authorization)));
    }

    @Bean
    AdminMerchantDispositionApi adminMerchantDispositionApi(DataSource source, SnowflakeIdGenerator ids,
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        return new AdminMerchantDispositionApiImpl(source, ids,
                new MerchantDispositionAdapter(requireAuthorization(authorization)));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pet.admin.trade.worker", name = "enabled", havingValue = "true")
    AsyncTaskWorker adminTradeRefundWorker(DataSource source, SnowflakeIdGenerator ids,
            RefundExecutionService execution, ObjectProvider<Clock> clocks) {
        return AsyncTaskWorker.create(source, ids, "admin-trade-" + UUID.randomUUID(),
                clocks.getIfAvailable(Clock::systemUTC), TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_CHANNEL", List.of(
                        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
                        Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30),
                        Duration.ofHours(1)))),
                List.of(LateRefundConfiguration.registration("ADMIN_REFUND_SUBMIT", execution, source),
                        LateRefundConfiguration.registration("ADMIN_REFUND_CHANNEL_QUERY", execution, source)));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pet.admin.trade.worker", name = "enabled", havingValue = "true")
    LateRefundConfiguration.DeadTaskReconciler adminTradeRefundReconciler(RefundExecutionService execution) {
        return new LateRefundConfiguration.DeadTaskReconciler(execution);
    }

    /**
     * Isolated opt-in Flyway migration for the disposal audit rows (V32), mirroring the guarded
     * service-write V27 precedent: only an explicitly named admtrade001_* database holding the
     * core/42/45/49 schemas is accepted, never the shared default datasource.
     */
    @Bean
    Object adminTradeDispositionMigration(
            DataSource source,
            @Value("${pet.admin.trade.migration-enabled:false}") boolean enabled,
            @Value("${pet.admin.trade.migration-database:}") String database) throws java.sql.SQLException {
        if (!enabled) return null;
        if (database == null || !database.startsWith("admtrade001_")
                || !database.equals(source.getConnection().getCatalog())) {
            throw new IllegalStateException(
                    "Only explicit isolated admin-trade disposal migration is permitted in this delivery");
        }
        try (var connection = source.getConnection()) {
            if (database.equals(connection.getCatalog())) {
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/admin-trade-migration")
                        .table("admin_trade_schema_history")
                        .load()
                        .migrate();
            }
        }
        return null;
    }

    private static AdminAuthorizationQueryApi requireAuthorization(
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        AdminAuthorizationQueryApi api = authorization.getIfAvailable();
        if (api == null) {
            throw new IllegalStateException(
                    "Trade disposal kernels require the admin authorization owner (pet.auth.admin.enabled)");
        }
        return api;
    }

    /** Boot-level adapter: merchant kernel port → admin authorization owner, fail-closed. */
    static final class MerchantDispositionAdapter
            implements AdminDispositionAuthorizationPort.MerchantDispositionAuthorizationPort {
        private final AdminAuthorizationQueryApi authorization;

        MerchantDispositionAdapter(AdminAuthorizationQueryApi authorization) {
            this.authorization = Objects.requireNonNull(authorization);
        }

        @Override
        public AdminDispositionAuthorizationPort.Decision check(AdminDispositionAuthorizationPort.Check check) {
            AdminActionDecision decision = authorization.check(new AdminActionCheckQuery(
                    check.sessionId(), check.sessionGeneration(), check.operatorId(), check.actionCode(),
                    new AdminResourceScope(check.resourceType(), check.resourceId(), check.merchantId(),
                            null, check.scopeVersion()),
                    check.purpose(), AdminActionCheckQuery.CheckPhase.EXECUTE));
            if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                    || decision.authzVersion().isBlank()) {
                throw new com.petplatform.common.ApiException(
                        com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
            }
            return new AdminDispositionAuthorizationPort.Decision(decision.allowed(), decision.checkedAt(),
                    decision.authzVersion(), decision.reasonCode());
        }
    }
}
