package com.petplatform.boot.config;

import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.api.command.PaymentPreparationApi;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.PaymentIdentityApi;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal opt-in only. No public payment endpoint, worker or live channel call at startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"pet.payment.foundation.enabled", "pet.payment.dispatch.enabled"}, havingValue = "true")
@EnableConfigurationProperties(PaymentDispatchConfiguration.DispatchSettings.class)
public class PaymentDispatchConfiguration {
    @ConfigurationProperties(prefix = "pet.payment.dispatch")
    public record DispatchSettings(LakalaHttpClient.Environment environment, String appId,
            String merchantSerial, String merchantPrivateKeyPath, String platformSerial,
            String platformCertificatePath, String parameterKeyPath, String outOrgCode,
            String subject, String requestIp, String notifyUrl, boolean terminalCloseCapability) {
        @Override public String toString() { return "DispatchSettings[redacted]"; }
    }

    @Bean @ConditionalOnMissingBean(PaymentIdentityApi.class)
    PaymentIdentityApi paymentIdentityApi(DataSource source, ScheduleCapacityGuardApi guard) {
        return new PaymentIdentityApiImpl(source, guard);
    }

    @Bean @ConditionalOnMissingBean(PaymentChannel.class)
    PaymentChannel paymentChannel(DispatchSettings settings) {
        try {
            if (settings.environment() == null) throw new IllegalArgumentException();
            String pem = read(settings.merchantPrivateKeyPath(), 16_384);
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            PublicKey publicKey;
            try (var input = Files.newInputStream(Path.of(settings.platformCertificatePath()))) {
                publicKey = CertificateFactory.getInstance("X.509").generateCertificate(input).getPublicKey();
            }
            return new LakalaHttpClient(settings.environment(), new LakalaHttpClient.Credentials(
                    settings.appId(), settings.merchantSerial(), privateKey,
                    settings.platformSerial(), publicKey));
        } catch (Exception failure) {
            throw new IllegalStateException("Payment dispatch credentials or launch configuration unavailable");
        }
    }

    @Bean
    PaymentDispatchService paymentDispatchService(DataSource source, com.petplatform.common.SnowflakeIdGenerator ids, ScheduleCapacityGuardApi guard,
            OrderPaymentFactsApi orders, PaymentIdentityApi identities, PaymentPreparationApi preparation,
            PaymentChannel channel, PaymentNotificationService notifications, DispatchSettings settings,
            PaymentFoundationConfiguration.LakalaSettings lakala, ObjectProvider<Clock> clocks) {
        try {
            byte[] key = Base64.getDecoder().decode(read(settings.parameterKeyPath(), 256).strip());
            if (key.length != 32 || lakala.channelTimeZone() == null) throw new IllegalArgumentException();
            var dispatch = new PaymentDispatchService.Settings(settings.outOrgCode(), settings.subject(),
                    settings.requestIp(), settings.notifyUrl(), ZoneId.of(lakala.channelTimeZone()),
                    new SecretKeySpec(key, "AES"), settings.terminalCloseCapability());
            java.util.Arrays.fill(key, (byte) 0);
            return new PaymentDispatchService(source, ids, guard, orders, identities, preparation,
                    channel, notifications, dispatch, clocks.getIfAvailable(Clock::systemUTC));
        } catch (Exception failure) {
            throw new IllegalStateException("Payment dispatch settings unavailable");
        }
    }

    private static String read(String path, int limit) throws Exception {
        if (path == null || path.isBlank()) throw new IllegalArgumentException();
        try (var input = Files.newInputStream(Path.of(path))) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) throw new IllegalArgumentException();
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
