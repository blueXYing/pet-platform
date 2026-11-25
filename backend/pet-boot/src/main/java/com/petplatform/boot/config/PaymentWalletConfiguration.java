package com.petplatform.boot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.settlement.LakalaWalletChannel;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup;
import com.petplatform.payment.biz.settlement.WalletChannel;
import com.petplatform.payment.biz.settlement.WalletReceiptWriter;
import com.petplatform.payment.biz.settlement.WalletService;
import com.petplatform.payment.biz.task.WalletQueryTask;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Contract 80 §7 merchant wallet & D1 withdrawal configuration (CCR-PAY-CENTER-001 Stage 5).
 * Default off ({@code pet.payment.wallet.enabled=false}); enabling requires
 * {@code pet.payment.receiver.enabled=true} (which in turn requires {@code pet.payment.settlement.enabled=true}).
 * Reuses Stage 4's {@link LakalaV2HttpClient} and registers a Stage 6 compensation hook automatically.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentWalletConfiguration.WalletSettings.class)
public class PaymentWalletConfiguration {
    static final String REQUIRES_RECEIVER = "Payment wallet requires pet.payment.receiver.enabled=true";

    @ConfigurationProperties(prefix = "pet.payment.wallet")
    public record WalletSettings(boolean enabled, String withdrawNotifyUrl) {}

    @Bean
    InitializingBean paymentWalletConfigurationGuard(
            @Value("${pet.payment.receiver.enabled:false}") boolean receiverEnabled,
            WalletSettings settings) {
        return () -> check(receiverEnabled, settings);
    }

    /** Pure startup guard check, unit-tested offline. */
    static void check(boolean receiverEnabled, WalletSettings settings) {
        if (!settings.enabled()) return;
        if (!receiverEnabled) throw new IllegalStateException(REQUIRES_RECEIVER);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(
            "'${pet.payment.wallet.enabled:false}' == 'true' and '${pet.payment.receiver.enabled:false}' == 'true'")
    static class Runtime {

        @Bean
        @ConditionalOnMissingBean(WalletChannel.class)
        WalletChannel walletChannel(LakalaV2HttpClient v2Client, ObjectProvider<Clock> clocks) {
            return new LakalaWalletChannel(v2Client, clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        @ConditionalOnMissingBean(WalletReceiptWriter.NotificationVerifier.class)
        WalletReceiptWriter.NotificationVerifier walletNotificationVerifier(
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            String path = lakala == null ? null : lakala.notificationCertificatePath();
            if (path == null || path.isBlank()) {
                return (headers, body) -> {
                    throw new IllegalStateException("Wallet withdrawal notification certificate unavailable");
                };
            }
            try (var input = Files.newInputStream(Path.of(path))) {
                PublicKey key = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input).getPublicKey();
                return WalletReceiptWriter.NotificationVerifier.fromPublicKey(key);
            } catch (Exception failed) {
                throw new IllegalStateException("Configured wallet notification certificate is unavailable");
            }
        }

        @Bean
        @DependsOn("paymentWalletConfigurationGuard")
        WalletReceiptWriter walletReceiptWriter(
                DataSource source,
                SnowflakeIdGenerator ids,
                WalletReceiptWriter.NotificationVerifier verifier,
                ObjectProvider<ObjectMapper> mappers,
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            ZoneId zone = requireChannelZone(lakala);
            var publisher = new TransactionalOutboxPublisher(source, ids,
                    mappers.getIfAvailable(ObjectMapper::new));
            return new WalletReceiptWriter(source, ids, verifier, publisher, zone);
        }

        @Bean
        @DependsOn("paymentWalletConfigurationGuard")
        WalletService walletService(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementReceiverLookup receiverLookup,
                WalletChannel channel,
                WalletReceiptWriter receiptWriter,
                @Value("${pet.payment.settlement.org-code:}") String orgCode,
                WalletSettings settings,
                ObjectProvider<Clock> clocks) {
            return new WalletService(
                    source,
                    ids,
                    receiverLookup,
                    channel,
                    receiptWriter,
                    new JdbcAsyncTaskSubmitter(source, ids),
                    new WalletService.Settings(orgCode, settings.withdrawNotifyUrl()),
                    clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @DependsOn("paymentWalletConfigurationGuard")
        AsyncTaskWorker walletQueryWorker(
                DataSource source,
                SnowflakeIdGenerator ids,
                WalletService walletService,
                ObjectProvider<Clock> clocks) {
            return AsyncTaskWorker.create(
                    source, ids, "payment-wallet-withdraw-" + UUID.randomUUID(),
                    clocks.getIfAvailable(Clock::systemUTC),
                    TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of(
                            WalletQueryTask.RETRY_POLICY, WalletQueryTask.DELAYS)),
                    List.<TaskRegistration<?>>of(
                            WalletQueryTask.registration(walletService::pollWithdrawalStatus)));
        }

        @Bean
        SettlementCompensationScanner.CompensationHook stuckWithdrawalCompensationHook(WalletService walletService) {
            return walletService::scanStuckWithdrawals;
        }

        private static ZoneId requireChannelZone(PaymentFoundationConfiguration.LakalaSettings lakala) {
            String zone = lakala == null ? null : lakala.channelTimeZone();
            if (zone == null || zone.isBlank()) {
                throw new IllegalStateException("Payment wallet requires pet.payment.lakala.channel-time-zone");
            }
            return ZoneId.of(zone);
        }
    }
}
