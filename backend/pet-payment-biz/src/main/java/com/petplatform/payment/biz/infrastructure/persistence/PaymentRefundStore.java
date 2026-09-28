package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentRefundMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;

/** PAYMENT-owned refund dispatch and minimum signed receipt facts (SQL43). */
public final class PaymentRefundStore {
    private final PaymentRefundMapper mapper;

    public PaymentRefundStore(DataSource source) {
        mapper = PaymentMybatis.template(source).getMapper(PaymentRefundMapper.class);
    }

    public void session() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }

    public OffsetDateTime now() { return mapper.utcNow().atOffset(ZoneOffset.UTC); }

    public Dispatch byRefund(long refundOrderId, boolean lock) {
        return lock ? mapper.selectByRefundForUpdate(refundOrderId)
                : mapper.selectByRefund(refundOrderId);
    }

    public void insert(Dispatch row) {
        LocalDateTime now = utc(now());
        mapper.insertDispatch(PaymentFoundationStore.values(
                "refundOrderId", row.refundOrderId(), "refundNo", row.refundNo(),
                "paymentId", row.paymentId(), "paymentNo", row.paymentNo(),
                "orderId", row.orderId(), "storeId", row.storeId(),
                "merchantNo", row.merchantNo(), "termNo", row.termNo(),
                "originalTradeNo", row.originalChannelTradeNo(),
                "successEventId", row.paymentSuccessEventId(),
                "originalPaidAmount", row.originalPaidAmount(),
                "refundAmount", row.refundAmount(), "currency", row.currency(),
                "bindingVersion", row.refundBindingVersion(),
                "channelRequestNo", row.channelRequestNo(), "requestSha", row.requestSha256(),
                "state", row.state(), "mayHaveSentAt", row.mayHaveSentAt(),
                "queryNotBefore", row.queryNotBefore(), "originalRequestAt", row.originalRequestAt(),
                "now", now));
    }

    public void recordReceipt(long id, long refundOrderId, String source, String sha,
            String originalTradeNo, String channelRequestNo, String channelRefundNo,
            String channelStatus, BigDecimal amount, LocalDateTime resultAt,
            OffsetDateTime receivedAt) {
        Long duplicate = mapper.countReceipt(refundOrderId, sha);
        if (duplicate != null && duplicate > 0) return;
        mapper.insertReceipt(PaymentFoundationStore.values(
                "id", id, "refundOrderId", refundOrderId, "source", source, "sha", sha,
                "originalTradeNo", originalTradeNo, "channelRequestNo", channelRequestNo,
                "channelRefundNo", channelRefundNo, "status", channelStatus,
                "amount", amount, "resultAt", resultAt, "receivedAt", utc(receivedAt)));
    }

    public int markPending(Dispatch row, OffsetDateTime next, OffsetDateTime now) {
        return mapper.markPending(PaymentFoundationStore.values("next", utc(next), "now", utc(now),
                "refundOrderId", row.refundOrderId(), "version", row.version()));
    }

    public int markSuccess(Dispatch row, String channelRefundNo, String sha,
            OffsetDateTime resultAt, OffsetDateTime now) {
        return mapper.markSuccess(PaymentFoundationStore.values("channelRefundNo", channelRefundNo,
                "sha", sha, "resultAt", utc(resultAt), "now", utc(now),
                "refundOrderId", row.refundOrderId(), "version", row.version()));
    }

    public int markReconciliation(Dispatch row, OffsetDateTime now) {
        return mapper.markReconciliation(PaymentFoundationStore.values("now", utc(now),
                "refundOrderId", row.refundOrderId(), "version", row.version()));
    }

    public Receipt terminalReceipt(long refundOrderId, String sha) {
        return mapper.selectTerminalReceipt(refundOrderId, sha);
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
