package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.settlement.DbSettlementReceiverLookup;
import com.petplatform.payment.biz.settlement.LakalaReceiverChannel;
import com.petplatform.payment.biz.settlement.ReceiverAccountService;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider;
import com.petplatform.payment.biz.settlement.ReceiverChannel;
import com.petplatform.payment.biz.settlement.ReceiverSensitiveCipher;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup;
import com.petplatform.payment.biz.task.ReceiverBindQueryTask;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

/**
 * Contract 80 split-receiver configuration (CCR-PAY-CENTER-001 Stage 4).
 * Wired directly under the unified payment base switch {@code pet.payment.enabled=true}
 * (per PR #234 single-switch architecture); requires a valid 32-byte Base64 AES-256 key file at
 * {@code pet.payment.receiver.aes-key-path}. Supersedes Stage 2's fail-closed
 * {@link SettlementReceiverLookup} with {@link DbSettlementReceiverLookup}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentReceiverConfiguration.ReceiverSettings.class)
public class PaymentReceiverConfiguration {
    static final String INVALID_AES_KEY = "pet.payment.receiver.aes-key-path must point to a valid 32-byte Base64 AES key file";

    @ConfigurationProperties(prefix = "pet.payment.receiver")
    public record ReceiverSettings(
            String aesKeyPath,
            String ledgerNotifyUrl) {}

    @Bean
    InitializingBean paymentReceiverConfigurationGuard(
            @Value("${pet.payment.enabled:false}") boolean paymentEnabled,
            ReceiverSettings settings) {
        return () -> check(paymentEnabled, settings);
    }

    /** Pure startup guard check, unit-tested offline. */
    static void check(boolean paymentEnabled, ReceiverSettings settings) {
        if (!paymentEnabled) return;
        try {
            ReceiverSensitiveCipher.loadKeyFromFile(settings.aesKeyPath());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(INVALID_AES_KEY);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
    static class Runtime {

        @Bean
        @DependsOn("paymentReceiverConfigurationGuard")
        ReceiverSensitiveCipher receiverSensitiveCipher(ReceiverSettings settings) {
            try {
                return ReceiverSensitiveCipher.fromKeyFile(settings.aesKeyPath());
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(INVALID_AES_KEY);
            }
        }

        @Bean
        @ConditionalOnMissingBean(ReceiverAttachmentProvider.class)
        ReceiverAttachmentProvider receiverAttachmentProvider(
                ObjectProvider<PrivateAssetApi> privateAssets) {
            PrivateAssetApi api = privateAssets.getIfAvailable();
            if (api == null) {
                return ReceiverAttachmentProvider.failClosed();
            }
            return new PrivateAssetReceiverAttachmentAdapter(api);
        }

        @Bean
        @ConditionalOnMissingBean({LakalaV2HttpClient.class, ReceiverChannel.class})
        LakalaV2HttpClient lakalaV2HttpClient(PaymentDispatchConfiguration.DispatchSettings dispatch) {
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
                return new LakalaV2HttpClient(dispatch.environment(), credentials);
            } catch (Exception failure) {
                throw new IllegalStateException("Lakala v2 credentials or launch configuration unavailable");
            }
        }

        @Bean
        @ConditionalOnMissingBean(ReceiverChannel.class)
        ReceiverChannel receiverChannel(LakalaV2HttpClient v2Client, ObjectProvider<Clock> clocks) {
            return new LakalaReceiverChannel(v2Client, clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        @ConditionalOnMissingBean(ReceiverAccountService.BindNotificationVerifier.class)
        ReceiverAccountService.BindNotificationVerifier receiverBindNotificationVerifier(
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            String path = lakala == null ? null : lakala.notificationCertificatePath();
            if (path == null || path.isBlank()) {
                return (headers, body) -> {
                    throw new IllegalStateException("Receiver ledger notification certificate unavailable");
                };
            }
            try (var input = Files.newInputStream(Path.of(path))) {
                PublicKey key = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input).getPublicKey();
                return ReceiverAccountService.BindNotificationVerifier.fromPublicKey(key);
            } catch (Exception failed) {
                throw new IllegalStateException("Configured receiver notification certificate is unavailable");
            }
        }

        @Bean
        @DependsOn("paymentReceiverConfigurationGuard")
        ReceiverAccountService receiverAccountService(
                DataSource source,
                SnowflakeIdGenerator ids,
                ReceiverSensitiveCipher cipher,
                ReceiverAttachmentProvider attachmentProvider,
                ReceiverChannel channel,
                ReceiverAccountService.BindNotificationVerifier notificationVerifier,
                PaymentMerchantBindings bindings,
                @Value("${pet.payment.settlement.org-code:}") String orgCode,
                ReceiverSettings receiverSettings,
                ObjectProvider<Clock> clocks) {
            String merchantNo = requirePlatformMerchantNo(bindings);
            var settings = new ReceiverAccountService.Settings(
                    orgCode,
                    merchantNo,
                    null,
                    receiverSettings.ledgerNotifyUrl());
            return new ReceiverAccountService(
                    source, ids, cipher, attachmentProvider, channel,
                    new JdbcAsyncTaskSubmitter(source, ids),
                    notificationVerifier, settings, clocks.getIfAvailable(Clock::systemUTC),
                    bindings::findAcquiringMerchantNo);
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @DependsOn("paymentReceiverConfigurationGuard")
        AsyncTaskWorker receiverBindQueryWorker(
                DataSource source,
                SnowflakeIdGenerator ids,
                ReceiverAccountService receiverAccountService,
                ObjectProvider<Clock> clocks) {
            return AsyncTaskWorker.create(
                    source, ids, "payment-receiver-bind-" + UUID.randomUUID(),
                    clocks.getIfAvailable(Clock::systemUTC),
                    TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of(
                            ReceiverBindQueryTask.RETRY_POLICY, ReceiverBindQueryTask.DELAYS)),
                    List.<TaskRegistration<?>>of(
                            ReceiverBindQueryTask.registration(receiverAccountService::pollBindStatus)));
        }

        @Bean
        @Primary
        SettlementReceiverLookup dbSettlementReceiverLookup(DataSource source) {
            return new DbSettlementReceiverLookup(source);
        }

        @Bean
        SettlementCompensationScanner.CompensationHook stuckReceiverBindCompensationHook(
                ReceiverAccountService receiverAccountService) {
            return receiverAccountService::scanStuckBindApplies;
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
                        "Payment receiver requires the platform merchant (pet.payment.lakala.platform.*)");
            }
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
