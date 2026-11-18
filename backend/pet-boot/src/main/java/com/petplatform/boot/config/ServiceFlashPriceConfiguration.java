package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.merchant.api.dto.MerchantStoreDisplayDTO;
import com.petplatform.merchant.api.query.MerchantStoreDisplayApi;
import com.petplatform.merchant.api.query.MerchantStoreDisplayQuery;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceAdminApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceMerchantApiImpl;
import com.petplatform.service.biz.apiimpl.ServiceFlashPriceReservationApiImpl;
import com.petplatform.service.biz.application.FlashPriceAdminService.FlashPriceStoreFactsPort;
import com.petplatform.service.biz.application.FlashPriceEffectiveResolver;
import com.petplatform.service.biz.application.FlashPriceReleaseService;
import com.petplatform.service.biz.event.OrderPaidFlashPriceConsumer;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.task.FlashPriceReleaseTaskRegistration;
import com.petplatform.task.core.AsyncTaskWorker;
import com.petplatform.task.core.JdbcAsyncTaskStatusReader;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.task.core.TaskRetryDelays;
import com.petplatform.task.core.TaskWorkerSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 77 (CCR-SVC-FLASH-PRICE-001) runtime, default OFF via {@code
 * pet.service.flash-price.enabled}. While off, the reservation API is a fail-closed stub
 * (FLASH_PRICE_DISABLED) and the read side never touches the flash tables (no resolver bean);
 * enabling assembles the merchant/admin APIs, the shared effective resolver for the C read side
 * and booking facts, the OrderPaidEvent.v1 consumer (picked up by the outbox dispatcher's
 * ObjectProvider scan — no EventOutboxConfiguration change needed) and the durable
 * FLASH_PRICE_RELEASE_EXPIRED worker plus its resurrection watchdog.
 *
 * <p>Migrations: V31__service_flash_price.sql already lives in db/service-migration and runs with
 * the existing service-write Flyway (svcw001_* guarded, pet.service.command.migration-enabled);
 * this configuration deliberately registers no second migration path.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.service.flash-price", name = "enabled", havingValue = "true")
public class ServiceFlashPriceConfiguration {

    @Bean
    FlashPriceStore flashPriceStore(DataSource source, SnowflakeIdGenerator ids) {
        return new FlashPriceStore(source, ids);
    }

    /** Shared read-side resolver: joins each caller's read/booking transaction. */
    @Bean
    FlashPriceEffectiveResolver flashPriceEffectiveResolver(
            FlashPriceStore store, ObjectProvider<Clock> clock) {
        return new FlashPriceEffectiveResolver(store, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ServiceFlashPriceMerchantApiImpl serviceFlashPriceMerchantApi(
            FlashPriceStore store,
            SnowflakeIdGenerator ids,
            ObjectProvider<com.petplatform.merchant.api.query.MerchantAdmissionQueryApi> admissions,
            ObjectProvider<Clock> clock) {
        return new ServiceFlashPriceMerchantApiImpl(
                store.dataSource(), ids, requireAdmissions(admissions),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ServiceFlashPriceAdminApiImpl serviceFlashPriceAdminApi(
            FlashPriceStore store,
            SnowflakeIdGenerator ids,
            ObjectProvider<MerchantStoreDisplayApi> display,
            ObjectProvider<Clock> clock) {
        MerchantStoreDisplayApi stores = display.getIfAvailable();
        return new ServiceFlashPriceAdminApiImpl(
                store.dataSource(), ids, stores == null ? null : new StoreNameAdapter(stores),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ServiceFlashPriceReservationApiImpl serviceFlashPriceReservationApi(
            FlashPriceStore store, SnowflakeIdGenerator ids, ObjectProvider<Clock> clock) {
        return new ServiceFlashPriceReservationApiImpl(
                store.dataSource(), ids, clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * OrderPaidEvent.v1 consumer (77 §6.2). Registered as an IntegrationEventConsumer bean, the
     * outbox dispatcher's ObjectProvider scan picks it up with no EventOutboxConfiguration edit.
     * The consume guard is resolved lazily so an outbox-less deployment fails closed at dispatch
     * time instead of breaking context startup.
     */
    @Bean
    OrderPaidFlashPriceConsumer orderPaidFlashPriceConsumer(
            FlashPriceStore store,
            ObjectProvider<JdbcOutboxConsumeGuard> guard,
            ObjectProvider<Clock> clock) {
        return new OrderPaidFlashPriceConsumer(
                store,
                (name, event) -> {
                    JdbcOutboxConsumeGuard claims = guard.getIfAvailable();
                    if (claims == null) {
                        throw new IllegalStateException("outbox consume guard is not assembled");
                    }
                    return claims.tryClaim(name, event);
                },
                clock.getIfAvailable(Clock::systemUTC));
    }

    // ------------------------------------------------------------------ release task

    @Bean
    JdbcAsyncTaskSubmitter flashPriceTaskSubmitter(DataSource source, SnowflakeIdGenerator ids) {
        return new JdbcAsyncTaskSubmitter(source, ids);
    }

    @Bean
    FlashPriceReleaseService flashPriceReleaseService(
            FlashPriceStore store, JdbcAsyncTaskSubmitter tasks, ObjectProvider<Clock> clock) {
        return new FlashPriceReleaseService(store, tasks, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    AsyncTaskWorker flashPriceReleaseWorker(
            DataSource source,
            SnowflakeIdGenerator ids,
            FlashPriceReleaseService service,
            ObjectProvider<Clock> clock) {
        return AsyncTaskWorker.create(
                source,
                ids,
                "svc-flash-release-" + UUID.randomUUID(),
                clock.getIfAvailable(Clock::systemUTC),
                TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of(
                        FlashPriceReleaseService.RETRY_POLICY,
                        List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
                                Duration.ofMinutes(1)))),
                List.of(FlashPriceReleaseTaskRegistration.create(service)));
    }

    /**
     * Chain watchdog (77 §6.3 re-entrancy): every 30s it looks at the deterministic next-minute
     * task key; when the row is absent or terminal the round is enqueued again. Handler and
     * watchdog produce byte-identical content, so a live chain is never duplicated — dead chains
     * (DEAD/CANCELED after a restart or an exhausted retry budget) come back within one slot.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    FlashPriceReleaseWatchdog flashPriceReleaseWatchdog(
            DataSource source, FlashPriceReleaseService service, ObjectProvider<Clock> clock) {
        return new FlashPriceReleaseWatchdog(
                new JdbcAsyncTaskStatusReader(source), service,
                clock.getIfAvailable(Clock::systemUTC));
    }

    static final class FlashPriceReleaseWatchdog implements AutoCloseable {
        private final JdbcAsyncTaskStatusReader reader;
        private final FlashPriceReleaseService service;
        private final Clock clock;
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("svc-flash-release-watchdog").factory());

        FlashPriceReleaseWatchdog(
                JdbcAsyncTaskStatusReader reader, FlashPriceReleaseService service, Clock clock) {
            this.reader = Objects.requireNonNull(reader);
            this.service = Objects.requireNonNull(service);
            this.clock = Objects.requireNonNull(clock);
        }

        /** Spring lifecycle: initMethod start, destroyMethod close. */
        public void start() {
            scheduler.scheduleWithFixedDelay(this::ensureNextRound, 5, 30, TimeUnit.SECONDS);
        }

        private void ensureNextRound() {
            try {
                OffsetDateTime slot = FlashPriceReleaseService.nextSlot(clock);
                String status = reader.status(FlashPriceReleaseService.taskKey(slot));
                if (status == null || "SUCCEEDED".equals(status) || "CANCELED".equals(status)
                        || "DEAD".equals(status)) {
                    service.enqueueRound(slot);
                }
            } catch (RuntimeException failure) {
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING,
                                "flash-price release watchdog unavailable: {0}",
                                failure.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            scheduler.shutdown();
        }
    }

    // ------------------------------------------------------------------ adapters

    /** Store-name decoration for the admin list via the merchant display owner (fail-closed). */
    static final class StoreNameAdapter implements FlashPriceStoreFactsPort {
        private final MerchantStoreDisplayApi stores;

        StoreNameAdapter(MerchantStoreDisplayApi stores) {
            this.stores = Objects.requireNonNull(stores);
        }

        @Override
        public String storeName(long storeId) {
            MerchantStoreDisplayDTO dto;
            try {
                dto = stores.getDisplayStore(new MerchantStoreDisplayQuery(
                        Long.toUnsignedString(storeId),
                        new QueryContext("flash-price", OperatorType.PLATFORM_OPERATOR, "0")));
            } catch (ApiException known) {
                if (CommonApiCodes.NOT_FOUND.equals(known.code())) return null;
                throw unavailable("merchant store display facts are unavailable");
            } catch (RuntimeException failure) {
                throw unavailable("merchant store display facts are unavailable");
            }
            return dto == null ? null : dto.storeName();
        }
    }

    private static com.petplatform.merchant.api.query.MerchantAdmissionQueryApi requireAdmissions(
            ObjectProvider<com.petplatform.merchant.api.query.MerchantAdmissionQueryApi> admissions) {
        com.petplatform.merchant.api.query.MerchantAdmissionQueryApi api =
                admissions.getIfAvailable();
        if (api == null) {
            throw new IllegalStateException(
                    "flash-price merchant face requires the merchant admission owner");
        }
        return api;
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
