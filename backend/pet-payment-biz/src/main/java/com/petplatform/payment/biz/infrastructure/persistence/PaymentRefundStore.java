package com.petplatform.payment.biz.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** PAYMENT-owned refund dispatch and minimum signed receipt facts (SQL43). */
public final class PaymentRefundStore {
    private final JdbcTemplate jdbc;

    public PaymentRefundStore(DataSource source) { jdbc = new JdbcTemplate(source); }

    public void session() {
        jdbc.execute("SET SESSION time_zone='+00:00'");
        jdbc.execute("SET SESSION innodb_lock_wait_timeout=2");
    }

    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)
                .atOffset(ZoneOffset.UTC);
    }

    public Dispatch byRefund(long refundOrderId, boolean lock) {
        List<Dispatch> rows = jdbc.query("SELECT refund_order_id,refund_no,payment_id,payment_no,"
                        + "order_id,store_id,merchant_no,term_no,original_channel_trade_no,"
                        + "payment_success_event_id,original_paid_amount,refund_amount,currency,"
                        + "refund_binding_version,channel_request_no,request_sha256,state,"
                        + "may_have_sent_at,query_not_before,channel_refund_no,terminal_receipt_sha256,"
                        + "terminal_result_at,version,original_request_at "
                        + "FROM payment_refund_dispatch WHERE refund_order_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Dispatch(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getLong(10),
                        rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13),
                        rs.getLong(14), rs.getString(15), rs.getString(16), rs.getString(17),
                        rs.getObject(18, LocalDateTime.class), rs.getObject(19, LocalDateTime.class),
                        rs.getString(20), rs.getString(21), rs.getObject(22, LocalDateTime.class),
                        rs.getLong(23), rs.getObject(24, LocalDateTime.class)), refundOrderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insert(Dispatch row) {
        OffsetDateTime now = now();
        jdbc.update("INSERT INTO payment_refund_dispatch(refund_order_id,refund_no,payment_id,"
                        + "payment_no,order_id,store_id,merchant_no,term_no,original_channel_trade_no,"
                        + "payment_success_event_id,original_paid_amount,refund_amount,currency,"
                        + "refund_binding_version,channel_request_no,request_sha256,state,"
                        + "may_have_sent_at,query_not_before,original_request_at,version,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                row.refundOrderId(), row.refundNo(), row.paymentId(), row.paymentNo(),
                row.orderId(), row.storeId(), row.merchantNo(), row.termNo(),
                row.originalChannelTradeNo(), row.paymentSuccessEventId(), row.originalPaidAmount(),
                row.refundAmount(), row.currency(), row.refundBindingVersion(),
                row.channelRequestNo(), row.requestSha256(), row.state(), row.mayHaveSentAt(),
                row.queryNotBefore(), row.originalRequestAt(), utc(now), utc(now));
    }

    public void recordReceipt(long id, long refundOrderId, String source, String sha,
            String originalTradeNo, String channelRequestNo, String channelRefundNo,
            String channelStatus, BigDecimal amount, LocalDateTime resultAt,
            OffsetDateTime receivedAt) {
        Long duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_receipt "
                        + "WHERE refund_order_id=? AND receipt_sha256=?", Long.class,
                refundOrderId, sha);
        if (duplicate != null && duplicate > 0) return;
        jdbc.update("INSERT INTO payment_refund_receipt(id,refund_order_id,receipt_source,"
                        + "receipt_sha256,original_channel_trade_no,channel_request_no,"
                        + "channel_refund_no,channel_status,refund_amount,result_at,received_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, refundOrderId, source, sha, originalTradeNo, channelRequestNo,
                channelRefundNo, channelStatus, amount, resultAt, utc(receivedAt));
    }

    public int markPending(Dispatch row, OffsetDateTime next, OffsetDateTime now) {
        return jdbc.update("UPDATE payment_refund_dispatch SET state='QUERY_PENDING',"
                        + "query_not_before=?,version=version+1,updated_at=? "
                        + "WHERE refund_order_id=? AND version=? AND state IN ('MAY_HAVE_SENT','QUERY_PENDING')",
                utc(next), utc(now), row.refundOrderId(), row.version());
    }

    public int markSuccess(Dispatch row, String channelRefundNo, String sha,
            OffsetDateTime resultAt, OffsetDateTime now) {
        return jdbc.update("UPDATE payment_refund_dispatch SET state='VERIFIED_SUCCESS',"
                        + "channel_refund_no=?,terminal_receipt_sha256=?,terminal_result_at=?,"
                        + "version=version+1,updated_at=? WHERE refund_order_id=? AND version=? "
                        + "AND state IN ('MAY_HAVE_SENT','QUERY_PENDING')",
                channelRefundNo, sha, utc(resultAt), utc(now), row.refundOrderId(), row.version());
    }

    public int markReconciliation(Dispatch row, OffsetDateTime now) {
        return jdbc.update("UPDATE payment_refund_dispatch SET state='RECONCILIATION_REQUIRED',"
                        + "version=version+1,updated_at=? WHERE refund_order_id=? AND version=? "
                        + "AND state IN ('MAY_HAVE_SENT','QUERY_PENDING')",
                utc(now), row.refundOrderId(), row.version());
    }

    public Receipt terminalReceipt(long refundOrderId, String sha) {
        List<Receipt> rows = jdbc.query("SELECT receipt_source,receipt_sha256,channel_refund_no,"
                        + "channel_status,refund_amount,result_at,original_channel_trade_no,"
                        + "channel_request_no FROM payment_refund_receipt "
                        + "WHERE refund_order_id=? AND receipt_sha256=?",
                (rs, n) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5),
                        rs.getObject(6, LocalDateTime.class), rs.getString(7), rs.getString(8)),
                refundOrderId, sha);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public static LocalDateTime utc(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public record Dispatch(long refundOrderId, long refundNo, long paymentId, long paymentNo,
            long orderId, long storeId, String merchantNo, String termNo,
            String originalChannelTradeNo, long paymentSuccessEventId,
            BigDecimal originalPaidAmount, BigDecimal refundAmount, String currency,
            long refundBindingVersion, String channelRequestNo, String requestSha256,
            String state, LocalDateTime mayHaveSentAt, LocalDateTime queryNotBefore,
            String channelRefundNo, String terminalReceiptSha256,
            LocalDateTime terminalResultAt, long version, LocalDateTime originalRequestAt) {}

    public record Receipt(String source, String sha, String channelRefundNo,
            String state, BigDecimal amount, LocalDateTime resultAt,
            String originalTradeNo, String channelRequestNo) {}
}
