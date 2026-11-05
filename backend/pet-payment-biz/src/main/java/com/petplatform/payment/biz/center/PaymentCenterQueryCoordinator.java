package com.petplatform.payment.biz.center;

import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.Dispatch;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.task.PaymentCenterQueryTask;
import com.petplatform.payment.biz.task.PaymentCenterQueryTask.Outcome;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One PAYCENTER_CHANNEL_QUERY attempt for a payment center row: read without lock, query the channel with the
 * ORIGINAL paymentNo outside any transaction, hand the verified result to the shared receipt writer.
 * It never re-sends a preorder and never infers a failure from a timeout.
 */
public final class PaymentCenterQueryCoordinator implements PaymentCenterQueryTask.Step {
    /** Contract 75 §5.3: keep confirming until expireAt + 5 minutes. */
    static final Duration GRACE = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private final PaymentChannel channel;
    private final PaymentCenterReceiptWriter receipts;
    private final PaymentDispatchService.Settings settings;
    private final Clock clock;
    private final PaymentCenterStore store;
    private final TransactionTemplate tx;

    public PaymentCenterQueryCoordinator(DataSource source, PaymentChannel channel,
            PaymentCenterReceiptWriter receipts, PaymentDispatchService.Settings settings, Clock clock) {
        this.channel = Objects.requireNonNull(channel);
        this.receipts = Objects.requireNonNull(receipts);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.store = new PaymentCenterStore(Objects.requireNonNull(source));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    @Override public Outcome execute(long paymentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("payment center query must not run inside a transaction");
        CenterRow row = store.center.selectCenterById(paymentId);
        Outcome settled = settled(row);
        if (settled != null) return settled;
        Dispatch dispatch = tx.execute(status -> {
            store.session();
            return store.shared.selectDispatchForUpdate(paymentId);
        });
        if (dispatch == null || dispatch.mayHaveSentAt() == null || dispatch.tradeRequestDate() == null)
            return Outcome.NOT_SENT;
        try {
            var input = new LakalaProtocol.QueryInput(settings.outOrgCode(),
                    LocalDateTime.ofInstant(clock.instant(), settings.channelTimeZone()).withNano(0),
                    row.merchantNo(), row.termNo(), Long.toString(row.no()), dispatch.tradeRequestDate());
            var expected = new LakalaProtocol.ExpectedPayment(row.merchantNo(), Long.toString(row.no()),
                    row.amount().movePointRight(2).longValueExact());
            PaymentChannel.VerifiedQuery verified = channel.lookup(input, expected, nonce());
            receipts.receiveQuery(paymentId, verified);
            if (PaymentCenterReceiptWriter.TERMINAL.contains(verified.result().tradeState().name())) {
                Outcome after = settled(store.center.selectCenterById(paymentId));
                return after == null ? Outcome.DONE : after;
            }
        } catch (RuntimeException uncertain) {
            // Indeterminate: fall through to the deadline check, keep the original paymentNo.
        }
        CenterRow current = store.center.selectCenterById(paymentId);
        Outcome now = settled(current);
        if (now != null) return now;
        LocalDateTime dbNow = PaymentFoundationStore.utc(store.now());
        return dbNow.isAfter(current.expires().plus(GRACE)) ? Outcome.EXHAUSTED : Outcome.PENDING;
    }

    /** Non-null when this task has nothing left to query. */
    private static Outcome settled(CenterRow row) {
        if (row == null || !row.isCenter()) return Outcome.NOT_CENTER;
        if ("RECONCILIATION_REQUIRED".equals(row.dispatchState())) return Outcome.RECONCILIATION_REQUIRED;
        if (Set.of("PAID", "FAILED", "CLOSED").contains(row.status())) return Outcome.DONE;
        return null;
    }

    private RequestNonce nonce() {
        char[] chars = new char[12];
        for (int i = 0; i < chars.length; i++) chars[i] = NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)];
        return new RequestNonce(Long.toString(clock.instant().getEpochSecond()), new String(chars));
    }
}
