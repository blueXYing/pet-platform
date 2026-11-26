package com.petplatform.payment.biz.settlement;

import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentReconcileStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.StuckInstructionScanRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReconcileMapper.WaitingOrderScanRow;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskRecoverer;
import com.petplatform.task.core.JdbcAsyncTaskStatusReader;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 6 periodic settlement compensation scanner (default 5 minutes).
 *
 * <p>Built-in scans:
 * <ol>
 *   <li>Overdue {@code settlement_order(WAITING)} rows ({@code eligible_at <= now - 2 minutes})
 *       whose {@code SETTLEMENT_DUE:{id}:{round}} task is missing or terminal -> safely recovered
 *       via {@link JdbcAsyncTaskRecoverer} (UTC millis precision).</li>
 *   <li>Stuck {@code settlement_instruction(SENT/PROCESSING)} rows ({@code updated_at <= now - 5 minutes})
 *       whose {@code SETTLEMENT_CHANNEL_QUERY:{instructionId}} task is missing or terminal -> safely
 *       recovered via {@link JdbcAsyncTaskRecoverer} without ever creating a duplicate instruction.</li>
 *   <li>Extensible {@link CompensationHook} list so Stage 4/5 hooks can plug in cleanly.</li>
 * </ol>
 */
public final class SettlementCompensationScanner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementCompensationScanner.class);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(5);
    private static final Duration DUE_GRACE = Duration.ofMinutes(2);
    private static final Duration STUCK_INSTRUCTION_GRACE = Duration.ofMinutes(5);
    private static final int DEFAULT_BATCH_LIMIT = 100;
    private static final Set<String> ALIVE_TASK_STATES = Set.of("READY", "RUNNING", "RETRY_WAIT");

    @FunctionalInterface
    public interface CompensationHook {
        int scan();
    }

    private final PaymentReconcileStore store;
    private final JdbcAsyncTaskStatusReader taskReader;
    private final JdbcAsyncTaskRecoverer taskRecoverer;
    private final Clock clock;
    private final List<CompensationHook> hooks;
    private final Duration scanInterval;
    private final int batchLimit;
    private final TransactionTemplate tx;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = newScheduler();

    private static ScheduledExecutorService newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("settlement-compensation-scanner").factory());
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public SettlementCompensationScanner(
            DataSource source,
            SnowflakeIdGenerator ids,
            Clock clock,
            List<CompensationHook> hooks) {
        this(source, ids, clock, hooks, DEFAULT_SCAN_INTERVAL, DEFAULT_BATCH_LIMIT);
    }

    public SettlementCompensationScanner(
            DataSource source,
            SnowflakeIdGenerator ids,
            Clock clock,
            List<CompensationHook> hooks,
            Duration scanInterval,
            int batchLimit) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
        if (scanInterval == null || scanInterval.isZero() || scanInterval.isNegative()) {
            throw new IllegalArgumentException("scanInterval must be positive");
        }
        if (batchLimit <= 0 || batchLimit > 1000) {
            throw new IllegalArgumentException("batchLimit must be in [1, 1000]");
        }
        this.scanInterval = scanInterval;
        this.batchLimit = batchLimit;
        this.store = new PaymentReconcileStore(source);
        this.taskReader = new JdbcAsyncTaskStatusReader(source);
        this.taskRecoverer = new JdbcAsyncTaskRecoverer(source, ids);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    /** Spring lifecycle initMethod: schedules periodic compensation scanning. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        long delaySeconds = Math.max(1L, scanInterval.toSeconds());
        scheduler.scheduleWithFixedDelay(this::runScheduledScan, Math.min(30L, delaySeconds),
                delaySeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** Executes one synchronous compensation scan pass. Safe for both scheduler and direct testing. */
    public synchronized int scanOnce() {
        noOuterTransaction();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        int total = 0;
        total += scanMissedDueOrders(now);
        total += scanStuckInstructions(now);
        for (CompensationHook hook : hooks) {
            try {
                total += Math.max(0, hook.scan());
            } catch (RuntimeException failure) {
                LOG.error("Settlement compensation hook failed hook={}", hook.getClass().getSimpleName(), failure);
            }
        }
        return total;
    }

    private void runScheduledScan() {
        try {
            int recovered = scanOnce();
            if (recovered > 0) {
                LOG.warn("Settlement compensation scan recovered {} items", recovered);
            }
        } catch (Throwable failure) {
            LOG.error("Scheduled settlement compensation scan failed", failure);
        }
    }

    private int scanMissedDueOrders(OffsetDateTime now) {
        OffsetDateTime cutoffTime = now.minus(DUE_GRACE).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime cutoff = PaymentReconcileStore.utc(cutoffTime);
        List<WaitingOrderScanRow> candidates = store.reconcile.selectOverdueWaitingOrders(
                PaymentFoundationStore.values("cutoff", cutoff, "limit", batchLimit));
        int recovered = 0;
        for (WaitingOrderScanRow candidate : candidates) {
            String taskKey = SettlementDueTask.taskKey(candidate.id, candidate.dueScheduleRound);
            String priorStatus = taskReader.status(taskKey);
            if (isAlive(priorStatus)) continue;

            Boolean ok = tx.execute(status -> {
                store.session();
                WaitingOrderScanRow locked = store.reconcile.selectWaitingOrderByIdForUpdate(candidate.id);
                if (locked == null || !"WAITING".equals(locked.status) || locked.eligibleAt == null
                        || locked.dueScheduleRound <= 0) {
                    return Boolean.FALSE;
                }
                OffsetDateTime eligible = PaymentReconcileStore.offset(locked.eligibleAt)
                        .truncatedTo(ChronoUnit.MILLIS);
                if (eligible.isAfter(cutoffTime)) {
                    return Boolean.FALSE;
                }
                String lockedKey = SettlementDueTask.taskKey(locked.id, locked.dueScheduleRound);
                if (isAlive(taskReader.status(lockedKey))) {
                    return Boolean.FALSE;
                }
                List<OffsetDateTime> schedules = buildDueSchedules(eligible, locked.updatedAt);
                return recoverWithFallbackSchedules(
                        lockedKey,
                        SettlementDueTask.OWNER_MODULE,
                        SettlementDueTask.TASK_TYPE,
                        SettlementDueTask.BIZ_TYPE,
                        locked.id,
                        (long) locked.dueScheduleRound,
                        SettlementDueTask.payload(locked.id, locked.dueScheduleRound),
                        SettlementDueTask.MAX_RETRY_COUNT,
                        SettlementDueTask.RETRY_POLICY,
                        schedules);
            });
            if (Boolean.TRUE.equals(ok)) {
                recovered++;
                LOG.warn("Recovered missed SETTLEMENT_DUE task settlementOrderId={} round={} priorTaskStatus={}",
                        IDS.toApi(candidate.id), candidate.dueScheduleRound, priorStatus);
            }
        }
        return recovered;
    }

    private int scanStuckInstructions(OffsetDateTime now) {
        OffsetDateTime cutoffTime = now.minus(STUCK_INSTRUCTION_GRACE).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime cutoff = PaymentReconcileStore.utc(cutoffTime);
        List<StuckInstructionScanRow> candidates = store.reconcile.selectStuckInstructions(
                PaymentFoundationStore.values("cutoff", cutoff, "limit", batchLimit));
        int recovered = 0;
        for (StuckInstructionScanRow candidate : candidates) {
            String taskKey = SettlementQueryTask.taskKey(candidate.id);
            String priorStatus = taskReader.status(taskKey);
            if (isAlive(priorStatus)) continue;

            Boolean ok = tx.execute(status -> {
                store.session();
                StuckInstructionScanRow locked = store.reconcile.selectStuckInstructionByIdForUpdate(candidate.id);
                if (locked == null || (!"SENT".equals(locked.status) && !"PROCESSING".equals(locked.status))
                        || locked.updatedAt == null) {
                    return Boolean.FALSE;
                }
                OffsetDateTime updated = PaymentReconcileStore.offset(locked.updatedAt)
                        .truncatedTo(ChronoUnit.MILLIS);
                if (updated.isAfter(cutoffTime)) {
                    return Boolean.FALSE;
                }
                if (isAlive(taskReader.status(taskKey))) {
                    return Boolean.FALSE;
                }
                List<OffsetDateTime> schedules = buildQuerySchedules(locked.sentAt, locked.createdAt);
                return recoverWithFallbackSchedules(
                        taskKey,
                        SettlementQueryTask.OWNER_MODULE,
                        SettlementQueryTask.TASK_TYPE,
                        SettlementQueryTask.BIZ_TYPE,
                        locked.id,
                        null,
                        SettlementQueryTask.payload(locked.id),
                        SettlementQueryTask.MAX_RETRY_COUNT,
                        SettlementQueryTask.RETRY_POLICY,
                        schedules);
            });
            if (Boolean.TRUE.equals(ok)) {
                recovered++;
                LOG.warn("Recovered stuck SETTLEMENT_CHANNEL_QUERY task instructionId={} settlementOrderId={} priorTaskStatus={}",
                        IDS.toApi(candidate.id), IDS.toApi(candidate.settlementOrderId), priorStatus);
            }
        }
        return recovered;
    }

    private boolean recoverWithFallbackSchedules(
            String taskKey, String ownerModule, String taskType, String bizType,
            long bizId, Long expectedVersion, String payloadJson,
            int maxRetryCount, String retryPolicy, List<OffsetDateTime> candidateSchedules) {
        IllegalArgumentException lastMismatch = null;
        for (OffsetDateTime schedule : candidateSchedules) {
            try {
                taskRecoverer.recover(taskKey, ownerModule, taskType, bizType,
                        bizId, expectedVersion, payloadJson, maxRetryCount, retryPolicy, schedule);
                return true;
            } catch (IllegalArgumentException scheduleMismatch) {
                lastMismatch = scheduleMismatch;
            }
        }
        LOG.error("Unable to recover settlement task due to immutable metadata mismatch taskKey={}",
                taskKey, lastMismatch);
        return false;
    }

    private static List<OffsetDateTime> buildDueSchedules(OffsetDateTime eligibleAt, LocalDateTime updatedAt) {
        List<OffsetDateTime> schedules = new ArrayList<>(3);
        schedules.add(eligibleAt.truncatedTo(ChronoUnit.MILLIS));
        if (updatedAt != null) {
            OffsetDateTime updated = PaymentReconcileStore.offset(updatedAt).truncatedTo(ChronoUnit.MILLIS);
            if (!updated.equals(eligibleAt)) {
                schedules.add(updated);
            }
        }
        schedules.add(null);
        return schedules;
    }

    private static List<OffsetDateTime> buildQuerySchedules(LocalDateTime sentAt, LocalDateTime createdAt) {
        List<OffsetDateTime> schedules = new ArrayList<>(3);
        if (sentAt != null) {
            schedules.add(PaymentReconcileStore.offset(sentAt)
                    .plus(SettlementQueryTask.FIRST_DELAY).truncatedTo(ChronoUnit.MILLIS));
        }
        if (createdAt != null) {
            OffsetDateTime fromCreated = PaymentReconcileStore.offset(createdAt)
                    .plus(SettlementQueryTask.FIRST_DELAY).truncatedTo(ChronoUnit.MILLIS);
            if (!schedules.contains(fromCreated)) {
                schedules.add(fromCreated);
            }
        }
        schedules.add(null);
        return schedules;
    }

    private static boolean isAlive(String status) {
        return status != null && ALIVE_TASK_STATES.contains(status);
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("settlement compensation scan must not run inside caller transaction");
        }
    }
}
