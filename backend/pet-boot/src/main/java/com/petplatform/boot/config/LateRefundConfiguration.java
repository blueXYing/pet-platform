package com.petplatform.boot.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.order.biz.apiimpl.OrderLatePaymentFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderLateRefundProjectionConsumer;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.payment.api.query.PaymentRefundResultFactsApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.application.LakalaPaymentRefundChannel;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.payment.biz.application.PaymentRefundService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaRefundHttpClient;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import com.petplatform.task.core.TaskDatabaseClock;

/** Internal late-payment refund composition. All execution and live provider beans are opt-in. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"pet.refund.late.enabled", "pet.payment.foundation.enabled"},
        havingValue = "true")
@EnableConfigurationProperties(PaymentDispatchConfiguration.DispatchSettings.class)
public class LateRefundConfiguration {
    private static final String SUBMIT = "REFUND_SUBMIT";
    private static final String QUERY = "REFUND_CHANNEL_QUERY";
    /** Refund UNKNOWN polling starts no sooner than the provider's 30-second query floor. */
    private static final List<Duration> REFUND_RETRY_DELAYS = List.of(
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofMinutes(60));
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    @Bean OrderLatePaymentFactsApi orderLatePaymentFactsApi(DataSource source,
            ScheduleCapacityGuardApi guard, ReservationExpiryApi expiry) {
        return new OrderLatePaymentFactsApiImpl(source, guard, expiry);
    }

    @Bean LateRefundService lateRefundService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderLatePaymentFactsApi orders,
            PaymentSuccessFactsApi payments, IntegrationEventPublisher outbox) {
        return new LateRefundService(source, ids, guard, orders, payments, outbox);
    }

    @Bean PaymentRefundResultFactsApi paymentRefundResultFactsApi(DataSource source,
            ScheduleCapacityGuardApi guard) {
        return new PaymentRefundResultFactsApiImpl(source, guard);
    }

    @Bean @ConditionalOnMissingBean(PaymentRefundChannel.class)
    PaymentRefundChannel paymentRefundChannel(PaymentDispatchConfiguration.DispatchSettings settings,
            ObjectProvider<Clock> clocks) {
        try {
            if (settings.environment() == null) throw new IllegalArgumentException();
            String pem = read(settings.merchantPrivateKeyPath(), 16_384);
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            PublicKey platformKey;
            try (var input = Files.newInputStream(Path.of(settings.platformCertificatePath()))) {
                platformKey = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input).getPublicKey();
            }
            var credentials = new LakalaHttpClient.Credentials(settings.appId(),
                    settings.merchantSerial(), privateKey, settings.platformSerial(), platformKey);
            return new LakalaPaymentRefundChannel(new LakalaRefundHttpClient(
                    settings.environment(), credentials), clocks.getIfAvailable(Clock::systemUTC));
        } catch (Exception failure) {
            throw new IllegalStateException("Refund provider credentials or launch configuration unavailable");
        }
    }

    @Bean PaymentRefundApi paymentRefundApi(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderLatePaymentFactsApi orders,
            PaymentSuccessFactsApi payments, LateRefundService refunds,
            PaymentRefundChannel channel, PaymentDispatchConfiguration.DispatchSettings dispatch,
            PaymentFoundationConfiguration.LakalaSettings lakala, ObjectProvider<Clock> clocks) {
        try {
            String zone = lakala.channelTimeZone();
            if (zone == null || zone.isBlank()) throw new IllegalArgumentException();
            var settings = new PaymentRefundService.Settings(dispatch.requestIp(),
                    dispatch.notifyUrl(), ZoneId.of(zone));
            return new PaymentRefundService(source, ids, guard, orders, payments, refunds,
                    channel, settings, clocks.getIfAvailable(Clock::systemUTC));
        } catch (Exception failure) {
            throw new IllegalStateException("Refund execution settings unavailable");
        }
    }

    @Bean RefundExecutionService refundExecutionService(LateRefundService refunds,
            PaymentRefundApi payment, PaymentRefundResultFactsApi results) {
        return new RefundExecutionService(refunds, payment, results);
    }

    @Bean OrderLateRefundProjectionConsumer orderLateRefundProjectionConsumer(DataSource source,
            SnowflakeIdGenerator ids, ScheduleCapacityGuardApi guard,
            ReservationExpiryApi expiry, LateRefundService refunds) {
        return new OrderLateRefundProjectionConsumer(source, ids, guard, expiry, refunds);
    }

    @Bean @ConditionalOnProperty(prefix = "pet.refund.late.worker", name = "enabled", havingValue = "true")
    TaskRegistration<RefundPayload> refundSubmitRegistration(RefundExecutionService execution,
            DataSource source) {
        return registration(SUBMIT, execution, source);
    }

    @Bean @ConditionalOnProperty(prefix = "pet.refund.late.worker", name = "enabled", havingValue = "true")
    TaskRegistration<RefundPayload> refundQueryRegistration(RefundExecutionService execution,
            DataSource source) {
        return registration(QUERY, execution, source);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pet.refund.late.worker", name = "enabled", havingValue = "true")
    AsyncTaskWorker lateRefundWorker(DataSource source, SnowflakeIdGenerator ids,
            @Qualifier("refundSubmitRegistration") TaskRegistration<RefundPayload> refundSubmitRegistration,
            @Qualifier("refundQueryRegistration") TaskRegistration<RefundPayload> refundQueryRegistration,
            ObjectProvider<Clock> clocks) {
        return AsyncTaskWorker.create(source, ids, "late-refund-" + UUID.randomUUID(),
                clocks.getIfAvailable(Clock::systemUTC), TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_CHANNEL", REFUND_RETRY_DELAYS)),
                List.of(refundSubmitRegistration, refundQueryRegistration));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pet.refund.late.worker", name = "enabled", havingValue = "true")
    DeadTaskReconciler lateRefundDeadTaskReconciler(RefundExecutionService execution) {
        return new DeadTaskReconciler(execution);
    }

    /** Public for an offline TaskRegistration test; the worker sees only these two types. */
    public static TaskRegistration<RefundPayload> registration(String type,
            RefundExecutionService execution, DataSource source) {
        if (!SUBMIT.equals(type) && !QUERY.equals(type)) throw new IllegalArgumentException();
        Objects.requireNonNull(execution);
        var databaseClock = new TaskDatabaseClock(Objects.requireNonNull(source));
        return new TaskRegistration<>(new TaskHandler<>() {
            @Override public String taskType() { return type; }
            @Override public TaskExecutionResult execute(com.petplatform.task.core.TaskExecutionContext task,
                    RefundPayload payload) {
                var result = execution.execute(payload.refundOrderId(), payload.storeId(),
                        QUERY.equals(type), task.traceId());
                if (result.done()) return new TaskExecutionResult.Success("REFUND_COORDINATED");
                OffsetDateTime next = result.nextQueryAt();
                if (next == null) throw new IllegalStateException("Refund query deadline absent");
                LocalDateTime dbNow = databaseClock.now();
                Duration deadline = Duration.between(dbNow.atOffset(ZoneOffset.UTC), next)
                        .plusMillis(250);
                Duration backoff = REFUND_RETRY_DELAYS.get(Math.min(payload.retryCount(),
                        REFUND_RETRY_DELAYS.size() - 1));
                Duration delay = deadline.compareTo(backoff) > 0 ? deadline : backoff;
                return new TaskExecutionResult.Retry("REFUND_QUERY_PENDING", delay);
            }
        }, lease -> decode(type, lease), lease -> "TASK:" + lease.taskKey());
    }

    private static RefundPayload decode(String type, TaskLease lease) {
        try {
            JsonNode node = JSON.readTree(lease.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("refundOrderId", "storeId", "bindingVersion"))
                    || !node.path("refundOrderId").isTextual()
                    || !node.path("storeId").isTextual()
                    || !node.path("bindingVersion").isIntegralNumber()
                    || !node.path("bindingVersion").canConvertToLong()
                    || node.path("bindingVersion").longValue() != 0) throw new IllegalArgumentException();
            String refund = node.path("refundOrderId").textValue();
            String store = node.path("storeId").textValue();
            long refundId = IDS.fromApi(refund);
            IDS.fromApi(store);
            if (lease.bizId() != refundId || !Objects.equals(lease.expectedVersion(), 0L)
                    || !type.equals(lease.taskType())
                    || lease.retryCount() < 0
                    || !"REFUND_CHANNEL".equals(lease.retryPolicy())
                    || !(type + ":" + refund + ":0").equals(lease.taskKey()))
                throw new IllegalArgumentException();
            return new RefundPayload(refund, store, lease.retryCount());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid late refund task generation");
        }
    }

    public record RefundPayload(String refundOrderId, String storeId, int retryCount) {
        public RefundPayload(String refundOrderId, String storeId) {
            this(refundOrderId, storeId, 0);
        }
    }

    public static final class DeadTaskReconciler implements AutoCloseable {
        private static final System.Logger LOG = System.getLogger(DeadTaskReconciler.class.getName());
        private final RefundExecutionService execution;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("late-refund-reconcile").factory());
        private boolean started;

        DeadTaskReconciler(RefundExecutionService execution) { this.execution = execution; }
        public synchronized void start() {
            if (started) return;
            started = true;
            scheduler.scheduleWithFixedDelay(() -> {
                try { execution.reconcileDeadTasks(); }
                catch (RuntimeException failed) {
                    LOG.log(System.Logger.Level.WARNING, "Late refund reconciliation failed: {0}",
                            failed.getClass().getSimpleName());
                }
            }, 60, 60, TimeUnit.SECONDS);
        }
        @Override public synchronized void close() { scheduler.shutdown(); }
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
