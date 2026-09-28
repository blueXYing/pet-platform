package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderLateRefundMapper;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderMapperRows;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;

/** ORDER tables only; the REFUND business fact is obtained through its public API. */
public final class OrderLateRefundStore {
    private final OrderLateRefundMapper mapper;

    public OrderLateRefundStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderLateRefundMapper.class);
    }

    public OrderProjection lockOrder(long orderId) {
        OrderMapperRows.LateRefundOrder row = mapper.lockOrder(orderId);
        if (row == null) return null;
        return new OrderProjection(row.refundOrderId, row.refundedAmount, row.orderStage,
                row.paymentStatus, row.verificationStatus, row.cancelReason);
    }

    public RefundProjection lockResult(long orderId) {
        OrderMapperRows.LateRefundResult row = mapper.lockResult(orderId);
        if (row == null) return null;
        return new RefundProjection(row.refundOrderId, row.paymentId, row.refundType,
                row.refundSource, row.refundAmount, row.refundStatus, row.createdEventId,
                row.successEventId, offset(row.succeededAt));
    }

    public int bindOrder(long orderId, long refundOrderId, BigDecimal refundedAmount) {
        if (refundedAmount == null) return mapper.bindUnboundOrder(orderId, refundOrderId);
        return mapper.bindRefundedOrder(orderId, refundOrderId, refundedAmount);
    }

    public void insertCreated(long orderId, long refundOrderId, long paymentId,
            BigDecimal amount, long eventId) {
        if (mapper.insertCreated(orderId, refundOrderId, paymentId, amount, eventId) != 1)
            throw new IllegalStateException("ORDER refund creation projection absent");
    }

    public void noteCreatedAfterSuccess(long orderId, long eventId) {
        if (mapper.noteCreatedAfterSuccess(orderId, eventId) != 1)
            throw new IllegalStateException("ORDER refund creation replay conflict");
    }

    public void insertSucceeded(long orderId, long refundOrderId, long paymentId,
            BigDecimal amount, long eventId, OffsetDateTime succeededAt) {
        if (mapper.insertSucceeded(orderId, refundOrderId, paymentId, amount, eventId,
                utc(succeededAt)) != 1)
            throw new IllegalStateException("ORDER refund success projection absent");
    }

    public void markSucceeded(long orderId, long eventId, OffsetDateTime succeededAt) {
        if (mapper.markSucceeded(orderId, eventId, utc(succeededAt)) != 1)
            throw new IllegalStateException("ORDER refund success projection conflict");
    }

    public void statusLog(long id, long orderId, String from, String to,
            String eventType, String requestId) {
        if (mapper.statusLog(id, orderId, from, to, eventType, requestId) != 1)
            throw new IllegalStateException("ORDER refund status log absent");
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
    public record OrderProjection(Long refundOrderId, BigDecimal refundedAmount,
            String stage, String paymentStatus, String verificationStatus, String cancelReason) {}
    public record RefundProjection(long refundOrderId, long paymentId, String refundType,
            String refundSource, BigDecimal refundAmount, String refundStatus,
            Long createdEventId, Long successEventId, OffsetDateTime succeededAt) {}
}
