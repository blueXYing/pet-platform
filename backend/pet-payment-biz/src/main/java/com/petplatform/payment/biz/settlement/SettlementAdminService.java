package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.payment.api.command.SettlementAdminApi;
import com.petplatform.payment.api.command.SettlementTriggerApi;
import com.petplatform.payment.api.dto.SettlementTypes.ListSettlementOrdersQuery;
import com.petplatform.payment.api.dto.SettlementTypes.MarkSettlementVerifiedCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.ResumeSettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetryFallbackCommand;
import com.petplatform.payment.api.dto.SettlementTypes.RetrySettlementCommand;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementConfigView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementInstructionView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementOrderView;
import com.petplatform.payment.api.dto.SettlementTypes.SettlementPage;
import com.petplatform.payment.api.dto.SettlementTypes.UpsertSettlementConfigCommand;
import com.petplatform.payment.api.query.SettlementQueryApi;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator.ShareCalculation;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentOnboardingStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ActionRequestRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ConfigAuditRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.ConfigRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.InstructionRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.OrderRow;
import com.petplatform.payment.biz.task.SettlementDueTask;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 78 settlement configuration, lifecycle registration/verification, admin resume/retry and
 * query kernel. All write commands enforce requestId idempotency and optimistic version CAS.
 */
public final class SettlementAdminService implements SettlementAdminApi, SettlementTriggerApi, SettlementQueryApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> VALID_STATUSES = Set.of(
            "PENDING_TRIGGER", "WAITING", "ON_HOLD", "SPLITTING", "SPLIT_SUCCEEDED",
            "SPLIT_FAILED", "REVERSING", "PARTIALLY_REVERSED", "REVERSED", "REVERSE_FAILED", "CANCELED");
    private static final int MAX_PAGE_SIZE = 100;

    @FunctionalInterface
    public interface FallbackRetryDelegate {
        SettlementOrderView retry(RetryFallbackCommand command);
    }

    private final SnowflakeIdGenerator ids;
    private final PaymentSettlementStore store;
    private final PaymentOnboardingStore onboarding;
    private final JdbcAsyncTaskSubmitter tasks;
    private final FallbackRetryDelegate fallbackRetrier;
    private final String platformMerchantNo;
    private final String platformReceiverNo;
    private final TransactionTemplate tx;

    public SettlementAdminService(DataSource source, SnowflakeIdGenerator ids, JdbcAsyncTaskSubmitter tasks) {
        this(source, ids, tasks, null, null, null);
    }

    public SettlementAdminService(DataSource source, SnowflakeIdGenerator ids, JdbcAsyncTaskSubmitter tasks,
            FallbackRetryDelegate fallbackRetrier) {
        this(source, ids, tasks, fallbackRetrier, null, null);
    }

    public SettlementAdminService(DataSource source, SnowflakeIdGenerator ids, JdbcAsyncTaskSubmitter tasks,
            FallbackRetryDelegate fallbackRetrier, String platformMerchantNo, String platformReceiverNo) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fallbackRetrier = fallbackRetrier;
        this.platformMerchantNo = platformMerchantNo == null || platformMerchantNo.isBlank()
                ? null : platformMerchantNo.trim();
        this.platformReceiverNo = platformReceiverNo == null || platformReceiverNo.isBlank()
                ? null : platformReceiverNo.trim();
        this.store = new PaymentSettlementStore(source);
        this.onboarding = new PaymentOnboardingStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    // ---- SettlementAdminApi ----------------------------------------------------------------

    @Override
    public SettlementConfigView upsertConfig(UpsertSettlementConfigCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long merchantId = parsePositiveId(command.merchantId(), "merchantId");
        BigDecimal feeRate;
        try {
            SettlementAmountCalculator.validateSettleDays(command.settleDays());
            feeRate = SettlementAmountCalculator.validateFeeRate(command.feeRate());
        } catch (IllegalArgumentException bad) {
            throw invalid(bad.getMessage());
        }
        String reason = normalizeReason(command.reason());
        String lakalaMerchantNo = normalizeAcquiringToken(command.lakalaMerchantNo(), "lakalaMerchantNo");
        String lakalaTermNo = normalizeAcquiringToken(command.lakalaTermNo(), "lakalaTermNo");
        if ((lakalaMerchantNo == null) != (lakalaTermNo == null)) {
            throw invalid("lakalaMerchantNo and lakalaTermNo must be configured together");
        }
        byte[] requestKey = requestKey("settlement.config.upsert", command.context(), requestId);
        Long operatorId = parseNullableId(command.context().operatorId());

        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            ConfigAuditRow existingAudit = store.settlement.selectConfigAuditByRequestKey(requestKey);
            if (existingAudit != null) {
                if (existingAudit.merchantId != merchantId
                        || existingAudit.afterSettleDays != command.settleDays()
                        || existingAudit.afterFeeRate.compareTo(feeRate) != 0
                        || existingAudit.afterEnabled != command.enabled()
                        || !Objects.equals(existingAudit.afterLakalaMerchantNo, lakalaMerchantNo)
                        || !Objects.equals(existingAudit.afterLakalaTermNo, lakalaTermNo)) {
                    throw idempotencyConflict("settlement config requestId conflict");
                }
                ConfigRow current = store.settlement.selectConfigByMerchant(merchantId);
                return toConfigView(merchantId, current);
            }
            ConfigRow existing = store.settlement.selectConfigByMerchantForUpdate(merchantId);
            // Contract 81 §8: once a merchant has an onboarding record its acquiring pair is system-owned —
            // a request may neither replace it nor drop it; every other setting stays editable. Before V44 has
            // been applied no record can exist, which reads as "not owned" so hand-typed pairs keep working.
            String effectiveMerchantNo = lakalaMerchantNo;
            String effectiveTermNo = lakalaTermNo;
            if (onboarding.findIfApplied(merchantId) != null) {
                String ownedMerchant = existing == null ? null : existing.lakalaMerchantNo;
                String ownedTerm = existing == null ? null : existing.lakalaTermNo;
                if (lakalaMerchantNo != null && !lakalaMerchantNo.equals(ownedMerchant)) {
                    throw conflict("该商家的收单商户号与终端号由开通流程维护，不能手工修改");
                }
                effectiveMerchantNo = ownedMerchant;
                effectiveTermNo = ownedTerm;
            }
            long configId;
            Integer beforeDays = null;
            BigDecimal beforeFee = null;
            Boolean beforeEnabled = null;
            String beforeLklMerchant = null;
            String beforeLklTerm = null;
            if (existing == null) {
                configId = nextId();
                try {
                    store.settlement.insertConfig(PaymentFoundationStore.values(
                            "id", configId, "merchantId", merchantId,
                            "settleDays", command.settleDays(), "feeRate", feeRate,
                            "enabled", command.enabled(),
                            "lakalaMerchantNo", effectiveMerchantNo,
                            "lakalaTermNo", effectiveTermNo,
                            "now", PaymentSettlementStore.utc(now)));
                } catch (DuplicateKeyException race) {
                    throw conflict("concurrent settlement config creation, retry with same requestId");
                }
            } else {
                configId = existing.id;
                beforeDays = existing.settleDays;
                beforeFee = existing.feeRate;
                beforeEnabled = existing.enabled;
                beforeLklMerchant = existing.lakalaMerchantNo;
                beforeLklTerm = existing.lakalaTermNo;
                int updated = store.settlement.updateConfig(PaymentFoundationStore.values(
                        "id", existing.id, "version", existing.version,
                        "settleDays", command.settleDays(), "feeRate", feeRate,
                        "enabled", command.enabled(),
                        "lakalaMerchantNo", effectiveMerchantNo,
                        "lakalaTermNo", effectiveTermNo,
                        "now", PaymentSettlementStore.utc(now)));
                if (updated != 1) throw conflict("settlement config changed concurrently");
            }
            store.settlement.insertConfigAudit(PaymentFoundationStore.values(
                    "id", nextId(), "merchantId", merchantId, "configId", configId,
                    "requestKey", requestKey,
                    "beforeSettleDays", beforeDays, "afterSettleDays", command.settleDays(),
                    "beforeFeeRate", beforeFee, "afterFeeRate", feeRate,
                    "beforeEnabled", beforeEnabled, "afterEnabled", command.enabled(),
                    "beforeLakalaMerchantNo", beforeLklMerchant, "afterLakalaMerchantNo", effectiveMerchantNo,
                    "beforeLakalaTermNo", beforeLklTerm, "afterLakalaTermNo", effectiveTermNo,
                    "operatorType", command.context().operatorType().name(),
                    "operatorId", operatorId, "reason", reason,
                    "now", PaymentSettlementStore.utc(now)));
            return toConfigView(merchantId, store.settlement.selectConfigByMerchant(merchantId));
        });
    }

    @Override
    public SettlementConfigView getConfig(String merchantId, QueryContext context) {
        requireAdminOrSystemQuery(context);
        long id = parsePositiveId(merchantId, "merchantId");
        return toConfigView(id, store.settlement.selectConfigByMerchant(id));
    }

    @Override
    public SettlementOrderView resumeHeldOrder(ResumeSettlementCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long orderId = parsePositiveId(command.settlementOrderId(), "settlementOrderId");
        byte[] reqKey = requestKey("settlement.order.resume", command.context(), requestId);
        String fingerprint = sha256("RESUME|" + orderId);
        return transitionToWaiting(reqKey, "RESUME", orderId, fingerprint, "ON_HOLD", false);
    }

    @Override
    public SettlementOrderView retryFailedOrder(RetrySettlementCommand command) {
        noOuterTransaction();
        if (command == null || command.context() == null) throw invalid("missing command");
        requireAdminOrSystem(command.context());
        String requestId = requireRequestId(command.context());
        long orderId = parsePositiveId(command.settlementOrderId(), "settlementOrderId");
        byte[] reqKey = requestKey("settlement.order.retry", command.context(), requestId);
        String fingerprint = sha256("RETRY|" + orderId);
        return transitionToWaiting(reqKey, "RETRY", orderId, fingerprint, "SPLIT_FAILED", true);
    }

    @Override
    public SettlementOrderView retryFailedFallback(RetryFallbackCommand command) {
        noOuterTransaction();
        if (fallbackRetrier == null) {
            throw unavailable("fallback retry service is not configured");
        }
        return fallbackRetrier.retry(command);
    }

    private SettlementOrderView transitionToWaiting(byte[] reqKey, String actionType, long settlementOrderId,
            String fingerprint, String expectedStatus, boolean checkActiveInstructions) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            store.settlement.insertActionRequest(PaymentFoundationStore.values(
                    "id", nextId(), "requestKey", reqKey, "actionType", actionType,
                    "settlementOrderId", settlementOrderId, "fingerprint", fingerprint,
                    "now", PaymentSettlementStore.utc(now)));
            ActionRequestRow action = store.settlement.selectActionRequestForUpdate(reqKey);
            if (action == null) throw unavailable("action request binding unavailable");
            boolean sameAction = actionType.equals(action.actionType)
                    && settlementOrderId == action.settlementOrderId
                    && fingerprint.equals(action.requestFingerprint);
            if (!sameAction) throw idempotencyConflict("settlement action requestId conflict");

            OrderRow row = store.settlement.selectOrderByIdForUpdate(settlementOrderId);
            if (row == null) throw notFound("settlement order not found");
            // Replay: already moved out of expectedStatus under the same requestId.
            if (!expectedStatus.equals(row.status)) {
                if ("WAITING".equals(row.status) || "SPLITTING".equals(row.status)
                        || "SPLIT_SUCCEEDED".equals(row.status)) {
                    return toOrderView(row);
                }
                throw conflict("settlement order status " + row.status + " cannot " + actionType.toLowerCase());
            }
            if (row.eligibleAt == null) {
                throw conflict("settlement order has no eligible_at; verification has not completed");
            }
            if (checkActiveInstructions && store.settlement.countActiveSplitInstructions(row.id) > 0) {
                throw conflict("settlement order already has an active or succeeded split instruction");
            }
            int nextRound = row.dueScheduleRound + 1;
            int updated = store.settlement.resumeOrRetryOrderToWaiting(PaymentFoundationStore.values(
                    "id", row.id, "version", row.version, "expectedStatus", expectedStatus,
                    "dueScheduleRound", nextRound, "now", PaymentSettlementStore.utc(now)));
            if (updated != 1) throw conflict("settlement order changed concurrently");
            OffsetDateTime eligible = PaymentSettlementStore.offset(row.eligibleAt)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            OffsetDateTime runAt = (eligible.isAfter(now) ? eligible : now)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            tasks.enqueueAt(
                    SettlementDueTask.taskKey(row.id, nextRound),
                    SettlementDueTask.OWNER_MODULE,
                    SettlementDueTask.TASK_TYPE,
                    SettlementDueTask.BIZ_TYPE,
                    row.id,
                    (long) nextRound,
                    SettlementDueTask.payload(row.id, nextRound),
                    SettlementDueTask.MAX_RETRY_COUNT,
                    SettlementDueTask.RETRY_POLICY,
                    runAt);
            return toOrderView(store.settlement.selectOrderById(row.id));
        });
    }

    // ---- SettlementTriggerApi --------------------------------------------------------------

    @Override
    public SettlementOrderView registerForPayment(RegisterSettlementCommand command) {
        if (command == null || command.context() == null) throw invalid("missing command");
        requireSystem(command.context());
        long paymentId = parsePositiveId(command.paymentId(), "paymentId");
        Long orderId = parseNullableId(command.orderId());
        long merchantId = parsePositiveId(command.merchantId(), "merchantId");
        Long storeId = parseNullableId(command.storeId());
        String bizType = requireAscii(command.bizType(), 32, "bizType");
        String bizNo = requireAscii(command.bizNo(), 64, "bizNo");
        Long paidEventId = parseNullableId(command.sourcePaidEventId());

        return executeInTx(() -> {
            store.session();
            OffsetDateTime now = store.now();
            OrderRow existing = store.settlement.selectOrderByPaymentIdForUpdate(paymentId);
            if (existing != null) {
                if (!bizType.equals(existing.bizType) || !bizNo.equals(existing.bizNo)
                        || existing.merchantId != merchantId
                        || existing.paidAmount.compareTo(command.paidAmount()) != 0) {
                    throw conflict("settlement order already exists with different parameters");
                }
                return toOrderView(existing);
            }
            CenterRow payRow = store.center.selectCenterById(paymentId);
            if (payRow == null || !"PAID".equals(payRow.status())) {
                throw conflict("payment must be PAID before registering settlement");
            }
            if (!"1".equals(payRow.settleType())) {
                throw conflict("payment settle_type is not 1 (split-ledger)");
            }
            ConfigRow cfg = store.settlement.selectConfigByMerchant(merchantId);
            BigDecimal feeRate = cfg != null ? cfg.feeRate : SettlementAmountCalculator.DEFAULT_FEE_RATE;
            ShareCalculation calc;
            try {
                calc = SettlementAmountCalculator.computeBaseAndShare(
                        command.originalPrice(),
                        defaultZero(command.flashDiscountAmount()),
                        defaultZero(command.merchantDiscountAmount()),
                        command.paidAmount(),
                        SettlementAmountCalculator.ZERO,
                        feeRate);
            } catch (IllegalArgumentException bad) {
                throw invalid(bad.getMessage());
            }
            BigDecimal platformSubsidy = defaultZero(command.platformSubsidyAmount());
            if (platformSubsidy.signum() < 0 || platformSubsidy.scale() > 2) {
                throw invalid("invalid platformSubsidyAmount");
            }
            long id = nextId();
            try {
                store.settlement.insertOrder(PaymentFoundationStore.values(
                        "id", id,
                        "paymentId", paymentId,
                        "orderId", orderId,
                        "bizType", bizType,
                        "bizNo", bizNo,
                        "merchantId", merchantId,
                        "storeId", storeId,
                        "originalPrice", command.originalPrice().setScale(2),
                        "flashDiscountAmount", defaultZero(command.flashDiscountAmount()).setScale(2),
                        "merchantDiscountAmount", defaultZero(command.merchantDiscountAmount()).setScale(2),
                        "platformSubsidyAmount", platformSubsidy.setScale(2),
                        "paidAmount", command.paidAmount().setScale(2),
                        "splitBase", calc.splitBase(),
                        "feeRateSnapshot", feeRate,
                        "merchantShare", calc.merchantShare(),
                        "sourcePaidEventId", paidEventId,
                        "now", PaymentSettlementStore.utc(now)));
            } catch (DuplicateKeyException race) {
                OrderRow raced = store.settlement.selectOrderByPaymentIdForUpdate(paymentId);
                if (raced != null && bizType.equals(raced.bizType) && bizNo.equals(raced.bizNo)) {
                    return toOrderView(raced);
                }
                throw conflict("duplicate settlement biz key");
            }
            return toOrderView(store.settlement.selectOrderById(id));
        });
    }

    @Override
    public SettlementOrderView markVerified(MarkSettlementVerifiedCommand command) {
        if (command == null || command.context() == null || command.verifiedAt() == null) {
            throw invalid("missing verification command");
        }
        requireSystem(command.context());
        long id = parsePositiveId(command.settlementOrderId(), "settlementOrderId");
        Long verifiedEventId = parseNullableId(command.sourceVerifiedEventId());
        return executeInTx(() -> markVerifiedLocked(id, command.verifiedAt(), verifiedEventId));
    }

    /** Called inside an existing consumer transaction or standalone by markVerified. */
    public SettlementOrderView markVerifiedByOrderId(long orderId, OffsetDateTime verifiedAt, Long verifiedEventId) {
        return executeInTx(() -> {
            store.session();
            OrderRow row = store.settlement.selectOrderByOrderIdForUpdate(orderId);
            if (row == null) return null;
            return markVerifiedLocked(row.id, verifiedAt, verifiedEventId);
        });
    }

    private SettlementOrderView markVerifiedLocked(long settlementOrderId, OffsetDateTime verifiedAt,
            Long verifiedEventId) {
        store.session();
        OffsetDateTime now = store.now();
        OrderRow row = store.settlement.selectOrderByIdForUpdate(settlementOrderId);
        if (row == null) throw notFound("settlement order not found");
        if (!"PENDING_TRIGGER".equals(row.status)) {
            // Idempotent replay when already triggered, splitting, succeeded or canceled.
            return toOrderView(row);
        }
        ConfigRow cfg = store.settlement.selectConfigByMerchant(row.merchantId);
        int settleDays = cfg != null ? cfg.settleDays : SettlementAmountCalculator.DEFAULT_SETTLE_DAYS;
        OffsetDateTime utcVerified = verifiedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        OffsetDateTime eligibleAt = utcVerified.plusDays(settleDays);
        int round = row.dueScheduleRound + 1;
        int updated = store.settlement.markOrderWaiting(PaymentFoundationStore.values(
                "id", row.id, "version", row.version,
                "verifiedAt", PaymentSettlementStore.utc(utcVerified),
                "settleDays", settleDays,
                "eligibleAt", PaymentSettlementStore.utc(eligibleAt),
                "dueScheduleRound", round,
                "sourceVerifiedEventId", verifiedEventId,
                "now", PaymentSettlementStore.utc(now)));
        if (updated != 1) throw conflict("settlement order changed concurrently during verification");
        tasks.enqueueAt(
                SettlementDueTask.taskKey(row.id, round),
                SettlementDueTask.OWNER_MODULE,
                SettlementDueTask.TASK_TYPE,
                SettlementDueTask.BIZ_TYPE,
                row.id,
                (long) round,
                SettlementDueTask.payload(row.id, round),
                SettlementDueTask.MAX_RETRY_COUNT,
                SettlementDueTask.RETRY_POLICY,
                eligibleAt);
        return toOrderView(store.settlement.selectOrderById(row.id));
    }

    /** Called on RefundOrderCreatedEvent.v1: pauses WAITING settlement until refund finishes. */
    public void onRefundCreatedForOrder(long orderId) {
        executeInTx(() -> {
            store.session();
            OffsetDateTime now = store.now();
            OrderRow row = store.settlement.selectOrderByOrderIdForUpdate(orderId);
            if (row == null || !"WAITING".equals(row.status)) return null;
            store.settlement.markOrderOnHold(PaymentFoundationStore.values(
                    "id", row.id, "version", row.version,
                    "holdReason", "REFUND_IN_PROGRESS",
                    "errorCode", null, "errorMessage", null,
                    "now", PaymentSettlementStore.utc(now)));
            return null;
        });
    }

    /**
     * Called on RefundSucceededEvent.v1 (pre-split refunds): cancels on full refund, or recalculates
     * split_base / merchant_share proportionally on partial refund and releases REFUND_IN_PROGRESS hold.
     */
    public void onRefundSucceededForOrder(long orderId, BigDecimal totalRefundedAmount, boolean otherBlockerPresent) {
        executeInTx(() -> {
            store.session();
            OffsetDateTime now = store.now();
            OrderRow row = store.settlement.selectOrderByOrderIdForUpdate(orderId);
            if (row == null) return null;
            if (!Set.of("PENDING_TRIGGER", "WAITING", "ON_HOLD").contains(row.status)) {
                if (Set.of("SPLIT_SUCCEEDED", "REVERSING", "PARTIALLY_REVERSED", "REVERSED", "REVERSE_FAILED")
                        .contains(row.status)) {
                    ShareCalculation postCalc = SettlementAmountCalculator.computeBaseAndShare(
                            row.originalPrice, row.flashDiscountAmount, row.merchantDiscountAmount,
                            row.paidAmount, totalRefundedAmount, row.feeRateSnapshot);
                    BigDecimal splitAmt = row.splitAmount != null ? row.splitAmount : SettlementAmountCalculator.ZERO;
                    InstructionRow originSplit = store.settlement.selectSucceededSplitInstruction(row.id);
                    boolean modeA = SettlementModeDetector.isModeAInstruction(
                            originSplit, platformMerchantNo, platformReceiverNo);
                    BigDecimal merchantNetReversed = modeA
                            ? totalRefundedAmount.subtract(row.reversedAmount).max(SettlementAmountCalculator.ZERO)
                            : row.reversedAmount;
                    BigDecimal newSubsidyDue = SettlementAmountCalculator.recomputePlatformSubsidyDue(
                            postCalc.merchantShare(), splitAmt, merchantNetReversed);
                    store.settlement.updatePostSplitRefundProjection(PaymentFoundationStore.values(
                            "id", row.id, "version", row.version,
                            "refundedAmount", totalRefundedAmount.setScale(2),
                            "splitBase", postCalc.splitBase(),
                            "merchantShare", postCalc.merchantShare(),
                            "platformSubsidyDue", newSubsidyDue,
                            "now", PaymentSettlementStore.utc(now)));
                }
                return null;
            }
            ShareCalculation calc = SettlementAmountCalculator.computeBaseAndShare(
                    row.originalPrice, row.flashDiscountAmount, row.merchantDiscountAmount,
                    row.paidAmount, totalRefundedAmount, row.feeRateSnapshot);
            if (calc.fullyRefunded()) {
                store.settlement.markOrderCanceled(PaymentFoundationStore.values(
                        "id", row.id, "version", row.version,
                        "refundedAmount", totalRefundedAmount.setScale(2),
                        "now", PaymentSettlementStore.utc(now)));
                return null;
            }
            int updated = store.settlement.updateOrderRefundAmounts(PaymentFoundationStore.values(
                    "id", row.id, "version", row.version,
                    "refundedAmount", totalRefundedAmount.setScale(2),
                    "splitBase", calc.splitBase(),
                    "merchantShare", calc.merchantShare(),
                    "now", PaymentSettlementStore.utc(now)));
            if (updated != 1) throw conflict("settlement order changed concurrently on refund");
            if ("ON_HOLD".equals(row.status) && "REFUND_IN_PROGRESS".equals(row.holdReason)
                    && !otherBlockerPresent && row.eligibleAt != null) {
                OrderRow refreshed = store.settlement.selectOrderByIdForUpdate(row.id);
                int nextRound = refreshed.dueScheduleRound + 1;
                int resumed = store.settlement.resumeOrRetryOrderToWaiting(PaymentFoundationStore.values(
                        "id", refreshed.id, "version", refreshed.version, "expectedStatus", "ON_HOLD",
                        "dueScheduleRound", nextRound, "now", PaymentSettlementStore.utc(now)));
                if (resumed == 1) {
                    OffsetDateTime eligible = PaymentSettlementStore.offset(refreshed.eligibleAt)
                            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                    OffsetDateTime runAt = (eligible.isAfter(now) ? eligible : now)
                            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                    tasks.enqueueAt(
                            SettlementDueTask.taskKey(refreshed.id, nextRound),
                            SettlementDueTask.OWNER_MODULE,
                            SettlementDueTask.TASK_TYPE,
                            SettlementDueTask.BIZ_TYPE,
                            refreshed.id,
                            (long) nextRound,
                            SettlementDueTask.payload(refreshed.id, nextRound),
                            SettlementDueTask.MAX_RETRY_COUNT,
                            SettlementDueTask.RETRY_POLICY,
                            runAt);
                }
            }
            return null;
        });
    }

    // ---- SettlementQueryApi ----------------------------------------------------------------

    @Override
    public SettlementOrderView getById(String settlementOrderId, QueryContext context) {
        requireAdminOrSystemQuery(context);
        OrderRow row = store.settlement.selectOrderById(parsePositiveId(settlementOrderId, "settlementOrderId"));
        if (row == null) throw notFound("settlement order not found");
        return toOrderView(row);
    }

    @Override
    public SettlementOrderView getByPaymentId(String paymentId, QueryContext context) {
        requireAdminOrSystemQuery(context);
        OrderRow row = store.settlement.selectOrderByPaymentId(parsePositiveId(paymentId, "paymentId"));
        if (row == null) throw notFound("settlement order not found");
        return toOrderView(row);
    }

    @Override
    public SettlementPage<SettlementOrderView> listOrders(ListSettlementOrdersQuery query) {
        if (query == null) throw invalid("missing query");
        requireAdminOrSystemQuery(query.context());
        Long merchantId = parseNullableId(query.merchantId());
        String status = query.status() == null || query.status().isBlank() ? null : query.status().trim();
        if (status != null && !VALID_STATUSES.contains(status)) throw invalid("invalid status filter");
        int page = Math.max(1, query.page());
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
        int offset = (page - 1) * size;
        var params = PaymentFoundationStore.values(
                "merchantId", merchantId, "status", status, "limit", size, "offset", offset);
        long total = store.settlement.countOrders(params);
        List<SettlementOrderView> items = total == 0 ? List.of()
                : store.settlement.listOrders(params).stream().map(SettlementAdminService::toOrderView).toList();
        return new SettlementPage<>(items, total, page, size);
    }

    @Override
    public List<SettlementInstructionView> listInstructions(String settlementOrderId, QueryContext context) {
        requireAdminOrSystemQuery(context);
        long id = parsePositiveId(settlementOrderId, "settlementOrderId");
        return store.settlement.selectInstructionsByOrderId(id).stream()
                .map(SettlementAdminService::toInstructionView).toList();
    }

    @Override
    public SettlementInstructionView getInstructionById(String instructionId, QueryContext context) {
        requireAdminOrSystemQuery(context);
        InstructionRow row = store.settlement.selectInstructionById(parsePositiveId(instructionId, "instructionId"));
        if (row == null) throw notFound("settlement instruction not found");
        return toInstructionView(row);
    }

    // ---- Mappers & helpers -----------------------------------------------------------------

    private <T> T executeInTx(java.util.function.Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        return tx.execute(status -> work.get());
    }

    static SettlementConfigView toConfigView(long merchantId, ConfigRow row) {
        if (row == null) {
            return new SettlementConfigView(
                    IDS.toApi(merchantId),
                    SettlementAmountCalculator.DEFAULT_SETTLE_DAYS,
                    SettlementAmountCalculator.DEFAULT_FEE_RATE,
                    true, false, null, null, 0L, null);
        }
        return new SettlementConfigView(
                IDS.toApi(row.merchantId), row.settleDays, row.feeRate,
                row.enabled, true, row.lakalaMerchantNo, row.lakalaTermNo,
                row.version, PaymentSettlementStore.offset(row.updatedAt));
    }

    static SettlementOrderView toOrderView(OrderRow r) {
        return new SettlementOrderView(
                IDS.toApi(r.id),
                IDS.toApi(r.paymentId),
                r.orderId == null ? null : IDS.toApi(r.orderId),
                r.bizType, r.bizNo,
                IDS.toApi(r.merchantId),
                r.storeId == null ? null : IDS.toApi(r.storeId),
                r.originalPrice, r.flashDiscountAmount, r.merchantDiscountAmount, r.platformSubsidyAmount,
                r.paidAmount, r.refundedAmount, r.splitBase, r.feeRateSnapshot, r.merchantShare,
                r.splitAmount, r.platformRetainedAmount, r.platformSubsidyDue,
                r.actualMerchantAmount, r.channelSplitFee,
                PaymentSettlementStore.offset(r.verifiedAt), r.settleDaysSnapshot,
                PaymentSettlementStore.offset(r.eligibleAt), r.reversedAmount,
                r.activeInstructionId == null ? null : IDS.toApi(r.activeInstructionId),
                r.status, r.holdReason, r.lastErrorCode, r.lastErrorMessage,
                r.version, PaymentSettlementStore.offset(r.createdAt), PaymentSettlementStore.offset(r.updatedAt));
    }

    static SettlementInstructionView toInstructionView(InstructionRow r) {
        return new SettlementInstructionView(
                IDS.toApi(r.id),
                IDS.toApi(r.settlementOrderId),
                IDS.toApi(r.paymentId),
                r.refundOrderId == null ? null : IDS.toApi(r.refundOrderId),
                r.originInstructionId == null ? null : IDS.toApi(r.originInstructionId),
                IDS.toApi(r.merchantId),
                r.instructionType, r.attemptNo, r.outSeparateNo, r.separateNo,
                r.merchantNo, r.receiverNo, r.logNo, r.logDate,
                r.totalAmount, r.merchantAmount, r.platformAmount,
                r.actualSeparateAmount, r.actualMerchantAmount, r.totalFeeAmount, r.merchantFeeAmount,
                r.status, r.channelStatus, r.channelFinalStatus, r.accResultDesc, r.fallbackReason, r.finishDate,
                PaymentSettlementStore.offset(r.sentAt), PaymentSettlementStore.offset(r.terminalAt),
                r.version, PaymentSettlementStore.offset(r.createdAt), PaymentSettlementStore.offset(r.updatedAt));
    }

    private long nextId() {
        long value = ids.nextId();
        if (value <= 0) throw unavailable("snowflake id generator returned invalid id");
        return value;
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? SettlementAmountCalculator.ZERO : value;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("settlement command must not run inside caller transaction");
        }
    }

    private static void requireAdminOrSystem(CommandContext ctx) {
        if (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR && ctx.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "settlement command requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static void requireSystem(CommandContext ctx) {
        if (ctx.operatorType() != OperatorType.SYSTEM) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "settlement trigger requires SYSTEM");
        }
    }

    private static void requireAdminOrSystemQuery(QueryContext ctx) {
        if (ctx == null || (ctx.operatorType() != OperatorType.PLATFORM_OPERATOR
                && ctx.operatorType() != OperatorType.SYSTEM)) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "settlement query requires PLATFORM_OPERATOR or SYSTEM");
        }
    }

    private static String requireRequestId(CommandContext ctx) {
        String req = ctx.requestId();
        if (req == null || req.isBlank() || req.length() > 128) {
            throw invalid("requestId is required");
        }
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

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parsePositiveId(raw, "id");
    }

    private static String requireAscii(String raw, int max, String field) {
        if (raw == null || raw.isBlank() || raw.length() > max || !raw.matches("[A-Za-z0-9_-]+")) {
            throw invalid("invalid " + field);
        }
        return raw;
    }

    private static String normalizeAcquiringToken(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.length() > 32 || !trimmed.matches("[A-Za-z0-9]+")) {
            throw invalid("invalid " + field);
        }
        return trimmed;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String trimmed = reason.trim();
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
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
