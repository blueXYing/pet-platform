package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderMapperRows;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderPaymentMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;

/** ORDER SQL only; no PAYMENT or SCH table access. */
public final class OrderPaymentStore {
    private final OrderPaymentMapper mapper;

    public OrderPaymentStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderPaymentMapper.class);
    }

    public OffsetDateTime databaseNow() {
        return mapper.databaseNow().atOffset(ZoneOffset.UTC);
    }

    public void sessionDefaults() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }

    public List<Locator> locate(long orderId) {
        return mapper.locate(orderId).stream()
                .map(row -> new Locator(row.storeId, row.userId)).toList();
    }

    public OrderRow lock(long orderId) {
        OrderMapperRows.PaymentOrder row = mapper.lock(orderId);
        if (row == null) return null;
        return new OrderRow(row.id, row.userId, row.merchantId, row.storeId,
                row.reservationId, row.orderStage, row.paymentStatus, row.verificationStatus,
                row.payAmount, row.discountAmount, offset(row.paymentExpireAt), row.cancelReason,
                row.version, offset(row.paidAt));
    }

    public ResultRow lockResult(long orderId) {
        OrderMapperRows.PaymentResult row = mapper.lockResult(orderId);
        if (row == null) return null;
        return new ResultRow(row.paymentId, row.sourceEventId, row.channelTradeNo,
                row.channelPaidAmount, offset(row.channelPaidAt), row.resultType);
    }

    public int markPaid(OrderRow row, OffsetDateTime paidAt, OffsetDateTime confirmDeadline) {
        return mapper.markPaid(row.id(), row.version(), row.reservationId(),
                utc(paidAt), utc(confirmDeadline));
    }

    public int recordLatePayment(OrderRow row, OffsetDateTime paidAt) {
        return mapper.recordLatePayment(row.id(), row.version(), row.reservationId(), utc(paidAt));
    }

    public void insertResult(long id, long orderId, long paymentId, long sourceEventId,
            String channelTradeNo, BigDecimal paidAmount, OffsetDateTime paidAt,
            String resultType) {
        if (mapper.insertResult(id, orderId, paymentId, sourceEventId, channelTradeNo,
                paidAmount, utc(paidAt), resultType) != 1)
            throw new IllegalStateException("ORDER payment result was not persisted");
    }

    public void statusLog(long id, long orderId, String dimension, String fromStatus,
            String toStatus, String eventType, String requestId) {
        if (mapper.statusLog(id, orderId, dimension, fromStatus, toStatus, eventType, requestId) != 1)
            throw new IllegalStateException("ORDER payment status audit was not persisted");
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    public record Locator(long storeId, long userId) {}
    public record OrderRow(long id,long userId,long merchantId,long storeId,long reservationId,
            String stage,String paymentStatus,String verificationStatus,BigDecimal payAmount,
            BigDecimal discountAmount,OffsetDateTime paymentExpireAt,String cancelReason,long version,
            OffsetDateTime paidAt) {}
    public record ResultRow(long paymentId,long sourceEventId,String channelTradeNo,
            BigDecimal paidAmount,OffsetDateTime paidAt,String resultType) {}
}
