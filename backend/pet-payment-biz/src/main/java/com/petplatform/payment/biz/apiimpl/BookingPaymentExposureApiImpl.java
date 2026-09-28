package com.petplatform.payment.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.payment.api.query.BookingPaymentExposureApi;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore.Row;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PAYMENT-owned current proof, always reread inside ORDER's guarded expiry transaction. */
public final class BookingPaymentExposureApiImpl implements BookingPaymentExposureApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final JdbcTemplate jdbc;
    private final PaymentFoundationStore store;
    private final boolean allowDispatchEvidence;

    public BookingPaymentExposureApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this(source, guard, false);
    }

    public BookingPaymentExposureApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            boolean allowDispatchEvidence) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.jdbc = new JdbcTemplate(source);
        this.store = new PaymentFoundationStore(source);
        this.allowDispatchEvidence = allowDispatchEvidence;
    }

    @Override public void requireNoPayment(String orderId, String storeId, QueryContext context) {
        try {
            long order = IDS.fromApi(orderId);
            guard.requireHeld(storeId, source);
            if (!jdbc.query("SELECT id FROM payment_order WHERE order_id=? LIMIT 1 FOR UPDATE",
                    (rs, row) -> rs.getLong(1), order).isEmpty() || hasOrphanTransaction())
                throw unavailable();
        } catch (RuntimeException failure) {
            rollback();
            throw unavailable();
        }
    }

    @Override public void requireSafeToExpire(String orderId, String storeId,
            OffsetDateTime expectedDeadline, QueryContext context) {
        if (!allowDispatchEvidence) {
            requireNoPayment(orderId, storeId, context);
            return;
        }
        try {
            long order = IDS.fromApi(orderId);
            long storeIdValue = IDS.fromApi(storeId);
            if (expectedDeadline == null) throw unavailable();
            guard.requireHeld(storeId, source);
            Row payment = store.byOrder(order);
            if (payment == null) {
                if (hasOrphanTransaction() || hasOrphanReceipt() || hasOrphanDispatch())
                    throw unavailable();
                return;
            }
            if (payment.id() <= 0 || payment.no() <= 0 || payment.orderId() != order
                    || payment.storeId() != storeIdValue || payment.merchantId() <= 0
                    || payment.userId() <= 0 || payment.amount() == null
                    || payment.amount().signum() <= 0 || payment.expires() == null
                    || !payment.expires().equals(PaymentFoundationStore.utc(expectedDeadline))
                    || !"LAKALA_WECHAT".equals(payment.channel())
                    || !"CNY".equals(payment.currency()) || blank(payment.merchantNo())
                    || blank(payment.termNo()) || blank(payment.subAppId())
                    || hasOrphanTransaction() || hasOrphanReceipt() || hasOrphanDispatch()
                    || hasTransaction(payment.id())
                    || payment.successEventId() != null || payment.paidAt() != null
                    || payment.paidAmount() != null) throw unavailable();

            List<Dispatch> dispatches = jdbc.query("""
                    SELECT state,preorder_req_time,trade_req_date,timeout_express_minutes,
                           may_have_sent_at,preorder_response_sha256,parameters_ciphertext,
                           parameters_iv,parameter_valid_until,fenced_at,close_may_have_sent_at,
                           close_response_sha256,terminal_query_sha256,terminal_confirmed_at,
                           terminal_close_capability
                    FROM payment_dispatch WHERE payment_id=? FOR UPDATE
                    """, (rs, row) -> new Dispatch(rs.getString(1),
                    rs.getObject(2, LocalDateTime.class), rs.getObject(3, java.time.LocalDate.class),
                    rs.getObject(4, Integer.class), rs.getObject(5, LocalDateTime.class),
                    rs.getString(6), rs.getBytes(7), rs.getBytes(8),
                    rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class),
                    rs.getObject(11, LocalDateTime.class), rs.getString(12), rs.getString(13),
                    rs.getObject(14, LocalDateTime.class), rs.getInt(15)), payment.id());
            if (dispatches.size() != 1) throw unavailable();
            Dispatch dispatch = dispatches.getFirst();
            List<Receipt> receipts = jdbc.query("""
                    SELECT receipt_source,channel_status,receipt_sha256,
                           channel_response_sha256,paid_amount,total_amount,channel_trade_no
                    FROM payment_channel_receipt WHERE payment_id=? FOR UPDATE
                    """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                    rs.getBigDecimal(6), rs.getString(7)), payment.id());

            if ("FENCED_UNSENT".equals(dispatch.state())) {
                if (!"INIT".equals(payment.status()) || !"PREPARED".equals(payment.dispatchState())
                        || payment.tradeNo() != null || dispatch.fencedAt() == null
                        || dispatch.mayHaveSentAt() != null || dispatch.preorderReqTime() != null
                        || dispatch.tradeReqDate() != null || dispatch.timeoutMinutes() != null
                        || dispatch.preorderResponseSha() != null
                        || dispatch.parametersCiphertext() != null || dispatch.parametersIv() != null
                        || dispatch.parameterValidUntil() != null || dispatch.closeCapability() != 0
                        || dispatch.closeMayHaveSentAt() != null || dispatch.closeResponseSha() != null
                        || dispatch.terminalQuerySha() != null || dispatch.terminalConfirmedAt() != null
                        || !receipts.isEmpty()) throw unavailable();
                return;
            }
            if (!"TERMINAL_CLOSED".equals(dispatch.state())
                    || !"CLOSED".equals(payment.status())
                    || blank(payment.tradeNo())
                    || !"OBSERVED".equals(payment.dispatchState())
                    || dispatch.fencedAt() == null || dispatch.preorderReqTime() == null
                    || dispatch.tradeReqDate() == null
                    || !dispatch.tradeReqDate().equals(dispatch.preorderReqTime().toLocalDate())
                    || dispatch.timeoutMinutes() == null || dispatch.timeoutMinutes() < 1
                    || dispatch.timeoutMinutes() > 10
                    || dispatch.mayHaveSentAt() == null || dispatch.closeMayHaveSentAt() == null
                    || dispatch.terminalConfirmedAt() == null || dispatch.closeCapability() != 1
                    || !sha(dispatch.preorderResponseSha()) || !sha(dispatch.closeResponseSha())
                    || !sha(dispatch.terminalQuerySha())) throw unavailable();

            boolean closeReceipt = false;
            boolean terminalQuery = false;
            for (Receipt receipt : receipts) {
                if (!Set.of("NOTIFICATION", "QUERY", "CLOSE").contains(receipt.source())
                        || !Set.of("INIT", "CREATE", "SUCCESS", "FAIL", "DEAL", "UNKNOWN",
                                "CLOSE", "PART_REFUND", "REFUND", "REVOKED")
                                .contains(receipt.status())) throw unavailable();
                if ("SUCCESS".equals(receipt.status()) || "REFUND".equals(receipt.status())
                        || "PART_REFUND".equals(receipt.status()) || "REVOKED".equals(receipt.status())
                        || receipt.paidAmount() != null && receipt.paidAmount().signum() != 0)
                    throw unavailable();
                if ("CLOSE".equals(receipt.source()) && "CLOSE".equals(receipt.status())
                        && sha(receipt.digest()) && sha(receipt.responseSha())
                        && receipt.responseSha().equals(dispatch.closeResponseSha())
                        && receipt.digest().equals(domainDigest("CLOSE", receipt.responseSha()))
                        && payment.tradeNo().equals(receipt.tradeNo())) closeReceipt = true;
                if ("QUERY".equals(receipt.source()) && "CLOSE".equals(receipt.status())
                        && sha(receipt.digest()) && sha(receipt.responseSha())
                        && receipt.responseSha().equals(dispatch.terminalQuerySha())
                        && receipt.digest().equals(domainDigest("QUERY", receipt.responseSha()))
                        && receipt.totalAmount() != null && receipt.totalAmount().compareTo(payment.amount()) == 0
                        && payment.tradeNo().equals(receipt.tradeNo())) terminalQuery = true;
            }
            if (!closeReceipt || !terminalQuery) throw unavailable();
        } catch (RuntimeException failure) {
            rollback();
            throw unavailable();
        }
    }

    private boolean hasOrphanTransaction() {
        return !jdbc.query("""
                SELECT t.id FROM payment_transaction t
                LEFT JOIN payment_order p ON p.id=t.payment_id
                WHERE p.id IS NULL LIMIT 1
                """, (rs, row) -> rs.getLong(1)).isEmpty();
    }

    private boolean hasTransaction(long paymentId) {
        return !jdbc.query("SELECT id FROM payment_transaction WHERE payment_id=? LIMIT 1 FOR UPDATE",
                (rs, row) -> rs.getLong(1), paymentId).isEmpty();
    }

    private boolean hasOrphanReceipt() {
        return !jdbc.query("""
                SELECT r.id FROM payment_channel_receipt r
                LEFT JOIN payment_order p ON p.id=r.payment_id
                WHERE p.id IS NULL LIMIT 1
                """, (rs, row) -> rs.getLong(1)).isEmpty();
    }

    private boolean hasOrphanDispatch() {
        return !jdbc.query("""
                SELECT d.payment_id FROM payment_dispatch d
                LEFT JOIN payment_order p ON p.id=d.payment_id
                WHERE p.id IS NULL LIMIT 1
                """, (rs, row) -> rs.getLong(1)).isEmpty();
    }

    private void rollback() {
        if (TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
            holder.setRollbackOnly();
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static boolean sha(String text) { return text != null && text.matches("[0-9a-fA-F]{64}"); }
    private static String domainDigest(String domain, String rawSha) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((domain + "\0" + rawSha).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw unavailable(); }
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "booking payment exposure requires reconciliation");
    }
    private record Dispatch(String state, LocalDateTime preorderReqTime,
            java.time.LocalDate tradeReqDate, Integer timeoutMinutes, LocalDateTime mayHaveSentAt,
            String preorderResponseSha, byte[] parametersCiphertext, byte[] parametersIv,
            LocalDateTime parameterValidUntil, LocalDateTime fencedAt,
            LocalDateTime closeMayHaveSentAt, String closeResponseSha, String terminalQuerySha,
            LocalDateTime terminalConfirmedAt, int closeCapability) {}
    private record Receipt(String source, String status, String digest, String responseSha,
            BigDecimal paidAmount, BigDecimal totalAmount, String tradeNo) {}
}
