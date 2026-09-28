package com.petplatform.order.biz.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** ORDER tables only; the REFUND business fact is obtained through its public API. */
public final class OrderLateRefundStore {
    private final JdbcTemplate jdbc;

    public OrderLateRefundStore(DataSource source) { jdbc = new JdbcTemplate(Objects.requireNonNull(source)); }

    public OrderProjection lockOrder(long orderId) {
        return jdbc.query("""
                SELECT refund_order_id,refunded_amount,order_stage,payment_status,
                       verification_status,cancel_reason
                FROM pet_order WHERE id=? FOR UPDATE
                """, rs -> {
            if (!rs.next()) return null;
            Number refundId = (Number) rs.getObject("refund_order_id");
            return new OrderProjection(refundId == null ? null : refundId.longValue(),
                    rs.getBigDecimal("refunded_amount"), rs.getString("order_stage"),
                    rs.getString("payment_status"), rs.getString("verification_status"),
                    rs.getString("cancel_reason"));
        }, orderId);
    }

    public RefundProjection lockResult(long orderId) {
        return jdbc.query("""
                SELECT refund_order_id,payment_id,refund_type,refund_source,refund_amount,
                       refund_status,created_event_id,success_event_id,succeeded_at
                FROM order_late_refund_result WHERE order_id=? FOR UPDATE
                """, rs -> {
            if (!rs.next()) return null;
            Number createdId = (Number) rs.getObject("created_event_id");
            Number successId = (Number) rs.getObject("success_event_id");
            LocalDateTime succeeded = rs.getObject("succeeded_at", LocalDateTime.class);
            return new RefundProjection(rs.getLong("refund_order_id"), rs.getLong("payment_id"),
                    rs.getString("refund_type"), rs.getString("refund_source"),
                    rs.getBigDecimal("refund_amount"), rs.getString("refund_status"),
                    createdId == null ? null : createdId.longValue(),
                    successId == null ? null : successId.longValue(),
                    succeeded == null ? null : succeeded.atOffset(ZoneOffset.UTC));
        }, orderId);
    }

    public int bindOrder(long orderId, long refundOrderId, BigDecimal refundedAmount) {
        if (refundedAmount == null) {
            return jdbc.update("""
                    UPDATE pet_order SET refund_order_id=?,version=version+1,
                                         updated_at=UTC_TIMESTAMP(3)
                    WHERE id=? AND refund_order_id IS NULL AND refunded_amount=0
                      AND order_stage='CANCELED' AND cancel_reason='PAYMENT_TIMEOUT'
                      AND payment_status='PAID' AND verification_status='UNVERIFIED'
                    """, refundOrderId, orderId);
        }
        return jdbc.update("""
                UPDATE pet_order SET refund_order_id=?,refunded_amount=?,version=version+1,
                                     updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND (refund_order_id IS NULL OR refund_order_id=?)
                  AND refunded_amount=0 AND order_stage='CANCELED'
                  AND cancel_reason='PAYMENT_TIMEOUT' AND payment_status='PAID'
                  AND verification_status='UNVERIFIED'
                """, refundOrderId, refundedAmount, orderId, refundOrderId);
    }

    public void insertCreated(long orderId, long refundOrderId, long paymentId,
            BigDecimal amount, long eventId) {
        if (jdbc.update("""
                INSERT INTO order_late_refund_result
                  (order_id,refund_order_id,payment_id,refund_type,refund_source,refund_amount,
                   refund_status,created_event_id,created_at,updated_at)
                VALUES (?,?,?,'FULL','LATE_PAYMENT_TIMEOUT',?,'CREATED',?,
                        UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, orderId, refundOrderId, paymentId, amount, eventId) != 1)
            throw new IllegalStateException("ORDER refund creation projection absent");
    }

    public void noteCreatedAfterSuccess(long orderId, long eventId) {
        if (jdbc.update("""
                UPDATE order_late_refund_result SET created_event_id=?,updated_at=UTC_TIMESTAMP(3)
                WHERE order_id=? AND refund_status='SUCCESS' AND created_event_id IS NULL
                """, eventId, orderId) != 1)
            throw new IllegalStateException("ORDER refund creation replay conflict");
    }

    public void insertSucceeded(long orderId, long refundOrderId, long paymentId,
            BigDecimal amount, long eventId, OffsetDateTime succeededAt) {
        if (jdbc.update("""
                INSERT INTO order_late_refund_result
                  (order_id,refund_order_id,payment_id,refund_type,refund_source,refund_amount,
                   refund_status,success_event_id,succeeded_at,created_at,updated_at)
                VALUES (?,?,?,'FULL','LATE_PAYMENT_TIMEOUT',?,'SUCCESS',?,?,
                        UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """, orderId, refundOrderId, paymentId, amount, eventId, utc(succeededAt)) != 1)
            throw new IllegalStateException("ORDER refund success projection absent");
    }

    public void markSucceeded(long orderId, long eventId, OffsetDateTime succeededAt) {
        if (jdbc.update("""
                UPDATE order_late_refund_result
                SET refund_status='SUCCESS',success_event_id=?,succeeded_at=?,
                    updated_at=UTC_TIMESTAMP(3)
                WHERE order_id=? AND refund_status='CREATED' AND success_event_id IS NULL
                """, eventId, utc(succeededAt), orderId) != 1)
            throw new IllegalStateException("ORDER refund success projection conflict");
    }

    public void statusLog(long id, long orderId, String from, String to,
            String eventType, String requestId) {
        if (jdbc.update("""
                INSERT INTO order_status_log
                  (id,order_id,dimension,from_status,to_status,event_type,operator_type,
                   operator_id,request_id,remark,created_at)
                VALUES (?,?,'REFUND',?,?,?,'SYSTEM',NULL,?,NULL,UTC_TIMESTAMP(3))
                """, id, orderId, from, to, eventType, requestId) != 1)
            throw new IllegalStateException("ORDER refund status log absent");
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
    public record OrderProjection(Long refundOrderId, BigDecimal refundedAmount,
            String stage, String paymentStatus, String verificationStatus, String cancelReason) {}
    public record RefundProjection(long refundOrderId, long paymentId, String refundType,
            String refundSource, BigDecimal refundAmount, String refundStatus,
            Long createdEventId, Long successEventId, OffsetDateTime succeededAt) {}
}
