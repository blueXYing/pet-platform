package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.command.PaymentCenterApi;
import com.petplatform.payment.api.query.PaymentCenterFactsApi;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.center.PaymentCallbackRouter;
import com.petplatform.payment.biz.center.PaymentCenterBizPolicy;
import com.petplatform.payment.biz.center.PaymentCenterFactsApiImpl;
import com.petplatform.payment.biz.center.PaymentCenterQueryCoordinator;
import com.petplatform.payment.biz.center.PaymentCenterReceiptWriter;
import com.petplatform.payment.biz.center.PaymentCenterService;
import com.petplatform.payment.biz.center.demo.DemoPaymentConfirmConsumer;
import com.petplatform.payment.biz.center.demo.DemoPaymentService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.task.PaymentCenterQueryTask;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 75 §8 payment center (CCR-PAY-CENTER-001 stage 1). Every switch defaults to false and no HTTP
 * (payment center, DEMO or payment callback) is registered. Missing dependencies fail startup closed.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentCenterConfiguration {
    static final String REQUIRES_FOUNDATION = "Payment center requires pet.payment.foundation.enabled and pet.payment.dispatch.enabled";
    static final String REQUIRES_CENTER = "Payment center worker and DEMO require pet.payment.center.enabled";
    static final String DEMO_REQUIRES_OUTBOX = "Payment center DEMO requires the transactional outbox (pet.outbox.enabled)";
    static final String DEMO_FORBIDDEN_IN_PRODUCTION = "Payment center DEMO is forbidden when pet.payment.dispatch.environment=PRODUCTION";

    @Bean InitializingBean paymentCenterConfigurationGuard(
            @Value("${pet.payment.center.enabled:false}") boolean center,
            @Value("${pet.payment.center.worker.enabled:false}") boolean worker,
            @Value("${pet.payment.center.demo.enabled:false}") boolean demo,
            @Value("${pet.payment.foundation.enabled:false}") boolean foundation,
            @Value("${pet.payment.dispatch.enabled:false}") boolean dispatch,
            @Value("${pet.outbox.enabled:true}") boolean outbox,
            @Value("${pet.payment.dispatch.environment:}") String environment) {
        return () -> check(center, worker, demo, foundation, dispatch, outbox, environment);
    }

    /** Pure flag matrix, unit-tested offline. */
    static void check(boolean center, boolean worker, boolean demo, boolean foundation, boolean dispatch,
            boolean outbox, String environment) {
        if ((worker || demo) && !center) throw new IllegalStateException(REQUIRES_CENTER);
        if (center && !(foundation && dispatch)) throw new IllegalStateException(REQUIRES_FOUNDATION);
        if (demo && !outbox) throw new IllegalStateException(DEMO_REQUIRES_OUTBOX);
        // Anything that is not explicitly SIT is treated as production for the DEMO gate.
        if (demo && !LakalaHttpClient.Environment.SIT.name().equals(environment))
            throw new IllegalStateException(DEMO_FORBIDDEN_IN_PRODUCTION);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("${pet.payment.center.enabled:false} && ${pet.payment.foundation.enabled:false}"
            + " && ${pet.payment.dispatch.enabled:false}")
    static class Runtime {
        @Bean PaymentCenterBizPolicy paymentCenterBizPolicy(
                @Value("${pet.payment.center.demo.enabled:false}") boolean demo,
                @Value("${pet.payment.center.demo.max-amount:0.01}") String maxAmount) {
            if (!demo) return PaymentCenterBizPolicy.none();
            try { return PaymentCenterBizPolicy.demoOnly(new BigDecimal(maxAmount)); }
            catch (RuntimeException invalid) {
                throw new IllegalStateException("pet.payment.center.demo.max-amount must be a positive amount with at most 2 decimals");
            }
        }

        @Bean PaymentDispatchService.Settings paymentCenterChannelSettings(
                PaymentDispatchConfiguration.DispatchSettings dispatch,
                PaymentFoundationConfiguration.LakalaSettings lakala) {
            try {
                byte[] key = Base64.getDecoder().decode(read(dispatch.parameterKeyPath(), 256).strip());
                if (key.length != 32 || lakala.channelTimeZone() == null || lakala.channelTimeZone().isBlank())
                    throw new IllegalArgumentException();
                var settings = new PaymentDispatchService.Settings(dispatch.outOrgCode(), dispatch.subject(),
                        dispatch.requestIp(), dispatch.notifyUrl(), ZoneId.of(lakala.channelTimeZone()),
                        new SecretKeySpec(key, "AES"), dispatch.terminalCloseCapability());
                java.util.Arrays.fill(key, (byte) 0);
                return settings;
            } catch (Exception failure) {
                throw new IllegalStateException("Payment center channel settings unavailable");
            }
        }

        @Bean PaymentCenterReceiptWriter paymentCenterReceiptWriter(DataSource source, SnowflakeIdGenerator ids,
                PaymentReceiptVerifier verifier, IntegrationEventPublisher publisher,
                PaymentMerchantBindings bindings, PaymentDispatchService.Settings paymentCenterChannelSettings) {
            return new PaymentCenterReceiptWriter(source, ids, verifier, publisher,
                    paymentCenterChannelSettings.channelTimeZone(), platform(bindings).merchantNo());
        }

        @Bean PaymentCenterApi paymentCenterApi(DataSource source, SnowflakeIdGenerator ids,
                PaymentCenterBizPolicy policy, PaymentMerchantBindings bindings, PaymentIdentityApi identities,
                PaymentChannel channel, PaymentCenterReceiptWriter receipts,
                PaymentDispatchService.Settings paymentCenterChannelSettings,
                PaymentFoundationConfiguration.LakalaSettings lakala, ObjectProvider<Clock> clocks) {
            platform(bindings);
            String settleType = lakala.settleType();
            if (!"0".equals(settleType) && !"1".equals(settleType))
                throw new IllegalStateException("pet.payment.lakala.settle-type must be 0 or 1");
            return new PaymentCenterService(source, ids, policy, bindings, identities, channel, receipts,
                    new JdbcAsyncTaskSubmitter(source, ids), paymentCenterChannelSettings, settleType,
                    clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean PaymentCenterFactsApi paymentCenterFactsApi(DataSource source) {
            return new PaymentCenterFactsApiImpl(source);
        }

        /** Internal routing entry for a future callback controller; no HTTP is registered here. */
        @Bean PaymentCallbackRouter paymentCallbackRouter(DataSource source, PaymentCenterReceiptWriter receipts,
                PaymentNotificationService orders) {
            return new PaymentCallbackRouter(source, receipts, orders);
        }

        @Bean @ConditionalOnProperty(prefix = "pet.payment.center.worker", name = "enabled", havingValue = "true")
        PaymentCenterQueryCoordinator paymentCenterQueryCoordinator(DataSource source, PaymentChannel channel,
                PaymentCenterReceiptWriter receipts, PaymentDispatchService.Settings paymentCenterChannelSettings,
                ObjectProvider<Clock> clocks) {
            return new PaymentCenterQueryCoordinator(source, channel, receipts, paymentCenterChannelSettings,
                    clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @ConditionalOnProperty(prefix = "pet.payment.center.worker", name = "enabled", havingValue = "true")
        AsyncTaskWorker paymentCenterQueryWorker(DataSource source, SnowflakeIdGenerator ids,
                PaymentCenterQueryCoordinator coordinator, ObjectProvider<Clock> clocks) {
            return AsyncTaskWorker.create(source, ids, "payment-center-query-" + UUID.randomUUID(),
                    clocks.getIfAvailable(Clock::systemUTC), TaskWorkerSettings.defaults(),
                    new TaskRetryDelays(Map.of(PaymentCenterQueryTask.RETRY_POLICY, PaymentCenterQueryTask.DELAYS)),
                    List.<TaskRegistration<?>>of(PaymentCenterQueryTask.registration(coordinator)));
        }

        @Bean @ConditionalOnProperty(prefix = "pet.payment.center.demo", name = "enabled", havingValue = "true")
        DemoPaymentService demoPaymentService(DataSource source, SnowflakeIdGenerator ids, PaymentCenterApi center) {
            return new DemoPaymentService(source, ids, center);
        }

        @Bean @ConditionalOnProperty(prefix = "pet.payment.center.demo", name = "enabled", havingValue = "true")
        DemoPaymentConfirmConsumer demoPaymentConfirmConsumer(DataSource source, JdbcOutboxConsumeGuard claims,
                PaymentCenterFactsApi facts) {
            return new DemoPaymentConfirmConsumer(source, claims, facts);
        }

        private static PaymentMerchantBindings.Binding platform(PaymentMerchantBindings bindings) {
            try {
                var binding = bindings.requirePlatform();
                if (binding == null || binding.merchantNo() == null || binding.merchantNo().isBlank())
                    throw new IllegalArgumentException();
                return binding;
            } catch (RuntimeException missing) {
                throw new IllegalStateException("Payment center requires the platform merchant (pet.payment.lakala.platform.*)");
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
}
