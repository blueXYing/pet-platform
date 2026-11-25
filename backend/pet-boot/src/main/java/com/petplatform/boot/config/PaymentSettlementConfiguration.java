package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.order.biz.apiimpl.OrderSettlementFactsApiImpl;
import com.petplatform.payment.api.command.SettlementAdminApi;
import com.petplatform.payment.api.command.SettlementTriggerApi;
import com.petplatform.payment.api.query.SettlementQueryApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient;
import com.petplatform.payment.biz.settlement.LakalaSettlementChannel;
import com.petplatform.payment.biz.settlement.SettlementAdminService;
import com.petplatform.payment.biz.settlement.SettlementChannel;
import com.petplatform.payment.biz.settlement.SettlementEventConsumers;
import com.petplatform.payment.biz.settlement.SettlementExecutionService;
import com.petplatform.payment.biz.settlement.SettlementFallbackService;
import com.petplatform.payment.biz.settlement.SettlementOrderBlockerPort;
import com.petplatform.payment.biz.settlement.SettlementQueryCoordinator;
import com.petplatform.payment.biz.settlement.SettlementReceiptWriter;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 78 settlement configuration (CCR-PAY-CENTER-001 stage 2).
 * Default off ({@code pet.payment.settlement.enabled=false}); enabling requires {@code pet.payment.enabled=true}
 * and platform merchant configuration. No public HTTP controller is registered in stage 2.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentSettlementConfiguration.SettlementSettings.class)
public class PaymentSettlementConfiguration {
    static final String REQUIRES_PAYMENT = "Payment settlement requires pet.payment.enabled=true";
    static final String INVALID_LOWEST_RATIO = "pet.payment.settlement.lowest-ratio must be in [0, 1) with at most 4 decimals";

    @ConfigurationProperties(prefix = "pet.payment.settlement")
    public record SettlementSettings(
            boolean enabled,
            String lowestRatio,
            String orgCode,
            String notifyUrl,
            boolean migrationEnabled,
            String migrationDatabase) {
        public BigDecimal parsedLowestRatio() {
            String raw = lowestRatio == null || lowestRatio.isBlank() ? "0" : lowestRatio.trim();
            try {
                return SettlementAmountCalculator.validateLowestRatio(new BigDecimal(raw));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(INVALID_LOWEST_RATIO);
            }
        }
    }

    @Bean
    InitializingBean paymentSettlementConfigurationGuard(
            @Value("${pet.payment.enabled:false}") boolean paymentEnabled,
            SettlementSettings settings) {
        return () -> check(paymentEnabled, settings);
    }

    /** Pure flag and ratio check, unit-tested offline. */
    static void check(boolean paymentEnabled, SettlementSettings settings) {
        if (!settings.enabled()) return;
        if (!paymentEnabled) throw new IllegalStateException(REQUIRES_PAYMENT);
        settings.parsedLowestRatio();
    }

    /** Isolated opt-in Flyway migration for test databases named {@code paysettle_*}. */
    @Bean
    Object settlementMigration(DataSource source, SettlementSettings settings) throws SQLException {
        if (!settings.migrationEnabled()) return null;
        String catalog;
        try (Connection conn = source.getConnection()) {
            catalog = conn.getCatalog();
        }
        String target = settings.migrationDatabase();
        if (target == null || !target.startsWith("paysettle_") || !target.equals(catalog)) {
            throw new IllegalStateException(
                    "Only explicit isolated settlement migration (paysettle_*) is permitted");
        }
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/settlement-migration")
                .table("settlement_schema_history")
                .load()
                .migrate();
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet.payment.settlement", name = "enabled", havingValue = "true")
    static class Runtime {

        @Bean @ConditionalOnMissingBean(OrderSettlementFactsApi.class)
        OrderSettlementFactsApi orderSettlementFactsApi(DataSource source) {
            return new OrderSettlementFactsApiImpl(source);
        }

        @Bean @ConditionalOnMissingBean(SettlementReceiverLookup.class)
        SettlementReceiverLookup settlementReceiverLookup() {
            // Stage 2 fail-closed default until Stage 4 delivers receiver onboarding.
            return SettlementReceiverLookup.failClosed();
        }

        @Bean @ConditionalOnMissingBean(SettlementOrderBlockerPort.class)
        SettlementOrderBlockerPort settlementOrderBlockerPort(OrderSettlementFactsApi orderFacts) {
            return SettlementOrderBlockerPort.fromOrderFacts(orderFacts);
        }

        @Bean @ConditionalOnMissingBean(SettlementChannel.class)
        SettlementChannel settlementChannel(PaymentDispatchConfiguration.DispatchSettings dispatch,
                ObjectProvider<Clock> clocks) {
            try {
                if (dispatch.environment() == null) throw new IllegalArgumentException();
                String pem = read(dispatch.merchantPrivateKeyPath(), 16_384);
                String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
                PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                PublicKey platformKey;
                try (var input = Files.newInputStream(Path.of(dispatch.platformCertificatePath()))) {
                    platformKey = CertificateFactory.getInstance("X.509")
                            .generateCertificate(input).getPublicKey();
                }
                var credentials = new LakalaHttpClient.Credentials(dispatch.appId(),
                        dispatch.merchantSerial(), privateKey, dispatch.platformSerial(), platformKey);
                return new LakalaSettlementChannel(
                        new LakalaSacsHttpClient(dispatch.environment(), credentials),
                        clocks.getIfAvailable(Clock::systemUTC));
            } catch (Exception failure) {
                throw new IllegalStateException("Settlement SACS credentials or launch configuration unavailable");
            }
        }

        @Bean @ConditionalOnMissingBean(SettlementReceiptWriter.NotificationVerifier.class)
        SettlementReceiptWriter.NotificationVerifier settlementNotificationVerifier(
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            String path = lakala.notificationCertificatePath();
            if (path == null || path.isBlank()) {
                return (headers, body) -> {
                    throw new IllegalStateException("Settlement notification certificate unavailable");
                };
            }
            try (var input = Files.newInputStream(Path.of(path))) {
                PublicKey key = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input).getPublicKey();
                return SettlementReceiptWriter.NotificationVerifier.fromPublicKey(key);
            } catch (Exception failed) {
                throw new IllegalStateException("Configured settlement notification certificate is unavailable");
            }
        }

        @Bean
        SettlementFallbackService settlementFallbackService(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementChannel channel,
                PaymentFoundationConfiguration.LakalaSettings lakala,
                ObjectProvider<Clock> clocks) {
            return new SettlementFallbackService(
                    source, ids, channel, new JdbcAsyncTaskSubmitter(source, ids),
                    requireChannelZone(lakala), clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        SettlementAdminService settlementAdminService(DataSource source, SnowflakeIdGenerator ids,
                SettlementFallbackService fallbackService) {
            return new SettlementAdminService(source, ids, new JdbcAsyncTaskSubmitter(source, ids),
                    fallbackService::retryFailedFallback);
        }

        @Bean
        SettlementExecutionService settlementExecutionService(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementChannel channel,
                SettlementReceiverLookup receivers,
                SettlementOrderBlockerPort blockers,
                PaymentMerchantBindings bindings,
                PaymentFoundationConfiguration.LakalaSettings lakala,
                SettlementSettings settlementSettings,
                ObjectProvider<Clock> clocks) {
            String merchantNo = requirePlatformMerchantNo(bindings);
            ZoneId zone = requireChannelZone(lakala);
            var settings = new SettlementExecutionService.Settings(
                    merchantNo, zone, settlementSettings.parsedLowestRatio(),
                    settlementSettings.orgCode(), settlementSettings.notifyUrl());
            return new SettlementExecutionService(source, ids, channel, receivers, blockers,
                    new JdbcAsyncTaskSubmitter(source, ids), settings,
                    clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        SettlementReceiptWriter settlementReceiptWriter(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementReceiptWriter.NotificationVerifier verifier,
                IntegrationEventPublisher publisher) {
            return new SettlementReceiptWriter(source, ids, verifier, publisher);
        }

        @Bean
        SettlementQueryCoordinator settlementQueryCoordinator(
                DataSource source,
                SettlementChannel channel,
                SettlementReceiptWriter writer,
                PaymentFoundationConfiguration.LakalaSettings lakala,
                ObjectProvider<Clock> clocks) {
            return new SettlementQueryCoordinator(source, channel, writer,
                    requireChannelZone(lakala), clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        AsyncTaskWorker settlementTaskWorker(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementExecutionService execution,
                SettlementQueryCoordinator queryCoordinator,
                ObjectProvider<Clock> clocks) {
            return AsyncTaskWorker.create(
                    source, ids, "payment-settlement-" + UUID.randomUUID(),
                    clocks.getIfAvailable(Clock::systemUTC),
                    TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of(
                            SettlementDueTask.RETRY_POLICY, SettlementDueTask.DELAYS,
                            SettlementQueryTask.RETRY_POLICY, SettlementQueryTask.DELAYS)),
                    List.<TaskRegistration<?>>of(
                            SettlementDueTask.registration(execution::executeDue),
                            SettlementQueryTask.registration(queryCoordinator::execute)));
        }

        @Bean
        SettlementEventConsumers.OnPaymentSucceeded settlementOnPaymentSucceededConsumer(
                DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            return new SettlementEventConsumers.OnPaymentSucceeded(source, claims, orderFacts, settlement);
        }

        @Bean
        SettlementEventConsumers.OnOrderVerified settlementOnOrderVerifiedConsumer(
                DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            return new SettlementEventConsumers.OnOrderVerified(source, claims, orderFacts, settlement);
        }

        @Bean
        SettlementEventConsumers.OnRefundCreated settlementOnRefundCreatedConsumer(
                DataSource source, JdbcOutboxConsumeGuard claims, SettlementAdminService settlement) {
            return new SettlementEventConsumers.OnRefundCreated(source, claims, settlement);
        }

        @Bean
        SettlementEventConsumers.OnRefundSucceeded settlementOnRefundSucceededConsumer(
                DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            return new SettlementEventConsumers.OnRefundSucceeded(source, claims, orderFacts, settlement);
        }

        private static String requirePlatformMerchantNo(PaymentMerchantBindings bindings) {
            try {
                var binding = bindings.requirePlatform();
                if (binding == null || binding.merchantNo() == null || binding.merchantNo().isBlank()) {
                    throw new IllegalArgumentException();
                }
                return binding.merchantNo();
            } catch (RuntimeException missing) {
                throw new IllegalStateException(
                        "Payment settlement requires the platform merchant (pet.payment.lakala.platform.*)");
            }
        }

        private static ZoneId requireChannelZone(PaymentFoundationConfiguration.LakalaSettings lakala) {
            String zone = lakala == null ? null : lakala.channelTimeZone();
            if (zone == null || zone.isBlank()) {
                throw new IllegalStateException("Payment settlement requires pet.payment.lakala.channel-time-zone");
            }
            return ZoneId.of(zone);
        }

        private static String read(String path, int limit) throws Exception {
            if (path == null || path.isBlank()) throw new IllegalArgumentException();
            try (var input = Files.newInputStream(Path.of(path))) {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length == 0 || bytes.length > limit) throw new IllegalArgumentException();
                return new String(bytes, StandardCharsets.US_ASCII);
            }
        }
    }
}
