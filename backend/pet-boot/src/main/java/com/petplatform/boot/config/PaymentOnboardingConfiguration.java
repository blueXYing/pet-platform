package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantAcquiringFactDTO;
import com.petplatform.merchant.api.query.MerchantAcquiringFactsApi;
import com.petplatform.merchant.biz.apiimpl.MerchantAcquiringFactsApiImpl;
import com.petplatform.payment.biz.infrastructure.provider.LakalaMerchantOnboardingChannel;
import com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingNotifications;
import com.petplatform.payment.biz.infrastructure.provider.LakalaV2HttpClient;
import com.petplatform.payment.biz.settlement.MerchantOnboardingChannel;
import com.petplatform.payment.biz.settlement.MerchantOnboardingEligibilityPort;
import com.petplatform.payment.biz.settlement.MerchantOnboardingService;
import com.petplatform.payment.biz.settlement.MerchantOnboardingSettingsParser;
import com.petplatform.payment.biz.settlement.MerchantOnboardingTasks;
import com.petplatform.payment.biz.settlement.OnboardingAttachmentUploader;
import com.petplatform.payment.biz.settlement.ReceiverAttachmentProvider;
import com.petplatform.payment.biz.settlement.ReceiverSensitiveCipher;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.UploadPrivateAssetCommand;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.UploadPrivateAssetResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.time.Clock;
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

/**
 * Merchant acquiring onboarding wiring (CCR-PAY-ONBOARD-001, contract 81). Wired directly under the
 * unified payment base switch {@code pet.payment.enabled=true} (per PR #234 single-switch architecture):
 * no additional switch exists. Platform parameters come from {@code pet.payment.onboarding.*}; blank values
 * are allowed at boot and only make "发起开通" fail closed, but malformed values stop startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentOnboardingConfiguration.OnboardingProperties.class)
public class PaymentOnboardingConfiguration {
    static final String INVALID_SETTINGS =
            "pet.payment.onboarding.* contains malformed values (fee-rates-json / mcc-by-merchant-type-json"
                    + " / district-by-city-json / lowest-ratio-percent / settlement.org-code)";

    /** Every value is bound from the environment in application.yml; none is secret. */
    @ConfigurationProperties(prefix = "pet.payment.onboarding")
    public record OnboardingProperties(
            String notifyBaseUrl,
            String posType,
            String merBusiContent,
            String settlePeriod,
            String feeRatesJson,
            String mccDefault,
            String mccByMerchantTypeJson,
            String districtByCityJson,
            String lowestRatioPercent,
            String cooperationAgreementName,
            String platformCompanyName,
            String ecOrgId) {
        MerchantOnboardingSettingsParser.Raw raw() {
            return new MerchantOnboardingSettingsParser.Raw(notifyBaseUrl, posType, merBusiContent, settlePeriod,
                    feeRatesJson, mccDefault, mccByMerchantTypeJson, districtByCityJson, lowestRatioPercent,
                    cooperationAgreementName, platformCompanyName, ecOrgId);
        }
    }

    @Bean
    InitializingBean paymentOnboardingConfigurationGuard(
            @Value("${pet.payment.enabled:false}") boolean paymentEnabled,
            OnboardingProperties properties) {
        return () -> check(paymentEnabled, properties);
    }

    /** Pure startup guard check, unit-tested offline: present values must parse; blank values are fine. */
    static void check(boolean paymentEnabled, OnboardingProperties properties) {
        if (!paymentEnabled) return;
        try {
            MerchantOnboardingSettingsParser.parse(properties.raw(), null, null);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(INVALID_SETTINGS);
        }
    }

    static MerchantOnboardingService.Settings settings(OnboardingProperties properties,
            PaymentSettlementConfiguration.SettlementSettings settlement) {
        return MerchantOnboardingSettingsParser.parse(properties.raw(),
                settlement == null ? null : settlement.orgCode(),
                settlement == null ? null : settlement.normalizedPlatformReceiverNo());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
    static class Runtime {

        @Bean
        @ConditionalOnMissingBean(MerchantAcquiringFactsApi.class)
        MerchantAcquiringFactsApi merchantAcquiringFactsApi(DataSource source) {
            return new MerchantAcquiringFactsApiImpl(source);
        }

        /** Merchant facts reach the payment module through the merchant API only (no cross-module table access). */
        @Bean
        MerchantOnboardingEligibilityPort merchantOnboardingEligibilityPort(MerchantAcquiringFactsApi facts) {
            return merchantId -> {
                MerchantAcquiringFactDTO fact = facts.find(merchantId);
                return fact == null ? null : new MerchantOnboardingEligibilityPort.MerchantOnboardingFacts(
                        fact.merchantId(), fact.ownerUserId(), fact.merchantName(), fact.merchantStatus(),
                        fact.merchantTypeCode(), fact.cityCode(), fact.applicationStatus(), fact.signingStatus());
            };
        }

        @Bean
        @ConditionalOnMissingBean(OnboardingAttachmentUploader.class)
        OnboardingAttachmentUploader onboardingAttachmentUploader(ObjectProvider<PrivateAssetApi> privateAssets) {
            PrivateAssetApi api = privateAssets.getIfAvailable();
            if (api == null) {
                return OnboardingAttachmentUploader.failClosed();
            }
            return (ownerUserId, mediaType, bytes, content, context) -> {
                UploadPrivateAssetResult result = api.uploadOperatorAttachment(new UploadPrivateAssetCommand(
                        ownerUserId, PrivateAssetApi.MERCHANT_ONBOARDING_ATTACHMENT, mediaType, bytes, content,
                        context));
                if (result == null || result.assetId() == null || result.assetId().isBlank()) {
                    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "私有素材存储未返回素材编号");
                }
                return result.assetId();
            };
        }

        @Bean
        @ConditionalOnMissingBean(MerchantOnboardingChannel.class)
        MerchantOnboardingChannel merchantOnboardingChannel(
                ObjectProvider<LakalaV2HttpClient> clients, ObjectProvider<Clock> clocks) {
            // The signed client is only wired when the receiver channel is not replaced; never let its
            // absence stop the application from starting (onboarding then answers 503).
            LakalaV2HttpClient v2 = clients.getIfAvailable();
            if (v2 == null) {
                return MerchantOnboardingChannel.failClosed();
            }
            return new LakalaMerchantOnboardingChannel(
                    new com.petplatform.payment.biz.infrastructure.provider.LakalaOnboardingHttpClient(v2),
                    v2, clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        @ConditionalOnMissingBean(MerchantOnboardingService.NotificationVerifier.class)
        MerchantOnboardingService.NotificationVerifier merchantOnboardingNotificationVerifier(
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            String path = lakala == null ? null : lakala.notificationCertificatePath();
            if (path == null || path.isBlank()) {
                return LakalaOnboardingNotifications.failClosed();
            }
            try (var input = Files.newInputStream(Path.of(path))) {
                return new LakalaOnboardingNotifications(
                        CertificateFactory.getInstance("X.509").generateCertificate(input).getPublicKey());
            } catch (Exception failed) {
                throw new IllegalStateException("Configured onboarding notification certificate is unavailable");
            }
        }

        @Bean
        @DependsOn("paymentOnboardingConfigurationGuard")
        MerchantOnboardingService merchantOnboardingService(
                DataSource source,
                SnowflakeIdGenerator ids,
                ReceiverSensitiveCipher cipher,
                ReceiverAttachmentProvider attachmentProvider,
                OnboardingAttachmentUploader uploader,
                MerchantOnboardingChannel channel,
                MerchantOnboardingEligibilityPort merchants,
                MerchantOnboardingService.NotificationVerifier notificationVerifier,
                OnboardingProperties properties,
                PaymentSettlementConfiguration.SettlementSettings settlement,
                ObjectProvider<Clock> clocks) {
            return new MerchantOnboardingService(
                    source, ids, cipher, attachmentProvider, uploader, channel,
                    new JdbcAsyncTaskSubmitter(source, ids), merchants, notificationVerifier,
                    settings(properties, settlement), clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @DependsOn("paymentOnboardingConfigurationGuard")
        AsyncTaskWorker merchantOnboardingWorker(
                DataSource source,
                SnowflakeIdGenerator ids,
                MerchantOnboardingService merchantOnboardingService,
                ObjectProvider<Clock> clocks) {
            return AsyncTaskWorker.create(
                    source, ids, "payment-merchant-onboarding-" + UUID.randomUUID(),
                    clocks.getIfAvailable(Clock::systemUTC),
                    TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of(
                            MerchantOnboardingTasks.ADVANCE_POLICY, MerchantOnboardingTasks.ADVANCE_DELAYS,
                            MerchantOnboardingTasks.POLL_POLICY, MerchantOnboardingTasks.POLL_DELAYS)),
                    List.<TaskRegistration<?>>of(
                            MerchantOnboardingTasks.advanceRegistration(merchantOnboardingService::advanceStep),
                            MerchantOnboardingTasks.pollRegistration(merchantOnboardingService::pollStep)));
        }
    }
}
