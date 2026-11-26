package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.dto.SettlementTypes.RetryFallbackCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.biz.application.SettlementRefundGate;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ActionRequestRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.InstructionRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.OrderRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.ChannelUnknownException;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedFallbackSubmit;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.FallbackInput;
import com.petplatform.payment.biz.task.SettlementQueryTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 78 §8 / Ruling 40 §6 post-split fallback orchestrator and synchronous pre-refund gate.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Pre-split {@code WAITING} settlements are atomically paused ({@code ON_HOLD}) under row lock
 *       before releasing a refund, closing the refund x split concurrency race.</li>
 *   <li>Post-split refunds trigger Lakala 2395 ({@code /api/v3/sacs/fallback}) using strict send
 *       fence discipline ({@code SENT} + 15s {@code SETTLEMENT_CHANNEL_QUERY} committed before I/O);
 *       original-route refund never executes until fallback is verified {@code SUCCEEDED}.</li>
 *   <li>Fallback failure or 180-day window expiry pauses the refund ({@code Paused}) and logs an
 *       operator alert; the platform never advances funds.</li>
 * </ul>
 */
public final class SettlementFallbackService implements SettlementRefundGate {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementFallbackService.class);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final SnowflakeIdGenerator ids;
    private final PaymentSettlementStore store;
    private final SettlementChannel channel;
    private final JdbcAsyncTaskSubmitter tasks;
    private final ZoneId channelTimeZone;
    private final Clock clock;
    private final String platformMerchantNo;
    private final String platformReceiverNo;
    private final TransactionTemplate tx;

    public SettlementFallbackService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementChannel channel,
            JdbcAsyncTaskSubmitter tasks,
            ZoneId channelTimeZone,
            Clock clock) {
        this(source, ids, channel, tasks, channelTimeZone, clock, null, null);
    }

    public SettlementFallbackService(
            DataSource source,
            SnowflakeIdGenerator ids,
            SettlementChannel channel,
            JdbcAsyncTaskSubmitter tasks,
            ZoneId channelTimeZone,
            Clock clock,
            String platformMerchantNo,
            String platformReceiverNo) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channelTimeZone");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.platformMerchantNo = platformMerchantNo == null || platformMerchantNo.isBlank()
                ? null : platformMerchantNo.trim();
        this.platformReceiverNo = platformReceiverNo == null || platformReceiverNo.isBlank()
                ? null : platformReceiverNo.trim();
        this.store = new PaymentSettlementStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    @Override
    public GateDecision ensureReadyForRefund(RefundGateRequest request) {
        noOuterTransaction();
        if (request == null || request.paymentId() <= 0 || request.refundOrderId() <= 0
                || request.refundAmount() == null || request.refundAmount().signum() <= 0) {
            return new GateDecision.Paused("INVALID_REFUND_GATE_REQUEST", "invalid refund gate request");
        }

        GatePlan plan = tx.execute(status -> evaluateAndFence(request));
        if (plan == null) {
            return new GateDecision.Paused("SETTLEMENT_GATE_UNAVAILABLE", "settlement gate returned null");
        }
        if (plan.submitPlan() != null) {
            dispatchFallbackOutsideTx(plan.submitPlan());
        }
        return plan.decision();
    }

    /** Admin idempotent retry for a failed fallback after merchant balance replenishment. */
    public SettlementOrderView retryFailedFallback(RetryFallbackCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long settlementOrderId = parsePositiveId(command.settlementOrderId(), "settlementOrderId");
        long refundOrderId = parsePositiveId(command.refundOrderId(), "refundOrderId");
        byte[] reqKey = requestKey("settlement.fallback.retry", command.context(), requestId);
        String fingerprint = sha256("RETRY_FALLBACK|" + settlementOrderId + "|" + refundOrderId);

        SubmitPlan submitPlan = tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            store.settlement.insertActionRequest(PaymentFoundationStore.values(
                    "id", nextId(), "requestKey", reqKey, "actionType", "RETRY_FALLBACK",
                    "settlementOrderId", settlementOrderId, "fingerprint", fingerprint,
                    "now", PaymentSettlementStore.utc(now)));
            ActionRequestRow action = store.settlement.selectActionRequestForUpdate(reqKey);
            if (action == null) throw unavailable("fallback action request binding unavailable");
            if (!"RETRY_FALLBACK".equals(action.actionType)
                    || settlementOrderId != action.settlementOrderId
                    || !fingerprint.equals(action.requestFingerprint)) {
                throw idempotencyConflict("fallback retry requestId conflict");
            }

            OrderRow row = store.settlement.selectOrderByIdForUpdate(settlementOrderId);
            if (row == null) throw notFound("settlement order not found");

            List<InstructionRow> existing = store.settlement.selectFallbackInstructionsByRefundOrderId(refundOrderId);
            for (InstructionRow item : existing) {
                if (item.settlementOrderId != row.id) {
                    throw conflict("refundOrderId does not belong to settlement order");
                }
                if ("SUCCEEDED".equals(item.status) || "SENT".equals(item.status) || "PROCESSING".equals(item.status)) {
                    // Idempotent replay when already active or succeeded.
                    return null;
                }
            }
            if (!"REVERSE_FAILED".equals(row.status)) {
                throw conflict("settlement order status " + row.status + " cannot retry fallback");
            }
            if (store.settlement.countActiveFallbackInstructions(row.id) > 0) {
                throw conflict("settlement order already has an active fallback instruction");
            }
            InstructionRow failed = existing.stream()
                    .filter(i -> "FAILED".equals(i.status))
                    .findFirst()
                    .orElseThrow(() -> conflict("no failed fallback instruction found for refundOrderId"));

            InstructionRow originSplit = failed.originInstructionId != null
                    ? store.settlement.selectInstructionById(failed.originInstructionId)
                    : store.settlement.selectSucceededSplitInstruction(row.id);
            if (originSplit == null || !"SUCCEEDED".equals(originSplit.status)) {
                throw conflict("original succeeded split instruction unavailable");
            }
            OffsetDateTime splitCompletedAt = splitCompletedAt(originSplit);
            if (!SettlementAmountCalculator.isWithinFallbackWindow(splitCompletedAt, now)) {
                throw conflict("split fallback window (180 days) has expired");
            }
            String reason = normalizeReason(command.reason(), failed.fallbackReason);
            boolean modeA = SettlementModeDetector.isModeAInstruction(
                    originSplit, platformMerchantNo, platformReceiverNo);
            return persistFallbackFence(row, originSplit, refundOrderId, failed.totalAmount, reason, modeA, now);
        });

        if (submitPlan != null) {
            dispatchFallbackOutsideTx(submitPlan);
        }
        OrderRow latest = store.settlement.selectOrderById(settlementOrderId);
        if (latest == null) throw notFound("settlement order not found");
        return SettlementAdminService.toOrderView(latest);
    }

    private record SubmitPlan(
            long instructionId,
            String merchantNo,
            String originSeparateNo,
            String originOutSeparateNo,
            String outSeparateNo,
            String receiverNo,
            BigDecimal amount,
            String reason) {}

    private record GatePlan(GateDecision decision, SubmitPlan submitPlan) {}

    private GatePlan evaluateAndFence(RefundGateRequest request) {
        store.session();
        OffsetDateTime now = store.now();
        OrderRow row = store.settlement.selectOrderByPaymentIdForUpdate(request.paymentId());
        if (row == null) {
            return new GatePlan(new GateDecision.Proceed(), null);
        }

        // First check whether this refundOrderId already has a FALLBACK instruction (replay/query path).
        List<InstructionRow> forRefund = store.settlement.selectFallbackInstructionsByRefundOrderId(
                request.refundOrderId());
        for (InstructionRow instr : forRefund) {
            if (instr.settlementOrderId != row.id) {
                return new GatePlan(new GateDecision.Paused(
                        "FALLBACK_ORDER_MISMATCH", "fallback instruction linked to another settlement"), null);
            }
            if ("SUCCEEDED".equals(instr.status)) {
                return new GatePlan(new GateDecision.Proceed(), null);
            }
            if ("SENT".equals(instr.status) || "PROCESSING".equals(instr.status)) {
                return new GatePlan(new GateDecision.Wait(nextCheckAt(now)), null);
            }
        }
        if (!forRefund.isEmpty()) {
            InstructionRow latest = forRefund.getFirst();
            return new GatePlan(new GateDecision.Paused("CHANNEL_FALLBACK_FAILED", latest.accResultDesc), null);
        }

        // Pre-split states: never split yet. Pause WAITING synchronously to block concurrent due split.
        if ("WAITING".equals(row.status)) {
            int held = store.settlement.markOrderOnHold(PaymentFoundationStore.values(
                    "id", row.id, "version", row.version,
                    "holdReason", "REFUND_IN_PROGRESS",
                    "errorCode", null, "errorMessage", null,
                    "now", PaymentSettlementStore.utc(now)));
            if (held != 1) {
                return new GatePlan(new GateDecision.Wait(nextCheckAt(now)), null);
            }
            return new GatePlan(new GateDecision.Proceed(), null);
        }
        if ("PENDING_TRIGGER".equals(row.status) || "ON_HOLD".equals(row.status)
                || "SPLIT_FAILED".equals(row.status) || "CANCELED".equals(row.status)) {
            return new GatePlan(new GateDecision.Proceed(), null);
        }
        if ("SPLITTING".equals(row.status) || "REVERSING".equals(row.status)) {
            return new GatePlan(new GateDecision.Wait(nextCheckAt(now)), null);
        }
        if ("REVERSE_FAILED".equals(row.status)) {
            String code = row.lastErrorCode != null ? row.lastErrorCode : "SETTLEMENT_REVERSE_FAILED";
            return new GatePlan(new GateDecision.Paused(code, row.lastErrorMessage), null);
        }
        if (!"SPLIT_SUCCEEDED".equals(row.status) && !"PARTIALLY_REVERSED".equals(row.status)
                && !"REVERSED".equals(row.status)) {
            return new GatePlan(new GateDecision.Paused("UNKNOWN_SETTLEMENT_STATUS", row.status), null);
        }

        // Post-split state: compute required fallback amount (Mode A pulls back platform fee share; legacy pulls back merchant share).
        InstructionRow originSplit = store.settlement.selectSucceededSplitInstruction(row.id);
        if (originSplit == null) {
            return new GatePlan(new GateDecision.Paused(
                    "ORIGIN_SPLIT_NOT_FOUND", "succeeded split instruction missing"), null);
        }
        boolean modeA = SettlementModeDetector.isModeAInstruction(
                originSplit, platformMerchantNo, platformReceiverNo);
        final BigDecimal fallbackAmount;
        if (modeA) {
            // Mode A: when platform fee share was 0 (or no external receiver was split to), skip 2395 and refund directly.
            if (originSplit.platformAmount == null || originSplit.platformAmount.signum() <= 0
                    || originSplit.receiverNo == null || originSplit.receiverNo.isBlank()) {
                return new GatePlan(new GateDecision.Proceed(), null);
            }
            fallbackAmount = SettlementAmountCalculator.computePlatformFallbackAmount(
                    row.paidAmount, row.refundedAmount, request.refundAmount(),
                    row.feeRateSnapshot, originSplit.platformAmount, row.reversedAmount);
        } else {
            fallbackAmount = SettlementAmountCalculator.computeFallbackAmount(
                    row.paidAmount, row.refundedAmount, request.refundAmount(),
                    row.feeRateSnapshot, originSplit.merchantAmount, row.reversedAmount);
        }
        if (fallbackAmount.signum() <= 0) {
            return new GatePlan(new GateDecision.Proceed(), null);
        }

        // Enforce Lakala 2395 180-day fallback window (Ruling 40 §6.2).
        OffsetDateTime splitCompletedAt = splitCompletedAt(originSplit);
        if (!SettlementAmountCalculator.isWithinFallbackWindow(splitCompletedAt, now)) {
            String detail = "Split fallback window (180 days) exceeded";
            store.settlement.markOrderReverseFailed(PaymentFoundationStore.values(
                    "id", row.id, "version", row.version,
                    "errorCode", "FALLBACK_WINDOW_EXPIRED",
                    "errorMessage", detail,
                    "now", PaymentSettlementStore.utc(now)));
            String orderIdText = IDS.toApi(row.id);
            String refundIdText = IDS.toApi(request.refundOrderId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    LOG.error("Settlement fallback window expired; refund paused without platform advance "
                            + "settlementOrderId={} refundOrderId={}", orderIdText, refundIdText);
                }
            });
            return new GatePlan(new GateDecision.Paused("FALLBACK_WINDOW_EXPIRED", detail), null);
        }

        boolean hasOriginRef = (originSplit.separateNo != null && !originSplit.separateNo.isBlank())
                || (originSplit.outSeparateNo != null && !originSplit.outSeparateNo.isBlank());
        if (!hasOriginRef || originSplit.receiverNo == null || originSplit.receiverNo.isBlank()
                || originSplit.logNo == null || originSplit.logDate == null) {
            return new GatePlan(new GateDecision.Paused(
                    "ORIGIN_SPLIT_INCOMPLETE", "origin split trace or receiver missing"), null);
        }

        String reason = "REFUND:" + IDS.toApi(request.refundOrderId());
        SubmitPlan submitPlan = persistFallbackFence(
                row, originSplit, request.refundOrderId(), fallbackAmount, reason, modeA, now);
        return new GatePlan(new GateDecision.Wait(nextCheckAt(now)), submitPlan);
    }

    private SubmitPlan persistFallbackFence(OrderRow row, InstructionRow originSplit, long refundOrderId,
            BigDecimal fallbackAmount, String reason, boolean modeA, OffsetDateTime now) {
        Integer maxAttempt = store.settlement.selectMaxAttemptNo(PaymentFoundationStore.values(
                "settlementOrderId", row.id, "instructionType", "FALLBACK"));
        int attemptNo = (maxAttempt == null ? 0 : maxAttempt) + 1;
        long instructionId = nextId();
        String outSeparateNo = "FB" + IDS.toApi(instructionId);

        store.settlement.insertInstruction(PaymentFoundationStore.values(
                "id", instructionId,
                "settlementOrderId", row.id,
                "paymentId", row.paymentId,
                "refundOrderId", refundOrderId,
                "originInstructionId", originSplit.id,
                "merchantId", row.merchantId,
                "instructionType", "FALLBACK",
                "attemptNo", attemptNo,
                "outSeparateNo", outSeparateNo,
                "merchantNo", originSplit.merchantNo,
                "receiverNo", originSplit.receiverNo,
                "logNo", originSplit.logNo,
                "logDate", originSplit.logDate,
                "totalAmount", fallbackAmount,
                "merchantAmount", modeA ? SettlementAmountCalculator.ZERO : fallbackAmount,
                "platformAmount", modeA ? fallbackAmount : SettlementAmountCalculator.ZERO,
                "fallbackReason", reason,
                "now", PaymentSettlementStore.utc(now)));

        int updated = store.settlement.markOrderReversing(PaymentFoundationStore.values(
                "id", row.id, "version", row.version,
                "instructionId", instructionId,
                "now", PaymentSettlementStore.utc(now)));
        if (updated != 1) {
            throw conflict("failed to advance settlement_order to REVERSING");
        }

        // Same transaction: enqueue SETTLEMENT_CHANNEL_QUERY at now + 15s (per Lakala 2395).
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
                now.plus(SettlementQueryTask.FIRST_DELAY).truncatedTo(ChronoUnit.MILLIS));

        return new SubmitPlan(
                instructionId,
                originSplit.merchantNo,
                originSplit.separateNo,
                originSplit.outSeparateNo,
                outSeparateNo,
                originSplit.receiverNo,
                fallbackAmount,
                reason);
    }

    private void dispatchFallbackOutsideTx(SubmitPlan plan) {
        noOuterTransaction();
        try {
            VerifiedFallbackSubmit submitted = channel.submitFallback(new FallbackInput(
                    channelNow(),
                    plan.merchantNo(),
                    plan.originSeparateNo(),
                    plan.originOutSeparateNo(),
                    plan.outSeparateNo(),
                    SettlementAmountCalculator.toCents(plan.amount()),
                    plan.receiverNo(),
                    plan.reason()));
            recordSubmitAck(plan.instructionId(), submitted);
        } catch (ChannelUnknownException unknown) {
            // Never mark FAILED or re-issue on unknown 2395 submission; query task confirms by out_separate_no.
            LOG.warn("Lakala 2395 fallback submission outcome unknown instructionId={} reason={}",
                    IDS.toApi(plan.instructionId()), unknown.reason());
        }
    }

    private void recordSubmitAck(long instructionId, VerifiedFallbackSubmit submitted) {
        tx.executeWithoutResult(status -> {
            store.session();
            OffsetDateTime now = store.now();
            InstructionRow instr = store.settlement.selectInstructionByIdForUpdate(instructionId);
            if (instr == null || !"SENT".equals(instr.status)) return;
            store.settlement.markInstructionProcessing(PaymentFoundationStore.values(
                    "id", instr.id,
                    "version", instr.version,
                    "separateNo", submitted.result().separateNo(),
                    "channelStatus", submitted.result().rawStatus() != null
                            ? submitted.result().rawStatus() : submitted.result().status().name(),
                    "channelFinalStatus", null,
                    "submitResponseSha256", submitted.rawResponseSha256(),
                    "now", PaymentSettlementStore.utc(now)));
        });
    }

    private static OffsetDateTime splitCompletedAt(InstructionRow originSplit) {
        if (originSplit.terminalAt != null) {
            return PaymentSettlementStore.offset(originSplit.terminalAt);
        }
        if (originSplit.finishDate != null) {
            return originSplit.finishDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static OffsetDateTime nextCheckAt(OffsetDateTime now) {
        return now.plus(SettlementQueryTask.FIRST_DELAY).truncatedTo(ChronoUnit.MILLIS);
    }

    private LocalDateTime channelNow() {
        return LocalDateTime.ofInstant(clock.instant(), channelTimeZone).withNano(0);
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable("snowflake id generator returned invalid id");
        return id;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("settlement fallback must not run inside caller transaction");
        }
    }

    private static void requireAdminOrSystem(CommandContext ctx) {
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR && ctx.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "fallback retry requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static String requireRequestId(CommandContext ctx) {
        String req = ctx.requestId();
        if (req == null || req.isBlank() || req.length() > 128) throw invalid("requestId is required");
        return req.trim();
    }

    private static byte[] requestKey(String action, CommandContext ctx, String requestId) {
        String op = ctx.operatorId() == null ? "SYSTEM" : ctx.operatorId();
        return (action + "/" + ctx.operatorType().name() + "/" + op + "/" + requestId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static long parsePositiveId(String raw, String field) {
        try { return IDS.fromApi(raw); }
        catch (IllegalArgumentException bad) { throw invalid("invalid " + field); }
    }

    private static String normalizeReason(String candidate, String fallbackDefault) {
        String chosen = candidate != null && !candidate.isBlank() ? candidate.trim() : fallbackDefault;
        if (chosen == null || chosen.isBlank()) return "REFUND_FALLBACK_RETRY";
        return chosen.length() > 128 ? chosen.substring(0, 128) : chosen;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ApiException invalid(String msg) { return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg); }
    private static ApiException conflict(String msg) { return new ApiException(CommonApiCodes.CONFLICT, msg); }
    private static ApiException idempotencyConflict(String msg) {
        return new ApiException(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, msg);
    }
    private static ApiException notFound(String msg) { return new ApiException(CommonApiCodes.NOT_FOUND, msg); }
    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
