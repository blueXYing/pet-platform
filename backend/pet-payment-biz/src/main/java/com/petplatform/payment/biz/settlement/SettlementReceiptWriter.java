package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.payment.biz.domain.service.SettlementAmountCalculator;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentSettlementStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.InstructionRow;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentSettlementMapper.OrderRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsHttpClient.VerifiedSplitOutcome;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.CmdType;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitDetail;
import com.petplatform.payment.biz.infrastructure.provider.LakalaSacsProtocol.SplitOutcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Contract 78 §4/§5/§8: single row-locked receipt writer shared by 1296 {@code query_plus} and 1297
 * split/fallback notification. Serializes concurrent query/notification races on {@code settlement_order}
 * then {@code settlement_instruction}, dedupes receipts by SHA-256, publishes split terminal Outbox
 * events at most once, and advances {@code FALLBACK} outcomes to {@code PARTIALLY_REVERSED}/{@code REVERSED}/{@code REVERSE_FAILED}.
 */
public final class SettlementReceiptWriter {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementReceiptWriter.class);
    public static final String SUCCEEDED_EVENT = "SettlementSplitSucceededEvent.v1";
    public static final String FAILED_EVENT = "SettlementSplitFailedEvent.v1";
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

    @FunctionalInterface
    public interface NotificationVerifier {
        SplitOutcome verify(Map<String, String> headers, byte[] rawBody);

        static NotificationVerifier fromPublicKey(PublicKey trustedNotificationKey) {
            Objects.requireNonNull(trustedNotificationKey, "trustedNotificationKey");
            return (headers, body) -> LakalaSacsProtocol.verifyNotification(headers, body, trustedNotificationKey);
        }
    }

    public record ReceiptResult(String instructionId, String settlementOrderId,
            boolean replayed, boolean terminal, boolean succeeded) {}

    private final SnowflakeIdGenerator ids;
    private final PaymentSettlementStore store;
    private final NotificationVerifier verifier;
    private final IntegrationEventPublisher publisher;
    private final String platformMerchantNo;
    private final String platformReceiverNo;
    private final TransactionTemplate tx;

    public SettlementReceiptWriter(DataSource source, SnowflakeIdGenerator ids,
            NotificationVerifier verifier, IntegrationEventPublisher publisher) {
        this(source, ids, verifier, publisher, null, null);
    }

    public SettlementReceiptWriter(DataSource source, SnowflakeIdGenerator ids,
            NotificationVerifier verifier, IntegrationEventPublisher publisher,
            String platformMerchantNo, String platformReceiverNo) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.platformMerchantNo = platformMerchantNo == null || platformMerchantNo.isBlank()
                ? null : platformMerchantNo.trim();
        this.platformReceiverNo = platformReceiverNo == null || platformReceiverNo.isBlank()
                ? null : platformReceiverNo.trim();
        this.store = new PaymentSettlementStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    /** Verified 1297 callback entry. Rejects bad signatures before any DB write. */
    public ReceiptResult receiveNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        if (headers == null || rawBody == null || rawBody.length == 0 || rawBody.length > 65_536) {
            throw invalid("invalid settlement notification");
        }
        byte[] copy = rawBody.clone();
        Map<String, String> safeHeaders = Map.copyOf(headers);
        SplitOutcome outcome;
        try {
            outcome = verifier.verify(safeHeaders, copy);
        } catch (LakalaProtocol.ProtocolException | LakalaSacsProtocol.ProtocolException badSig) {
            throw invalid("settlement notification signature or payload invalid");
        }
        String expectedType = switch (outcome.cmdType()) {
            case SEPARATE -> "SPLIT";
            case FALLBACK -> "FALLBACK";
            case CANCEL -> throw unavailable("unsupported settlement notification cmd_type: CANCEL");
        };
        InstructionRow hint = store.settlement.selectInstructionByOutSeparateNo(outcome.outSeparateNo());
        if (hint == null || !expectedType.equals(hint.instructionType)) {
            throw unavailable("unknown settlement instruction for notification");
        }
        String digest = sha256(copy);
        return persist(hint.settlementOrderId, hint.id, outcome, digest, "NOTIFICATION", null);
    }

    /** Package-private: only {@link SettlementQueryCoordinator} supplies a signature-verified 1296 query. */
    ReceiptResult receiveQuery(long settlementOrderId, long instructionId, VerifiedSplitOutcome query) {
        noOuterTransaction();
        if (query == null || query.outcome() == null || query.rawResponseSha256() == null
                || !query.rawResponseSha256().matches("[0-9a-f]{64}")) {
            throw unavailable("invalid verified settlement query");
        }
        if (query.outcome().cmdType() != CmdType.SEPARATE && query.outcome().cmdType() != CmdType.FALLBACK) {
            throw unavailable("unsupported query cmd_type: " + query.outcome().cmdType());
        }
        String digest = sha256(("QUERY\0" + query.rawResponseSha256()).getBytes(StandardCharsets.US_ASCII));
        return persist(settlementOrderId, instructionId, query.outcome(), digest, "QUERY", query.rawResponseSha256());
    }

    private ReceiptResult persist(long settlementOrderId, long instructionId, SplitOutcome outcome,
            String digest, String source, String channelResponseSha256) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();
            // Consistent lock ordering: settlement_order FIRST, then settlement_instruction.
            OrderRow order = store.settlement.selectOrderByIdForUpdate(settlementOrderId);
            if (order == null) throw unavailable("settlement order absent");
            InstructionRow instr = store.settlement.selectInstructionByIdForUpdate(instructionId);
            String expectedInstrType = outcome.cmdType() == CmdType.FALLBACK ? "FALLBACK" : "SPLIT";
            if (instr == null || instr.settlementOrderId != order.id || !expectedInstrType.equals(instr.instructionType)) {
                throw unavailable("settlement instruction absent or mismatched");
            }
            validateOutcomeMatchesInstruction(outcome, instr);

            boolean duplicate = store.settlement.countReceiptByDigest(PaymentFoundationStore.values(
                    "instructionId", instr.id, "digest", digest)) > 0;
            if (!duplicate) {
                store.settlement.insertInstructionReceipt(PaymentFoundationStore.values(
                        "id", nextId(),
                        "instructionId", instr.id,
                        "digest", digest,
                        "source", source,
                        "responseSha", channelResponseSha256,
                        "separateNo", outcome.separateNo(),
                        "cmdType", outcome.cmdType().name(),
                        "channelStatus", outcome.rawStatus(),
                        "channelFinalStatus", outcome.rawFinalStatus(),
                        "totalAmount", centsOrNull(outcome.totalAmountCents()),
                        "actualSeparateAmount", centsOrNull(outcome.actualSeparateCents()),
                        "totalFeeAmount", centsOrNull(outcome.totalFeeCents()),
                        "accResultDesc", outcome.accResultDesc(),
                        "finishDate", outcome.finishDate(),
                        "now", PaymentSettlementStore.utc(now)));
            }

            // Already terminal: verify consistency and replay idempotently without emitting a second event.
            if ("SUCCEEDED".equals(instr.status)) {
                if (outcome.isFail()) throw unavailable("conflicting FAIL receipt for already SUCCEEDED split");
                return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), true, true, true);
            }
            if ("FAILED".equals(instr.status)) {
                if (outcome.isSuccess()) throw unavailable("conflicting SUCCESS receipt for already FAILED instruction");
                return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), true, true, false);
            }

            if ("FALLBACK".equals(instr.instructionType)) {
                return applyFallbackOutcome(order, instr, outcome, digest, duplicate, now);
            }

            if (outcome.isSuccess()) {
                BigDecimal actualSeparate = outcome.actualSeparateCents() != null
                        ? BigDecimal.valueOf(outcome.actualSeparateCents(), 2) : instr.totalAmount;
                boolean modeA = SettlementModeDetector.isModeAInstruction(
                        instr, platformMerchantNo, platformReceiverNo);
                SplitDetail merchantDetail = (modeA
                        ? outcome.findMerchantDetail(instr.merchantNo)
                        : outcome.findReceiverDetail(instr.receiverNo)).orElse(null);
                BigDecimal actualMerchant = merchantDetail != null && merchantDetail.actualAmount() != null
                        ? merchantDetail.actualAmount() : instr.merchantAmount;
                BigDecimal merchantFee = merchantDetail != null && merchantDetail.feeAmount() != null
                        ? merchantDetail.feeAmount() : SettlementAmountCalculator.ZERO;
                BigDecimal totalFee = outcome.totalFeeCents() != null
                        ? BigDecimal.valueOf(outcome.totalFeeCents(), 2) : merchantFee;

                long eventId = nextId();
                int updatedInstr = store.settlement.markInstructionSucceeded(PaymentFoundationStore.values(
                        "id", instr.id, "version", instr.version,
                        "separateNo", outcome.separateNo(),
                        "actualSeparateAmount", actualSeparate,
                        "actualMerchantAmount", actualMerchant,
                        "totalFeeAmount", totalFee,
                        "merchantFeeAmount", merchantFee,
                        "channelStatus", outcome.rawStatus(),
                        "channelFinalStatus", outcome.rawFinalStatus(),
                        "terminalReceiptSha256", digest,
                        "finishDate", outcome.finishDate(),
                        "resultEventId", eventId,
                        "now", PaymentSettlementStore.utc(now)));
                if (updatedInstr != 1) throw unavailable("concurrent instruction state change");

                int updatedOrder = store.settlement.markOrderSplitSucceeded(PaymentFoundationStore.values(
                        "id", order.id, "version", order.version, "instructionId", instr.id,
                        "actualMerchantAmount", actualMerchant,
                        "channelSplitFee", totalFee,
                        "now", PaymentSettlementStore.utc(now)));
                if (updatedOrder != 1) throw unavailable("concurrent settlement order state change");

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("settlementOrderId", IDS.toApi(order.id));
                payload.put("instructionId", IDS.toApi(instr.id));
                payload.put("paymentId", IDS.toApi(order.paymentId));
                payload.put("orderId", order.orderId == null ? null : IDS.toApi(order.orderId));
                payload.put("bizType", order.bizType);
                payload.put("bizNo", order.bizNo);
                payload.put("merchantId", IDS.toApi(order.merchantId));
                payload.put("outSeparateNo", instr.outSeparateNo);
                payload.put("separateNo", outcome.separateNo());
                payload.put("splitAmount", instr.merchantAmount);
                payload.put("actualMerchantAmount", actualMerchant);
                payload.put("channelSplitFee", totalFee);
                payload.put("platformSubsidyDue", order.platformSubsidyDue);
                payload.put("settledAt", MILLIS.format(now.withOffsetSameInstant(ZoneOffset.UTC)));
                publisher.publish(new IntegrationEvent<>(
                        IDS.toApi(eventId), SUCCEEDED_EVENT, 1, now, "SETTLEMENT",
                        IDS.toApi(order.id), null, payload));
                return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), false, true, true);
            }

            if (outcome.isFail()) {
                long eventId = nextId();
                String desc = outcome.accResultDesc();
                int updatedInstr = store.settlement.markInstructionFailed(PaymentFoundationStore.values(
                        "id", instr.id, "version", instr.version,
                        "separateNo", outcome.separateNo(),
                        "channelStatus", outcome.rawStatus(),
                        "channelFinalStatus", outcome.rawFinalStatus(),
                        "accResultDesc", desc,
                        "terminalReceiptSha256", digest,
                        "finishDate", outcome.finishDate(),
                        "resultEventId", eventId,
                        "now", PaymentSettlementStore.utc(now)));
                if (updatedInstr != 1) throw unavailable("concurrent instruction state change");

                int updatedOrder = store.settlement.markOrderSplitFailed(PaymentFoundationStore.values(
                        "id", order.id, "version", order.version, "instructionId", instr.id,
                        "errorCode", "CHANNEL_SPLIT_FAILED",
                        "errorMessage", desc,
                        "now", PaymentSettlementStore.utc(now)));
                if (updatedOrder != 1) throw unavailable("concurrent settlement order state change");

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("settlementOrderId", IDS.toApi(order.id));
                payload.put("instructionId", IDS.toApi(instr.id));
                payload.put("paymentId", IDS.toApi(order.paymentId));
                payload.put("orderId", order.orderId == null ? null : IDS.toApi(order.orderId));
                payload.put("bizType", order.bizType);
                payload.put("bizNo", order.bizNo);
                payload.put("merchantId", IDS.toApi(order.merchantId));
                payload.put("outSeparateNo", instr.outSeparateNo);
                payload.put("separateNo", outcome.separateNo());
                payload.put("splitAmount", instr.merchantAmount);
                payload.put("reasonCode", "CHANNEL_SPLIT_FAILED");
                payload.put("reasonDetail", desc);
                payload.put("failedAt", MILLIS.format(now.withOffsetSameInstant(ZoneOffset.UTC)));
                publisher.publish(new IntegrationEvent<>(
                        IDS.toApi(eventId), FAILED_EVENT, 1, now, "SETTLEMENT",
                        IDS.toApi(order.id), null, payload));
                return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), false, true, false);
            }

            // Non-terminal (ACCEPTED / PROCESSING): record separate_no & latest channel status.
            store.settlement.markInstructionProcessing(PaymentFoundationStore.values(
                    "id", instr.id, "version", instr.version,
                    "separateNo", outcome.separateNo(),
                    "channelStatus", outcome.rawStatus(),
                    "channelFinalStatus", outcome.rawFinalStatus(),
                    "submitResponseSha256", null,
                    "now", PaymentSettlementStore.utc(now)));
            return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), duplicate, false, false);
        });
    }

    private ReceiptResult applyFallbackOutcome(OrderRow order, InstructionRow instr, SplitOutcome outcome,
            String digest, boolean duplicate, OffsetDateTime now) {
        if (outcome.isSuccess()) {
            BigDecimal actualSeparate = outcome.actualSeparateCents() != null
                    ? BigDecimal.valueOf(outcome.actualSeparateCents(), 2) : instr.totalAmount;
            BigDecimal totalFee = outcome.totalFeeCents() != null
                    ? BigDecimal.valueOf(outcome.totalFeeCents(), 2) : SettlementAmountCalculator.ZERO;
            InstructionRow originSplit = instr.originInstructionId != null
                    ? store.settlement.selectInstructionById(instr.originInstructionId)
                    : store.settlement.selectSucceededSplitInstruction(order.id);
            boolean modeA = SettlementModeDetector.isModeAInstruction(
                    originSplit != null ? originSplit : instr, platformMerchantNo, platformReceiverNo);

            int updatedInstr = store.settlement.markInstructionSucceeded(PaymentFoundationStore.values(
                    "id", instr.id, "version", instr.version,
                    "separateNo", outcome.separateNo(),
                    "actualSeparateAmount", actualSeparate,
                    "actualMerchantAmount", modeA ? SettlementAmountCalculator.ZERO : actualSeparate,
                    "totalFeeAmount", totalFee,
                    "merchantFeeAmount", SettlementAmountCalculator.ZERO,
                    "channelStatus", outcome.rawStatus(),
                    "channelFinalStatus", outcome.rawFinalStatus(),
                    "terminalReceiptSha256", digest,
                    "finishDate", outcome.finishDate(),
                    "resultEventId", null,
                    "now", PaymentSettlementStore.utc(now)));
            if (updatedInstr != 1) throw unavailable("concurrent fallback instruction state change");

            BigDecimal reversibleTotal;
            if (modeA) {
                reversibleTotal = originSplit != null
                        ? originSplit.platformAmount
                        : (order.platformRetainedAmount != null ? order.platformRetainedAmount : instr.totalAmount);
            } else {
                reversibleTotal = originSplit != null
                        ? originSplit.merchantAmount
                        : (order.splitAmount != null ? order.splitAmount : instr.merchantAmount);
            }
            BigDecimal newReversed = order.reversedAmount.add(instr.totalAmount).setScale(2);
            String newOrderStatus = newReversed.compareTo(reversibleTotal) >= 0
                    ? "REVERSED" : "PARTIALLY_REVERSED";

            int updatedOrder = store.settlement.markOrderFallbackSucceeded(PaymentFoundationStore.values(
                    "id", order.id, "version", order.version, "instructionId", instr.id,
                    "newStatus", newOrderStatus,
                    "reversedAmount", newReversed,
                    "now", PaymentSettlementStore.utc(now)));
            if (updatedOrder != 1) throw unavailable("concurrent settlement order state change on fallback");
            return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), false, true, true);
        }

        if (outcome.isFail()) {
            String desc = outcome.accResultDesc();
            int updatedInstr = store.settlement.markInstructionFailed(PaymentFoundationStore.values(
                    "id", instr.id, "version", instr.version,
                    "separateNo", outcome.separateNo(),
                    "channelStatus", outcome.rawStatus(),
                    "channelFinalStatus", outcome.rawFinalStatus(),
                    "accResultDesc", desc,
                    "terminalReceiptSha256", digest,
                    "finishDate", outcome.finishDate(),
                    "resultEventId", null,
                    "now", PaymentSettlementStore.utc(now)));
            if (updatedInstr != 1) throw unavailable("concurrent fallback instruction state change");

            int updatedOrder = store.settlement.markOrderReverseFailed(PaymentFoundationStore.values(
                    "id", order.id, "version", order.version,
                    "errorCode", "CHANNEL_FALLBACK_FAILED",
                    "errorMessage", desc,
                    "now", PaymentSettlementStore.utc(now)));
            if (updatedOrder != 1) throw unavailable("concurrent settlement order state change on fallback fail");

            String orderIdText = IDS.toApi(order.id);
            String instrIdText = IDS.toApi(instr.id);
            String refundIdText = instr.refundOrderId == null ? "null" : IDS.toApi(instr.refundOrderId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    LOG.error("Settlement fallback failed; refund paused pending manual handling "
                                    + "settlementOrderId={} instructionId={} refundOrderId={} reason={}",
                            orderIdText, instrIdText, refundIdText, desc);
                }
            });
            return new ReceiptResult(instrIdText, orderIdText, false, true, false);
        }

        store.settlement.markInstructionProcessing(PaymentFoundationStore.values(
                "id", instr.id, "version", instr.version,
                "separateNo", outcome.separateNo(),
                "channelStatus", outcome.rawStatus(),
                "channelFinalStatus", outcome.rawFinalStatus(),
                "submitResponseSha256", null,
                "now", PaymentSettlementStore.utc(now)));
        return new ReceiptResult(IDS.toApi(instr.id), IDS.toApi(order.id), duplicate, false, false);
    }

    private static void validateOutcomeMatchesInstruction(SplitOutcome outcome, InstructionRow instr) {
        if (!instr.outSeparateNo.equals(outcome.outSeparateNo())) {
            throw unavailable("out_separate_no mismatch");
        }
        if (instr.separateNo != null && outcome.separateNo() != null
                && !instr.separateNo.equals(outcome.separateNo())) {
            throw unavailable("separate_no mismatch");
        }
        if (outcome.logNo() != null && !instr.logNo.equals(outcome.logNo())) {
            throw unavailable("log_no mismatch");
        }
        if (outcome.logDate() != null && !instr.logDate.equals(outcome.logDate())) {
            throw unavailable("log_date mismatch");
        }
        if (outcome.totalAmountCents() != null
                && BigDecimal.valueOf(outcome.totalAmountCents(), 2).compareTo(instr.totalAmount) != 0) {
            throw unavailable("total_amt mismatch");
        }
    }

    private static BigDecimal centsOrNull(Long cents) {
        return cents == null ? null : BigDecimal.valueOf(cents, 2);
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable("invalid snowflake id");
        return id;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("settlement receipt writer requires no outer transaction");
        }
    }

    private static ApiException invalid(String msg) { return new ApiException(CommonApiCodes.INVALID_ARGUMENT, msg); }
    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
