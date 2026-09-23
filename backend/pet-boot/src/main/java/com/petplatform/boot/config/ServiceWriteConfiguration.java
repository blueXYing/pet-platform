package com.petplatform.boot.config;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.service.biz.apiimpl.ServiceCommandApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceManagementQueryApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceReviewQueryApiImpl;
import com.petplatform.service.biz.application.ServiceWriteDependencies;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverAssetPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceCoverUrlPort;
import com.petplatform.service.biz.application.ServiceWriteDependencies.ServiceReviewAuthorizationPort;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Service write slice assembly (CCR-W2-API-001, proposal v0.2): merchant workbench commands and
 * admin review, default OFF like pet.service.query. Enabling requires the real merchant admission
 * facts, the private-asset owner, the admin authorization API and the transactional outbox
 * publisher (review decisions append ServiceReviewedEvent.v1 in the same transaction). The cover
 * presigner is optional: without a real signer bean, visible rows carrying a cover fail closed.
 *
 * <p>Reserved assembly point (role E alignment, 2026-09-22): the ServiceReviewedConsumer bean
 * registration and the {@code pet.service.review.notifications-enabled} switch (default off) land
 * in EventOutboxConfiguration together with the consumer class from the notification-side PR —
 * enabling the review flow without the consumer is intentional and disclosed: the outbox rows
 * persist and are dispatched once the consumer merges.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.service.command", name = "enabled", havingValue = "true")
public class ServiceWriteConfiguration {

    @Bean
    @ConditionalOnMissingBean(ServiceReviewAuthorizationPort.class)
    ServiceReviewAuthorizationPort serviceReviewAuthorization(
            AdminAuthorizationQueryApi authorization) {
        return new ServiceReviewAuthorizationAdapter(authorization);
    }

    @Bean
    @ConditionalOnMissingBean(ServiceCoverAssetPort.class)
    @ConditionalOnBean(PrivateAssetApi.class)
    ServiceCoverAssetPort serviceCoverAssets(PrivateAssetApi assets) {
        return new ServiceCoverAssetAdapter(assets);
    }

    @Bean
    ServiceWriteDependencies serviceWriteDependencies(
            ObjectProvider<ServiceCoverAssetPort> coverAssets,
            ObjectProvider<ServiceCoverUrlPort> coverUrls,
            ServiceReviewAuthorizationPort authorization,
            IntegrationEventPublisher events) {
        ServiceWriteDependencies unavailable = ServiceWriteDependencies.unavailable();
        return new ServiceWriteDependencies(
                coverAssets.getIfAvailable(unavailable::coverAssets),
                coverUrls.getIfAvailable(unavailable::coverUrls),
                authorization,
                Objects.requireNonNull(events, "outbox publisher is required"));
    }

    @Bean
    ServiceCommandApiImpl serviceCommandApi(
            DataSource source,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            ServiceWriteDependencies deps,
            ObjectProvider<Clock> clock) {
        return new ServiceCommandApiImpl(
                source, ids, admissions, deps, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ServiceReviewQueryApiImpl serviceReviewQueryApi(
            DataSource source,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            ObjectProvider<Clock> clock) {
        return new ServiceReviewQueryApiImpl(
                source, ids, admissions, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ServiceManagementQueryApiImpl serviceManagementQueryApi(
            DataSource source,
            SnowflakeIdGenerator ids,
            MerchantAdmissionQueryApi admissions,
            ObjectProvider<Clock> clock) {
        return new ServiceManagementQueryApiImpl(
                source, ids, admissions, clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Isolated opt-in Flyway migration for the service write delta (SQL33), mirroring the guarded
     * admin-auth V26 precedent: only an explicitly named svcw001_* database holding the SQL06
 * service tables is accepted, never the shared default datasource.
     */
    @Bean
    Object serviceWriteMigration(
            DataSource source,
            @Value("${pet.service.command.migration-enabled:false}") boolean enabled,
            @Value("${pet.service.command.migration-database:}") String database)
            throws java.sql.SQLException {
        if (!enabled) return null;
        if (database == null || !database.startsWith("svcw001_")
                || !database.equals(source.getConnection().getCatalog())) {
            throw new IllegalStateException(
                    "Only explicit isolated service-write migration is permitted in this delivery");
        }
        try (var connection = source.getConnection()) {
            if (database.equals(connection.getCatalog())) {
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/service-migration")
                        .table("service_write_schema_history")
                        .load()
                        .migrate();
            }
        }
        return null;
    }
}
