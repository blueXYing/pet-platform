package com.petplatform.boot.config;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.ReconcileAdminApi;
import com.petplatform.payment.api.query.SubsidyReportQueryApi;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsReconcileHttpClient;
import com.petplatform.payment.biz.settlement.LakalaSettlementReconcileChannel;
import com.petplatform.payment.biz.settlement.SettlementCompensationScanner;
import com.petplatform.payment.biz.settlement.SettlementReconcileChannel;
import com.petplatform.payment.biz.settlement.SettlementReconcileService;
import com.petplatform.payment.biz.settlement.SubsidyReportService;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Contract 80 Stage 6 configuration: compensation scanner, Lakala 1879/1877 daily reconciliation,
 * and strictly read-only platform subsidy payable report.
 *
 * <p>Wired directly under the unified payment base switch {@code pet.payment.enabled=true}
 * (per PR #234 single-switch architecture).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentReconcileConfiguration.ReconcileSettings.class)
public class PaymentReconcileConfiguration {
    static final String INVALID_DAILY_RUN_TIME = "pet.payment.reconcile.daily-run-time must be valid ISO local time (HH:mm)";
    static final String INVALID_SCAN_INTERVAL = "pet.payment.reconcile.scan-interval-minutes must be in [0, 1440]";

    @ConfigurationProperties(prefix = "pet.payment.reconcile")
    public record ReconcileSettings(
            String dailyRunTime,
            long scanIntervalMinutes) {

        public long effectiveScanIntervalMinutes() {
            return scanIntervalMinutes <= 0 ? 5L : scanIntervalMinutes;
        }

        public LocalTime parsedDailyRunTime() {
            String raw = dailyRunTime == null || dailyRunTime.isBlank() ? "03:00" : dailyRunTime.trim();
            try {
                return LocalTime.parse(raw);
            } catch (DateTimeParseException invalid) {
                throw new IllegalStateException(INVALID_DAILY_RUN_TIME);
            }
        }
    }

    @Bean
    InitializingBean paymentReconcileConfigurationGuard(
            @Value("${pet.payment.enabled:false}") boolean paymentEnabled,
            ReconcileSettings settings) {
        return () -> check(paymentEnabled, settings);
    }

    /** Pure schedule check, unit-tested offline. */
    static void check(boolean paymentEnabled, ReconcileSettings settings) {
        if (!paymentEnabled) return;
        if (settings.scanIntervalMinutes() < 0 || settings.scanIntervalMinutes() > 1440) {
            throw new IllegalStateException(INVALID_SCAN_INTERVAL);
        }
        settings.parsedDailyRunTime();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
    static class Runtime {

        @Bean @ConditionalOnMissingBean(SettlementReconcileChannel.class)
        SettlementReconcileChannel settlementReconcileChannel(
                PaymentDispatchConfiguration.DispatchSettings dispatch,
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
                return new LakalaSettlementReconcileChannel(
                        new LakalaSacsReconcileHttpClient(dispatch.environment(), credentials),
                        clocks.getIfAvailable(Clock::systemUTC));
            } catch (Exception failure) {
                throw new IllegalStateException("Settlement reconcile credentials or launch configuration unavailable");
            }
        }

        @Bean
        SubsidyReportQueryApi subsidyReportQueryApi(DataSource source) {
            return new SubsidyReportService(source);
        }

        @Bean
        ReconcileAdminApi reconcileAdminApi(
                DataSource source,
                SnowflakeIdGenerator ids,
                SettlementReconcileChannel channel,
                PaymentMerchantBindings bindings,
                PaymentFoundationConfiguration.LakalaSettings lakala,
                ObjectProvider<Clock> clocks) {
            return new SettlementReconcileService(
                    source,
                    ids,
                    channel,
                    requirePlatformMerchantNo(bindings),
                    requireChannelZone(lakala),
                    clocks.getIfAvailable(Clock::systemUTC));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        SettlementCompensationScanner settlementCompensationScanner(
                DataSource source,
                SnowflakeIdGenerator ids,
                ObjectProvider<SettlementCompensationScanner.CompensationHook> hooks,
                ReconcileSettings settings,
                ObjectProvider<Clock> clocks) {
            return new SettlementCompensationScanner(
                    source,
                    ids,
                    clocks.getIfAvailable(Clock::systemUTC),
                    hooks.orderedStream().toList(),
                    Duration.ofMinutes(settings.effectiveScanIntervalMinutes()),
                    100);
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        DailyReconcileScheduler dailyReconcileScheduler(
                ReconcileAdminApi reconcileAdminApi,
                PaymentFoundationConfiguration.LakalaSettings lakala,
                ReconcileSettings settings,
                ObjectProvider<Clock> clocks) {
            return new DailyReconcileScheduler(
                    reconcileAdminApi,
                    requireChannelZone(lakala),
                    settings.parsedDailyRunTime(),
                    clocks.getIfAvailable(Clock::systemUTC));
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
                        "Payment reconcile requires the platform merchant (pet.payment.lakala.platform.*)");
            }
        }

        private static ZoneId requireChannelZone(PaymentFoundationConfiguration.LakalaSettings lakala) {
            String zone = lakala == null ? null : lakala.channelTimeZone();
            if (zone == null || zone.isBlank()) {
                throw new IllegalStateException("Payment reconcile requires pet.payment.lakala.channel-time-zone");
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

    /** Daily T-1 SACS reconciliation trigger (scheduled automatically under pet.payment.enabled=true). */
    public static final class DailyReconcileScheduler implements AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(DailyReconcileScheduler.class);

        private final ReconcileAdminApi reconcileAdminApi;
        private final ZoneId channelTimeZone;
        private final LocalTime runTime;
        private final Clock clock;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("settlement-daily-reconcile").factory());

        DailyReconcileScheduler(ReconcileAdminApi reconcileAdminApi, ZoneId channelTimeZone,
                LocalTime runTime, Clock clock) {
            this.reconcileAdminApi = Objects.requireNonNull(reconcileAdminApi, "reconcileAdminApi");
            this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channelTimeZone");
            this.runTime = Objects.requireNonNull(runTime, "runTime");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public void start() {
            if (!started.compareAndSet(false, true)) return;
            scheduleNext();
        }

        private void scheduleNext() {
            ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), channelTimeZone);
            ZonedDateTime next = now.with(runTime);
            if (!next.isAfter(now)) {
                next = next.plusDays(1);
            }
            long delayMillis = Math.max(1_000L, Duration.between(now, next).toMillis());
            scheduler.schedule(this::runYesterdayAndReschedule, delayMillis, TimeUnit.MILLISECONDS);
        }

        private void runYesterdayAndReschedule() {
            try {
                LocalDate yesterday = LocalDate.ofInstant(clock.instant(), channelTimeZone).minusDays(1);
                var ctx = new CommandContext("daily-reconcile-" + yesterday, "daily-reconcile",
                        OperatorType.SYSTEM, null, "SCHEDULER");
                reconcileAdminApi.runDailyReconcile(yesterday, ctx);
            } catch (Throwable failure) {
                LOG.error("Scheduled daily SACS reconciliation failed", failure);
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduleNext();
                }
            }
        }

        @Override
        public void close() {
            scheduler.shutdown();
        }
    }
}
