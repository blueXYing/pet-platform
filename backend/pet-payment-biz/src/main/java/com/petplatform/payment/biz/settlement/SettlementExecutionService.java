package com.petplatform.payment.biz.settlement;

import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator.ShareCalculation;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator.SplitAllocation;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ConfigRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.OrderRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedQueryAmt;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSeparateSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.QueryAmtInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.ReceiverShare;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SeparateInput;
import com.petplatform.payment.biz.settlement.SettlementOrderBlockerPort.BlockerState;
import com.petplatform.payment.biz.settlement.SettlementReceiverLookup.ActiveReceiver;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 78 due-settlement executor. Enforces strict send discipline:
 *
 * <ol>
 *   <li>Verify DB status, trace ({@code log_no}/{@code log_date}), active receiver and order blockers;
 *   <li>Query Lakala 2396 ({@code queryAmt}) strictly outside any DB transaction;
 *   <li>Inside a single row-locked fence transaction: persist {@code settlement_instruction(SENT)},
 *       advance {@code settlement_order} to {@code SPLITTING}, and enqueue {@code SETTLEMENT_CHANNEL_QUERY}
 *       (available at {@code now + 15s});
 *   <li>Submit Lakala 1293 ({@code separate}) strictly outside any DB transaction; on any unknown
 *       channel outcome leave the instruction in {@code SENT} so the already-enqueued query task
 *       confirms by the original {@code out_separate_no} without ever re-issuing.
 * </ol>
 */
public final class SettlementExecutionService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    public record Settings(
            String platformMerchantNo,
            ZoneId channelTimeZone,
            BigDecimal lowestRatio,
            String lklOrgNo,
            String notifyUrl,
            String platformReceiverNo) {
        public Settings(
                String platformMerchantNo,
                ZoneId channelTimeZone,
                BigDecimal lowestRatio,
                String lklOrgNo,
                String notifyUrl) {
            this(platformMerchantNo, channelTimeZone, lowestRatio, lklOrgNo, notifyUrl, null);
        }

        public Settings {
            if (platformMerchantNo == null || platformMerchantNo.isBlank() || platformMerchantNo.length() > 32) {
                throw new IllegalArgumentException("platformMerchantNo is required");
            }
            Objects.requireNonNull(channelTimeZone, "channelTimeZone");
            lowestRatio = SettlementAmountCalculator.validateLowestRatio(
                    lowestRatio == null ? BigDecimal.ZERO : lowestRatio);
            lklOrgNo = lklOrgNo == null || lklOrgNo.isBlank() ? null : lklOrgNo.trim();
            notifyUrl = notifyUrl == null || notifyUrl.isBlank() ? null : notifyUrl.trim();
            platformReceiverNo = platformReceiverNo == null || platformReceiverNo.isBlank()
                    ? null : platformReceiverNo.trim();
            if (platformReceiverNo != null && platformReceiverNo.length() > 32) {
                throw new IllegalArgumentException("platformReceiverNo must not exceed 32 characters");
            }
        }
    }

    private final SnowflakeIdGenerator ids;
    private final PaymentSettlementStore store;
    private final SettlementChannel channel;
    private final SettlementReceiverLookup receivers;
    private final SettlementOrderBlockerPort blockers;
    private final JdbcAsyncTaskSubmitter tasks;
    private final Settings settings;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SettlementExecutionService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementChannel channel,
            SettlementReceiverLookup receivers,
            SettlementOrderBlockerPort blockers,
            JdbcAsyncTaskSubmitter tasks,
            Settings settings,
            Clock clock) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.receivers = Objects.requireNonNull(receivers, "receivers");
        this.blockers = Objects.requireNonNull(blockers, "blockers");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = new PaymentSettlementStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    public SettlementDueTask.Outcome executeDue(long settlementOrderId, int expectedRound) {
        noOuterTransaction();
        Preflight preflight = runPreflight(settlementOrderId, expectedRound);
        if (preflight.earlyOutcome() != null) {
            return preflight.earlyOutcome();
        }

        // Phase 2: Query Lakala 2396 outside transaction under the order's actual acquiring merchant.
        VerifiedQueryAmt amtResult;
        try {
            amtResult = channel.querySeparableAmount(new QueryAmtInput(
                    channelNow(), preflight.acquiringMerchantNo(), preflight.logNo(), preflight.logDate()));
        } catch (ChannelUnknownException unknown) {
            return new SettlementDueTask.Outcome.Retry("SACS_QUERY_AMT_" + unknown.reason().name());
        }

        BigDecimal canSeparate = amtResult.result().canSeparateAmount();
        if (canSeparate.signum() <= 0) {
            markHeldIfUnchanged(settlementOrderId, preflight.version(), "ZERO_SEPARABLE_AMOUNT",
                    "ZERO_SEPARABLE_AMOUNT", "Lakala 2396 returned can_separate_amt=0");
            return new SettlementDueTask.Outcome.Held("ZERO_SEPARABLE_AMOUNT");
        }

        SplitAllocation allocation = SettlementAmountCalculator.computeSplitAllocation(
                preflight.merchantShare(), preflight.paidAmount(), canSeparate, settings.lowestRatio());

        // Mode A guard: when platform fee share > 0, platformReceiverNo (SR...) must be configured before send fence.
        if (preflight.modeA() && allocation.platformRetainedAmount().signum() > 0
                && (settings.platformReceiverNo() == null || settings.platformReceiverNo().isBlank())) {
            markHeldIfUnchanged(settlementOrderId, preflight.version(),
                    "PLATFORM_RECEIVER_NOT_CONFIGURED",
                    "PLATFORM_RECEIVER_NOT_CONFIGURED",
                    "Platform split receiver (LAKALA_PLATFORM_RECEIVER_NO) is not configured for positive platform share");
            return new SettlementDueTask.Outcome.Held("PLATFORM_RECEIVER_NOT_CONFIGURED");
        }

        // Phase 3: Atomic send fence transaction — persist SENT instruction + enqueue 15s query task.
        PersistedInstruction persisted = persistSendFence(preflight, canSeparate, allocation);
        if (persisted.earlyOutcome() != null) {
            return persisted.earlyOutcome();
        }

        // Phase 4: Submit Lakala 1293 outside transaction. Unknown outcome stays SENT for query task.
        List<ReceiverShare> shares = preflight.modeA()
                ? buildModeAReceiverShares(preflight.acquiringMerchantNo(), persisted.receiverNo(),
                        allocation.platformRetainedAmount(), allocation.splitAmount())
                : buildReceiverShares(preflight.acquiringMerchantNo(), persisted.receiverNo(),
                        allocation.platformRetainedAmount(), allocation.splitAmount());
        long totalCents = SettlementAmountCalculator.toCents(canSeparate);
        try {
            VerifiedSeparateSubmit submitted = channel.submitSeparate(new SeparateInput(
                    channelNow(),
                    preflight.acquiringMerchantNo(),
                    persisted.logNo(),
                    persisted.logDate(),
                    persisted.outSeparateNo(),
                    totalCents,
                    settings.lklOrgNo(),
                    settings.notifyUrl(),
                    shares,
                    "SO" + IDS.toApi(settlementOrderId)));
            recordSubmitAck(persisted.instructionId(), submitted);
        } catch (ChannelUnknownException unknown) {
            // Never mark FAILED on unknown submission; SETTLEMENT_CHANNEL_QUERY confirms by out_separate_no.
        }
        return new SettlementDueTask.Outcome.Done();
    }

    private record Preflight(
            SettlementDueTask.Outcome earlyOutcome,
            long settlementOrderId,
            long paymentId,
            long merchantId,
            long version,
            BigDecimal paidAmount,
            BigDecimal merchantShare,
            String acquiringMerchantNo,
            String logNo,
            LocalDate logDate,
            String receiverNo,
            boolean modeA) {
        static Preflight stop(SettlementDueTask.Outcome outcome) {
            return new Preflight(outcome, 0, 0, 0, 0, null, null, null, null, null, null, false);
        }
    }

    private Preflight runPreflight(long settlementOrderId, int expectedRound) {
        // Inspect live ORDER blockers before locking settlement_order to avoid cross-module lock cycles.
        OrderRow unlocked = store.settlement.selectOrderById(settlementOrderId);
        if (unlocked == null) {
            return Preflight.stop(new SettlementDueTask.Outcome.Canceled("SETTLEMENT_ORDER_NOT_FOUND"));
        }
        BlockerState orderBlocker = unlocked.orderId != null ? blockers.inspect(unlocked.orderId) : null;
        Optional<ActiveReceiver> activeReceiver = receivers.findActiveByMerchantId(unlocked.merchantId);

        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            OrderRow row = store.settlement.selectOrderByIdForUpdate(settlementOrderId);
            if (row == null) {
                return Preflight.stop(new SettlementDueTask.Outcome.Canceled("SETTLEMENT_ORDER_NOT_FOUND"));
            }
            if (expectedRound > 0 && row.dueScheduleRound != expectedRound) {
                return Preflight.stop(new SettlementDueTask.Outcome.Canceled("STALE_DUE_ROUND"));
            }
            if (!"WAITING".equals(row.status)) {
                return Preflight.stop(new SettlementDueTask.Outcome.Done());
            }
            if (row.eligibleAt == null) {
                return Preflight.stop(new SettlementDueTask.Outcome.Dead("MISSING_ELIGIBLE_AT"));
            }
            OffsetDateTime eligible = PaymentSettlementStore.offset(row.eligibleAt);
            if (now.isBefore(eligible)) {
                Duration remaining = Duration.between(now, eligible).plusMillis(200);
                return Preflight.stop(new SettlementDueTask.Outcome.Reschedule(remaining));
            }
            if (store.settlement.countActiveSplitInstructions(row.id) > 0) {
                return Preflight.stop(new SettlementDueTask.Outcome.Done());
            }

            // Re-check merchant config enabled switch.
            ConfigRow cfg = store.settlement.selectConfigByMerchant(row.merchantId);
            if (cfg != null && !cfg.enabled) {
                holdRow(row, "MERCHANT_SETTLEMENT_DISABLED", null, null, now);
                return Preflight.stop(new SettlementDueTask.Outcome.Held("MERCHANT_SETTLEMENT_DISABLED"));
            }

            // Re-check ORDER live refund/aftersale blockers (Ruling 40 §5.3).
            BigDecimal currentMerchantShare = row.merchantShare;
            long currentVersion = row.version;
            if (orderBlocker != null) {
                if (orderBlocker.refundedAmount().compareTo(row.paidAmount) >= 0) {
                    store.settlement.markOrderCanceled(PaymentFoundationStore.values(
                            "id", row.id, "version", row.version,
                            "refundedAmount", orderBlocker.refundedAmount().setScale(2),
                            "now", PaymentSettlementStore.utc(now)));
                    return Preflight.stop(new SettlementDueTask.Outcome.Canceled("ORDER_FULLY_REFUNDED"));
                }
                if (orderBlocker.refundInProgress()) {
                    holdRow(row, "REFUND_IN_PROGRESS", null, null, now);
                    return Preflight.stop(new SettlementDueTask.Outcome.Held("REFUND_IN_PROGRESS"));
                }
                if (orderBlocker.afterSaleInProgress()) {
                    holdRow(row, "AFTERSALE_IN_PROGRESS", null, null, now);
                    return Preflight.stop(new SettlementDueTask.Outcome.Held("AFTERSALE_IN_PROGRESS"));
                }
                if (orderBlocker.refundedAmount().compareTo(row.refundedAmount) != 0) {
                    ShareCalculation recalc = SettlementAmountCalculator.computeBaseAndShare(
                            row.originalPrice, row.flashDiscountAmount, row.merchantDiscountAmount,
                            row.paidAmount, orderBlocker.refundedAmount(), row.feeRateSnapshot);
                    int updated = store.settlement.updateOrderRefundAmounts(PaymentFoundationStore.values(
                            "id", row.id, "version", row.version,
                            "refundedAmount", orderBlocker.refundedAmount().setScale(2),
                            "splitBase", recalc.splitBase(),
                            "merchantShare", recalc.merchantShare(),
                            "now", PaymentSettlementStore.utc(now)));
                    if (updated != 1) {
                        return Preflight.stop(new SettlementDueTask.Outcome.Retry("SETTLEMENT_CONCURRENT_UPDATE"));
                    }
                    currentMerchantShare = recalc.merchantShare();
                    currentVersion = row.version + 1;
                }
            }

            // Re-check authoritative payment_order trace (Contract 75 §3: NULL log_no/log_date fails closed).
            CenterRow payment = store.center.selectCenterById(row.paymentId);
            if (payment == null || !"PAID".equals(payment.status()) || !"1".equals(payment.settleType())
                    || payment.merchantNo() == null || payment.merchantNo().isBlank()) {
                holdRow(row.id, currentVersion, "PAYMENT_INELIGIBLE", "PAYMENT_INELIGIBLE",
                        "Payment row is missing, unpaid, non-split or acquiring merchant absent", now);
                return Preflight.stop(new SettlementDueTask.Outcome.Held("PAYMENT_INELIGIBLE"));
            }

            // Detect Mode A (merchant-acquiring + reverse split to platform, Ruling 40 §10) vs legacy platform-acquiring.
            boolean merchantConfiguredAcquiring = cfg != null
                    && cfg.lakalaMerchantNo != null && !cfg.lakalaMerchantNo.isBlank();
            boolean modeA = merchantConfiguredAcquiring
                    || !settings.platformMerchantNo().equals(payment.merchantNo())
                    || settings.platformReceiverNo() != null;

            // Legacy platform-acquiring requires active merchant SR... receiver; Mode A splits self + optional platform SR....
            if (!modeA && activeReceiver.isEmpty()) {
                holdRow(row.id, currentVersion, "RECEIVER_NOT_ACTIVE", null, null, now);
                return Preflight.stop(new SettlementDueTask.Outcome.Held("RECEIVER_NOT_ACTIVE"));
            }

            if (payment.logNo() == null || payment.logNo().isBlank() || payment.logDate() == null) {
                holdRow(row.id, currentVersion, "MISSING_COLLECT_TRACE", "MISSING_COLLECT_TRACE",
                        "payment_order.log_no/log_date is NULL; manual reconciliation required", now);
                return Preflight.stop(new SettlementDueTask.Outcome.Held("MISSING_COLLECT_TRACE"));
            }

            String targetReceiverNo = modeA ? settings.platformReceiverNo() : activeReceiver.get().receiverNo();
            return new Preflight(null, row.id, row.paymentId, row.merchantId, currentVersion,
                    row.paidAmount, currentMerchantShare, payment.merchantNo(),
                    payment.logNo(), payment.logDate(), targetReceiverNo, modeA);
        });
    }

    private record PersistedInstruction(
            SettlementDueTask.Outcome earlyOutcome,
            long instructionId,
            String outSeparateNo,
            String receiverNo,
            String logNo,
            LocalDate logDate) {
        static PersistedInstruction stop(SettlementDueTask.Outcome outcome) {
            return new PersistedInstruction(outcome, 0, null, null, null, null);
        }
    }

    private PersistedInstruction persistSendFence(Preflight preflight, BigDecimal canSeparate,
            SplitAllocation allocation) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            OrderRow row = store.settlement.selectOrderByIdForUpdate(preflight.settlementOrderId());
            if (row == null || !"WAITING".equals(row.status) || row.version != preflight.version()) {
                return PersistedInstruction.stop(new SettlementDueTask.Outcome.Retry("SETTLEMENT_STATE_CHANGED"));
            }
            if (store.settlement.countActiveSplitInstructions(row.id) > 0) {
                return PersistedInstruction.stop(new SettlementDueTask.Outcome.Done());
            }
            Integer maxAttempt = store.settlement.selectMaxAttemptNo(PaymentFoundationStore.values(
                    "settlementOrderId", row.id, "instructionType", "SPLIT"));
            int attemptNo = (maxAttempt == null ? 0 : maxAttempt) + 1;
            long instructionId = nextId();
            String outSeparateNo = "SP" + IDS.toApi(instructionId);
            String instructionReceiverNo = preflight.modeA()
                    ? (allocation.platformRetainedAmount().signum() > 0 ? settings.platformReceiverNo() : null)
                    : preflight.receiverNo();
            store.settlement.insertInstruction(PaymentFoundationStore.values(
                    "id", instructionId,
                    "settlementOrderId", row.id,
                    "paymentId", row.paymentId,
                    "refundOrderId", null,
                    "originInstructionId", null,
                    "merchantId", row.merchantId,
                    "instructionType", "SPLIT",
                    "attemptNo", attemptNo,
                    "outSeparateNo", outSeparateNo,
                    "merchantNo", preflight.acquiringMerchantNo(),
                    "receiverNo", instructionReceiverNo,
                    "logNo", preflight.logNo(),
                    "logDate", preflight.logDate(),
                    "totalAmount", canSeparate.setScale(2),
                    "merchantAmount", allocation.splitAmount(),
                    "platformAmount", allocation.platformRetainedAmount(),
                    "fallbackReason", null,
                    "now", PaymentSettlementStore.utc(now)));
            int updated = store.settlement.markOrderSplitting(PaymentFoundationStore.values(
                    "id", row.id,
                    "version", row.version,
                    "splitAmount", allocation.splitAmount(),
                    "platformRetainedAmount", allocation.platformRetainedAmount(),
                    "platformSubsidyDue", allocation.platformSubsidyDue(),
                    "instructionId", instructionId,
                    "now", PaymentSettlementStore.utc(now)));
            if (updated != 1) {
                throw new IllegalStateException("failed to advance settlement_order to SPLITTING");
            }
            // Same transaction: enqueue SETTLEMENT_CHANNEL_QUERY at now + 15s (per Lakala 1293).
            tasks.enqueueAt(
                    SettlementQueryTask.taskKey(instructionId),
                    SettlementQueryTask.OWNER_MODULE,
                    SettlementQueryTask.TASK_TYPE,
                    SettlementQueryTask.BIZ_TYPE,
                    instructionId,
                    null,
                    SettlementQueryTask.payload(instructionId),
                    SettlementQueryTask.MAX_RETRY_COUNT,
                    SettlementQueryTask.RETRY_POLICY,
                    now.plus(SettlementQueryTask.FIRST_DELAY).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            return new PersistedInstruction(null, instructionId, outSeparateNo, instructionReceiverNo,
                    preflight.logNo(), preflight.logDate());
        });
    }

    private void recordSubmitAck(long instructionId, VerifiedSeparateSubmit submitted) {
        tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            var instr = store.settlement.selectInstructionByIdForUpdate(instructionId);
            if (instr == null || !"SENT".equals(instr.status)) return null;
            store.settlement.markInstructionProcessing(PaymentFoundationStore.values(
                    "id", instr.id,
                    "version", instr.version,
                    "separateNo", submitted.result().separateNo(),
                    "channelStatus", submitted.result().rawStatus() != null
                            ? submitted.result().rawStatus() : submitted.result().status().name(),
                    "channelFinalStatus", null,
                    "submitResponseSha256", submitted.rawResponseSha256(),
                    "now", PaymentSettlementStore.utc(now)));
            return null;
        });
    }

    private void markHeldIfUnchanged(long settlementOrderId, long expectedVersion, String holdReason,
            String errorCode, String errorMessage) {
        tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            holdRow(settlementOrderId, expectedVersion, holdReason, errorCode, errorMessage, now);
            return null;
        });
    }

    private void holdRow(OrderRow row, String holdReason, String errorCode, String errorMessage,
            OffsetDateTime now) {
        holdRow(row.id, row.version, holdReason, errorCode, errorMessage, now);
    }

    private void holdRow(long id, long version, String holdReason, String errorCode, String errorMessage,
            OffsetDateTime now) {
        store.settlement.markOrderOnHold(PaymentFoundationStore.values(
                "id", id, "version", version, "holdReason", holdReason,
                "errorCode", errorCode, "errorMessage", errorMessage,
                "now", PaymentSettlementStore.utc(now)));
    }

    static List<ReceiverShare> buildReceiverShares(String platformMerchantNo, String merchantReceiverNo,
            BigDecimal platformRetainedAmount, BigDecimal merchantSplitAmount) {
        List<ReceiverShare> shares = new ArrayList<>(2);
        long platformCents = SettlementAmountCalculator.toCents(platformRetainedAmount);
        long merchantCents = SettlementAmountCalculator.toCents(merchantSplitAmount);
        if (platformCents > 0) {
            shares.add(ReceiverShare.toPlatform(platformMerchantNo, platformCents));
        }
        if (merchantCents > 0) {
            shares.add(ReceiverShare.toReceiver(merchantReceiverNo, merchantCents));
        }
        if (shares.isEmpty()) {
            throw new IllegalStateException("split allocation has zero total cents");
        }
        return shares;
    }

    /** Mode A (Ruling 40 §10): merchant receives self-split ({@code recv_merchant_no}), platform receives fee ({@code recv_no}). */
    static List<ReceiverShare> buildModeAReceiverShares(String acquiringMerchantNo, String platformReceiverNo,
            BigDecimal platformRetainedAmount, BigDecimal merchantSplitAmount) {
        List<ReceiverShare> shares = new ArrayList<>(2);
        long merchantCents = SettlementAmountCalculator.toCents(merchantSplitAmount);
        long platformCents = SettlementAmountCalculator.toCents(platformRetainedAmount);
        if (merchantCents > 0) {
            shares.add(ReceiverShare.toSelf(acquiringMerchantNo, merchantCents));
        }
        if (platformCents > 0) {
            if (platformReceiverNo == null || platformReceiverNo.isBlank()) {
                throw new IllegalStateException("platformReceiverNo is required when platform share > 0");
            }
            shares.add(ReceiverShare.toReceiver(platformReceiverNo, platformCents));
        }
        if (shares.isEmpty()) {
            throw new IllegalStateException("split allocation has zero total cents");
        }
        return shares;
    }

    private LocalDateTime channelNow() {
        return LocalDateTime.ofInstant(clock.instant(), settings.channelTimeZone()).withNano(0);
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw new IllegalStateException("invalid snowflake id");
        return id;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("settlement execution requires no outer transaction");
        }
    }
}
