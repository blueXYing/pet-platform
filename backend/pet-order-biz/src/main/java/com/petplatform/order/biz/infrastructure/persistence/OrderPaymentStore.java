package com.petplatform.order.biz.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** ORDER SQL only; no PAYMENT or SCH table access. */
public final class OrderPaymentStore {
    private final JdbcTemplate jdbc;

    public OrderPaymentStore(DataSource source) { jdbc = new JdbcTemplate(Objects.requireNonNull(source)); }

    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class).atOffset(ZoneOffset.UTC);
    }

    public void sessionDefaults() {
        jdbc.execute("SET SESSION time_zone = '+00:00'");
        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
    }

    public List<Locator> locate(long orderId) {
        return jdbc.query("SELECT store_id,user_id FROM pet_order WHERE id=?",
                (rs, row) -> new Locator(rs.getLong(1), rs.getLong(2)), orderId);
    }

    public OrderRow lock(long orderId) {
        return jdbc.query("""
                SELECT id,user_id,merchant_id,store_id,reservation_id,order_stage,payment_status,
                       verification_status,pay_amount,discount_amount,payment_expire_at,
                       cancel_reason,version
                FROM pet_order WHERE id=? FOR UPDATE
                """, rs -> {
            if (!rs.next()) return null;
            LocalDateTime deadline = rs.getObject("payment_expire_at", LocalDateTime.class);
            return new OrderRow(rs.getLong("id"), rs.getLong("user_id"),
                    rs.getLong("merchant_id"), rs.getLong("store_id"),
                    rs.getLong("reservation_id"), rs.getString("order_stage"),
                    rs.getString("payment_status"), rs.getString("verification_status"),
                    rs.getBigDecimal("pay_amount"), rs.getBigDecimal("discount_amount"),
                    deadline == null ? null : deadline.atOffset(ZoneOffset.UTC),
                    rs.getString("cancel_reason"),
                    rs.getLong("version"));
        }, orderId);
    }

    public ResultRow lockResult(long orderId) {
        return jdbc.query("""
                SELECT payment_id,source_event_id,channel_trade_no,channel_paid_amount,
                       channel_paid_at,result_type
                FROM order_payment_result WHERE order_id=? FOR UPDATE
                """, rs -> {
            if (!rs.next()) return null;
            LocalDateTime paidAt = rs.getObject("channel_paid_at", LocalDateTime.class);
            return new ResultRow(rs.getLong("payment_id"), rs.getLong("source_event_id"),
                    rs.getString("channel_trade_no"), rs.getBigDecimal("channel_paid_amount"),
                    paidAt == null ? null : paidAt.atOffset(ZoneOffset.UTC),
                    rs.getString("result_type"));
        }, orderId);
    }

    public int markPaid(OrderRow row, OffsetDateTime paidAt, OffsetDateTime confirmDeadline) {
        return jdbc.update("""
                UPDATE pet_order SET order_stage='PENDING_CONFIRM',payment_status='PAID',
                  paid_at=?,confirm_deadline=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND version=? AND reservation_id=? AND order_stage='PENDING_PAYMENT'
                  AND payment_status='INIT' AND verification_status='UNVERIFIED'
                  AND cancel_reason IS NULL AND canceled_at IS NULL
                """, utc(paidAt), utc(confirmDeadline), row.id(), row.version(), row.reservationId());
    }

    public int recordLatePayment(OrderRow row, OffsetDateTime paidAt) {
        return jdbc.update("""
                UPDATE pet_order SET payment_status='PAID',paid_at=?,version=version+1,
                                     updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND version=? AND reservation_id=? AND order_stage='CANCELED'
                  AND payment_status='INIT' AND verification_status='UNVERIFIED'
                  AND cancel_reason='PAYMENT_TIMEOUT'
                """, utc(paidAt), row.id(), row.version(), row.reservationId());
    }

    public void insertResult(long id, long orderId, long paymentId, long sourceEventId,
            String channelTradeNo, BigDecimal paidAmount, OffsetDateTime paidAt,
            String resultType) {
        if (jdbc.update("""
                INSERT INTO order_payment_result
                  (id,order_id,payment_id,source_event_id,channel_trade_no,
                   channel_paid_amount,channel_paid_at,result_type,created_at)
                VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """, id, orderId, paymentId, sourceEventId, channelTradeNo,
                paidAmount, utc(paidAt), resultType) != 1)
            throw new IllegalStateException("ORDER payment result was not persisted");
    }

    public void statusLog(long id, long orderId, String dimension, String fromStatus,
            String toStatus, String eventType, String requestId) {
        if (jdbc.update("""
                INSERT INTO order_status_log
                  (id,order_id,dimension,from_status,to_status,event_type,operator_type,
                   operator_id,request_id,remark,created_at)
                VALUES (?,?,?,?,?,?,'SYSTEM',NULL,?,NULL,UTC_TIMESTAMP(3))
                """, id, orderId, dimension, fromStatus, toStatus, eventType, requestId) != 1)
            throw new IllegalStateException("ORDER payment status audit was not persisted");
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    public record Locator(long storeId, long userId) {}
    public record OrderRow(long id,long userId,long merchantId,long storeId,long reservationId,
            String stage,String paymentStatus,String verificationStatus,BigDecimal payAmount,
            BigDecimal discountAmount,OffsetDateTime paymentExpireAt,String cancelReason,long version) {}
    public record ResultRow(long paymentId,long sourceEventId,String channelTradeNo,
            BigDecimal paidAmount,OffsetDateTime paidAt,String resultType) {}
}
