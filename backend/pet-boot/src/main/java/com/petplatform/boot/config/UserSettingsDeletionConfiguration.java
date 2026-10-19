package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.order.api.query.OrderDeletionFactsApi;
import com.petplatform.aftersale.api.query.AfterSaleDeletionFactsApi;
import com.petplatform.order.biz.apiimpl.OrderDeletionFactsApiImpl;
import com.petplatform.aftersale.biz.apiimpl.AfterSaleDeletionFactsApiImpl;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import com.petplatform.user.biz.application.AccountDeletionService;
import com.petplatform.user.biz.application.AccountDeletionTaskSpec;
import com.petplatform.user.biz.application.AccountDeletionTaskRegistration;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 66 user settings / account-deletion slice (PRD 5.1.29; CCR-USR-DELETION-001 v0.2
 * cooling period): assembled only when the C session slice, this slice's switch AND the
 * merchant admission slice (OWNER membership surface) are all on. The deletion kernel consumes
 * the order/aftersale leaf fact ports and the frozen OWNER-membership surface, so a missing
 * port bean is an assembly error, never a runtime fallback. The cooling period is deployment
 * configuration (pet.usersettings.deletion.cooldown-days, default 7, bounded 1..30); the
 * durable expiry worker is independently opt-in (pet.usersettings.deletion.worker.enabled,
 * SQL13 family precedent — production, execution and recovery are separate decisions).
 * Default off in production until the deployment gate opens it (pet.usersettings.http.enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet",
        name = {"auth.c.enabled", "usersettings.http.enabled", "merchant.application.enabled"},
        havingValue = "true")
public class UserSettingsDeletionConfiguration {

    @Bean
    OrderDeletionFactsApi orderDeletionFacts(DataSource source) {
        return new OrderDeletionFactsApiImpl(source);
    }

    @Bean
    AfterSaleDeletionFactsApi afterSaleDeletionFacts(DataSource source) {
        return new AfterSaleDeletionFactsApiImpl(source);
    }

    @Bean
    AccountDeletionService accountDeletionService(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            OrderDeletionFactsApi orderFacts,
            AfterSaleDeletionFactsApi aftersaleFacts,
            ObjectProvider<MerchantAdmissionQueryApi> memberships,
            @Value("${pet.usersettings.deletion.cooldown-days:7}") int cooldownDays) {
        return new AccountDeletionService(source, idProvider(ids), orderFacts, aftersaleFacts,
                java.util.Objects.requireNonNull(memberships.getIfAvailable(),
                        "OWNER membership surface required before deletion precheck can start"),
                cooldownDays, Clock.systemUTC());
    }

    /**
     * The cooling-period expiry executor: one durable worker over this module's
     * ACCOUNT_DELETION_EXEC registrations; retries are technical only (the request row is the
     * authoritative state — auto-cancels never retry).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pet.usersettings.deletion.worker",
            name = "enabled", havingValue = "true")
    AsyncTaskWorker accountDeletionExpiryWorker(
            DataSource source,
            ObjectProvider<SnowflakeIdGenerator> ids,
            AccountDeletionService service) {
        return AsyncTaskWorker.create(source, idProvider(ids),
                "account-deletion-exec-" + UUID.randomUUID(), Clock.systemUTC(),
                TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of(AccountDeletionTaskSpec.TYPE,
                        List.of(Duration.ofSeconds(30), Duration.ofMinutes(1),
                                Duration.ofMinutes(5), Duration.ofMinutes(30)))),
                List.<TaskRegistration<?>>of(accountDeletionExpiryRegistration(source, service)));
    }

    /** Exposed separately so isolated-kernel tests can pin the registration shape. */
    static TaskRegistration<AccountDeletionTaskRegistration.Payload>
            accountDeletionExpiryRegistration(DataSource source, AccountDeletionService service) {
        return AccountDeletionTaskRegistration.create(source, service);
    }

    private static SnowflakeIdGenerator idProvider(ObjectProvider<SnowflakeIdGenerator> ids) {
        return java.util.Objects.requireNonNull(ids.getIfAvailable(),
                "PLAT-002 ID provider required before deletion writes can start");
    }
}
