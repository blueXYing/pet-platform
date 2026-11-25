package com.petplatform.payment.biz.settlement;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentWalletStore;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper.WithdrawalRow;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.DrawState;
import com.petplatform.payment.biz.infrastructure.provider.LakalaLaepProtocol.WithdrawOutcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic receipt writer for Lakala LAEP 1271 query & 1272 notification outcomes (Stage 5).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>1271 x 1272 concurrency serialized by {@code withdrawal_order FOR UPDATE}.</li>
 *   <li>Duplicate receipts deduped by {@code withdrawal_receipt(withdrawal_id, receipt_sha256)}.</li>
 *   <li>Terminal states ({@code SUCCEEDED} / {@code FAILED}) are immutable; {@code WithdrawalSucceededEvent.v1}
 *       or {@code WithdrawalFailedEvent.v1} is published via Transactional Outbox at most once
 *       (backed by {@code withdrawal_order.result_event_id} unique key).</li>
 * </ul>
 */
public final class WalletReceiptWriter {
    public static final String SUCCEEDED_EVENT = "WithdrawalSucceededEvent.v1";
    public static final String FAILED_EVENT = "WithdrawalFailedEvent.v1";

    private static final Logger LOG = LoggerFactory.getLogger(WalletReceiptWriter.class);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @FunctionalInterface
    public interface NotificationVerifier {
        WithdrawOutcome verify(Map<String, String> headers, byte[] rawBody);

        static NotificationVerifier fromPublicKey(PublicKey trustedNotificationKey) {
            Objects.requireNonNull(trustedNotificationKey, "trustedNotificationKey");
            return (headers, rawBody) -> LakalaLaepProtocol.verifyWithdrawalNotification(
                    headers, rawBody, trustedNotificationKey);
        }
    }

    public record ReceiptResult(String withdrawalId, boolean duplicate, boolean terminal, boolean succeeded) {}

    private final SnowflakeIdGenerator ids;
    private final PaymentWalletStore store;
    private final NotificationVerifier verifier;
    private final TransactionalOutboxPublisher publisher;
    private final ZoneId channelTimeZone;
    private final TransactionTemplate tx;

    public WalletReceiptWriter(
            DataSource source,
            SnowflakeIdGenerator ids,
            NotificationVerifier verifier,
            TransactionalOutboxPublisher publisher,
            ZoneId channelTimeZone) {
        Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.channelTimeZone = Objects.requireNonNull(channelTimeZone, "channelTimeZone");
        this.store = new PaymentWalletStore(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    /** Handles a public 1272 async notification: verifies 1009 signature outside DB tx, then applies atomically. */
    public ReceiptResult applyNotification(Map<String, String> headers, byte[] rawBody) {
        noOuterTransaction();
        if (headers == null || rawBody == null || rawBody.length == 0) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "withdrawal notification headers and body required");
        }
        final WithdrawOutcome outcome;
        try {
            outcome = verifier.verify(headers, rawBody);
        } catch (RuntimeException badSig) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "withdrawal notification verification failed");
        }
        return applyOutcome(null, outcome, "NOTIFICATION", sha256(rawBody));
    }

    /** Applies a verified 1271 query outcome for a known {@code withdrawalId}. */
    public ReceiptResult applyQueryOutcome(long withdrawalId, WithdrawOutcome outcome, String rawResponseSha256) {
        noOuterTransaction();
        Objects.requireNonNull(outcome, "outcome");
        return applyOutcome(withdrawalId, outcome, "QUERY", rawResponseSha256);
    }

    /** Publishes a deterministic submit-time rejection as FAILED + WithdrawalFailedEvent.v1 in a single tx. */
    public void recordSubmitRejection(long withdrawalId, long expectedVersion, String reason, OffsetDateTime now) {
        long eventId = nextId();
        LocalDateTime utcNow = PaymentWalletStore.utc(now);
        String truncated = truncate(reason, 256);
        int updated = store.wallet.markFailed(PaymentFoundationStore.values(
                "id", withdrawalId,
                "version", expectedVersion,
                "drawJnl", null,
                "channelState", "REJECTED",
                "failReason", truncated,
                "terminalReceiptSha256", null,
                "resultEventId", eventId,
                "finishedAt", utcNow,
                "now", utcNow));
        if (updated != 1) throw unavailable("concurrent withdrawal state change");

        WithdrawalRow row = store.wallet.selectById(withdrawalId);
        publishFailed(row, eventId, truncated, now);
    }

    private ReceiptResult applyOutcome(Long expectedWithdrawalId, WithdrawOutcome outcome,
            String source, String responseSha256) {
        return tx.execute(status -> {
            store.session();
            OffsetDateTime now = store.now();

            WithdrawalRow row = null;
            if (expectedWithdrawalId != null) {
                row = store.wallet.selectByIdForUpdate(expectedWithdrawalId);
            } else {
                if (outcome.merOrderNo() != null && !outcome.merOrderNo().isBlank()) {
                    row = store.wallet.selectByMerOrderNoForUpdate(outcome.merOrderNo());
                }
                if (row == null && outcome.drawJnl() != null && !outcome.drawJnl().isBlank()) {
                    row = store.wallet.selectByDrawJnlForUpdate(outcome.drawJnl());
                }
            }
            if (row == null) {
                throw new ApiException(CommonApiCodes.NOT_FOUND, "withdrawal order not found");
            }

            // Cross-check amount and receiver consistency to prevent tampered/mismatched receipts.
            if (outcome.drawAmt().compareTo(row.amount) != 0) {
                throw new ApiException(CommonApiCodes.CONFLICT, "withdrawal receipt amount mismatch");
            }
            if (outcome.mercId() != null && !outcome.mercId().isBlank() && !row.receiverNo.equals(outcome.mercId())) {
                throw new ApiException(CommonApiCodes.CONFLICT, "withdrawal receipt receiver mismatch");
            }
            if (outcome.merOrderNo() != null && !outcome.merOrderNo().isBlank()
                    && !row.merOrderNo.equals(outcome.merOrderNo())) {
                throw new ApiException(CommonApiCodes.CONFLICT, "withdrawal receipt merOrderNo mismatch");
            }
            if (row.drawJnl != null && !row.drawJnl.equals(outcome.drawJnl())) {
                throw new ApiException(CommonApiCodes.CONFLICT, "withdrawal receipt drawJnl mismatch");
            }

            String digest = canonicalDigest(row.id, outcome);
            LocalDateTime completeUtc = toUtc(outcome.completeTime());
            int inserted = store.wallet.insertReceiptIgnore(PaymentFoundationStore.values(
                    "id", nextId(),
                    "withdrawalId", row.id,
                    "receiptSha256", digest,
                    "receiptSource", source,
                    "channelResponseSha256", responseSha256,
                    "drawJnl", outcome.drawJnl(),
                    "drawState", outcome.drawState().wire(),
                    "drawAmount", outcome.drawAmt(),
                    "feeAmount", outcome.drawFee(),
                    "memo", truncate(outcome.memo(), 256),
                    "completeTime", completeUtc,
                    "receivedAt", PaymentWalletStore.utc(now)));
            boolean duplicate = inserted == 0;

            // Terminal state immutability: never flip an already-terminal withdrawal or re-emit Outbox.
            if ("SUCCEEDED".equals(row.status) || "FAILED".equals(row.status)) {
                boolean wasSuccess = "SUCCEEDED".equals(row.status);
                if ((wasSuccess && outcome.drawState() == DrawState.FAILED)
                        || (!wasSuccess && outcome.drawState() == DrawState.SUCCESS)) {
                    LOG.error("Conflicting terminal withdrawal receipt ignored withdrawalId={} local={} channel={}",
                            IDS.toApi(row.id), row.status, outcome.drawState().wire());
                }
                return new ReceiptResult(IDS.toApi(row.id), duplicate, true, wasSuccess);
            }

            if (outcome.drawState() == DrawState.SUCCESS) {
                long eventId = nextId();
                BigDecimal fee = outcome.drawFee() == null ? ZERO : outcome.drawFee().setScale(2);
                BigDecimal net = outcome.netAmount();
                OffsetDateTime finishedAt = completeUtc != null ? PaymentWalletStore.offset(completeUtc) : now;
                int updated = store.wallet.markSucceeded(PaymentFoundationStore.values(
                        "id", row.id,
                        "version", row.version,
                        "drawJnl", outcome.drawJnl(),
                        "channelState", outcome.drawState().wire(),
                        "feeAmount", fee,
                        "netAmount", net,
                        "settleNo", outcome.settleNo(),
                        "terminalReceiptSha256", digest,
                        "resultEventId", eventId,
                        "finishedAt", PaymentWalletStore.utc(finishedAt),
                        "now", PaymentWalletStore.utc(now)));
                if (updated != 1) throw unavailable("concurrent withdrawal state change");

                publishSucceeded(row, eventId, fee, net, outcome.drawJnl(), finishedAt);
                return new ReceiptResult(IDS.toApi(row.id), duplicate, true, true);
            }

            if (outcome.drawState() == DrawState.FAILED) {
                long eventId = nextId();
                String reason = truncate(outcome.memo() != null ? outcome.memo() : "CHANNEL_WITHDRAW_FAILED", 256);
                OffsetDateTime finishedAt = completeUtc != null ? PaymentWalletStore.offset(completeUtc) : now;
                int updated = store.wallet.markFailed(PaymentFoundationStore.values(
                        "id", row.id,
                        "version", row.version,
                        "drawJnl", outcome.drawJnl(),
                        "channelState", outcome.drawState().wire(),
                        "failReason", reason,
                        "terminalReceiptSha256", digest,
                        "resultEventId", eventId,
                        "finishedAt", PaymentWalletStore.utc(finishedAt),
                        "now", PaymentWalletStore.utc(now)));
                if (updated != 1) throw unavailable("concurrent withdrawal state change");

                publishFailed(row, eventId, reason, finishedAt);
                return new ReceiptResult(IDS.toApi(row.id), duplicate, true, false);
            }

            // Non-terminal (ACCEPTED / FREEZE / PROCESSING): record latest channel state & drawJnl.
            BigDecimal fee = outcome.drawFee();
            BigDecimal net = fee != null ? outcome.netAmount() : null;
            store.wallet.updateNonTerminalState(PaymentFoundationStore.values(
                    "id", row.id,
                    "version", row.version,
                    "drawJnl", outcome.drawJnl(),
                    "channelState", outcome.drawState().wire(),
                    "feeAmount", fee,
                    "netAmount", net,
                    "settleNo", outcome.settleNo(),
                    "now", PaymentWalletStore.utc(now)));
            return new ReceiptResult(IDS.toApi(row.id), duplicate, false, false);
        });
    }

    private void publishSucceeded(WithdrawalRow row, long eventId, BigDecimal fee, BigDecimal net,
            String drawJnl, OffsetDateTime succeededAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawalId", IDS.toApi(row.id));
        payload.put("merOrderNo", row.merOrderNo);
        payload.put("merchantId", IDS.toApi(row.merchantId));
        payload.put("receiverNo", row.receiverNo);
        payload.put("amount", row.amount.setScale(2));
        payload.put("feeAmount", fee.setScale(2));
        payload.put("netAmount", net.setScale(2));
        payload.put("drawJnl", drawJnl);
        payload.put("succeededAt", MILLIS.format(succeededAt.withOffsetSameInstant(ZoneOffset.UTC)));
        publisher.publish(new IntegrationEvent<>(
                IDS.toApi(eventId), SUCCEEDED_EVENT, 1, succeededAt, "WITHDRAWAL",
                IDS.toApi(row.id), null, payload));
    }

    private void publishFailed(WithdrawalRow row, long eventId, String failReason, OffsetDateTime failedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawalId", IDS.toApi(row.id));
        payload.put("merOrderNo", row.merOrderNo);
        payload.put("merchantId", IDS.toApi(row.merchantId));
        payload.put("receiverNo", row.receiverNo);
        payload.put("amount", row.amount.setScale(2));
        payload.put("drawJnl", row.drawJnl);
        payload.put("failReason", failReason);
        payload.put("failedAt", MILLIS.format(failedAt.withOffsetSameInstant(ZoneOffset.UTC)));
        publisher.publish(new IntegrationEvent<>(
                IDS.toApi(eventId), FAILED_EVENT, 1, failedAt, "WITHDRAWAL",
                IDS.toApi(row.id), null, payload));
    }

    private LocalDateTime toUtc(LocalDateTime channelLocal) {
        if (channelLocal == null) return null;
        return channelLocal.atZone(channelTimeZone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static String canonicalDigest(long withdrawalId, WithdrawOutcome outcome) {
        String raw = withdrawalId + "|" + outcome.drawJnl() + "|" + outcome.drawState().wire()
                + "|" + outcome.drawAmt().toPlainString()
                + "|" + (outcome.drawFee() == null ? "" : outcome.drawFee().toPlainString())
                + "|" + (outcome.settleNo() == null ? "" : outcome.settleNo())
                + "|" + (outcome.completeTime() == null ? "" : outcome.completeTime());
        return sha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable("snowflake id generator returned invalid id");
        return id;
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("withdrawal receipt operation must not run inside caller transaction");
        }
    }

    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
