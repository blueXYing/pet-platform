package com.petplatform.order.biz.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** ORDER-owned current reads and writes. The caller holds the store capacity guard. */
public final class OrderExpiryStore {
    private final JdbcTemplate jdbc;

    public OrderExpiryStore(DataSource source) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(source));
    }

    public void sessionDefaults() {
        jdbc.execute("SET SESSION time_zone = '+00:00'");
        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 2");
    }

    public String storeId(long orderId) {
        return jdbc.query("SELECT store_id FROM pet_order WHERE id=?", rs ->
                rs.next() ? Long.toString(rs.getLong(1)) : null, orderId);
    }

    public List<Long> scanPendingAfter(long afterOrderId, int limit) {
        return jdbc.query("""
                SELECT id FROM pet_order
                WHERE id>? AND order_stage='PENDING_PAYMENT' AND payment_status='INIT'
                  AND verification_status='UNVERIFIED' AND payment_expire_at IS NOT NULL
                ORDER BY id LIMIT ?
                """, (rs, row) -> rs.getLong(1), afterOrderId, limit);
    }

    public OffsetDateTime databaseNow() {
        // A DATETIME returned as java.sql.Timestamp is decoded through the JVM timezone.
        // Epoch seconds avoid changing the observed instant on non-UTC application hosts.
        BigDecimal seconds = jdbc.queryForObject(
                "SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP(3))", BigDecimal.class);
        if (seconds == null) throw new IllegalStateException("Database time is unavailable");
        return Instant.ofEpochMilli(seconds.movePointRight(3).longValueExact())
                .atOffset(ZoneOffset.UTC);
    }

    public OrderRow lock(long orderId) {
        return jdbc.query("""
                SELECT id, reservation_id, store_id, order_stage, payment_status,
                       pay_amount, discount_amount,
                       verification_status, payment_expire_at, version
                FROM pet_order WHERE id=? FOR UPDATE
                """, rs -> {
            if (!rs.next()) return null;
            LocalDateTime deadline = rs.getObject("payment_expire_at", LocalDateTime.class);
            return new OrderRow(rs.getLong("id"), rs.getLong("reservation_id"),
                    rs.getLong("store_id"), rs.getString("order_stage"),
                    rs.getString("payment_status"), rs.getString("verification_status"),
                    rs.getBigDecimal("pay_amount"), rs.getBigDecimal("discount_amount"),
                    deadline == null ? null : deadline.atOffset(ZoneOffset.UTC),
                    rs.getLong("version"));
        }, orderId);
    }

    public boolean hasExpiryLog(long orderId, String requestId) {
        return !jdbc.query("""
                SELECT id FROM order_status_log
                WHERE order_id=? AND dimension='ORDER_STAGE' AND from_status='PENDING_PAYMENT'
                  AND to_status='CANCELED' AND event_type='PAYMENT_TIMEOUT'
                  AND operator_type='SYSTEM' AND request_id=? LIMIT 1
                """, (rs, row) -> rs.getLong(1), orderId, requestId).isEmpty();
    }

    public int cancel(long orderId, long version, OffsetDateTime deadline, OffsetDateTime observedNow) {
        return jdbc.update("""
                UPDATE pet_order SET order_stage='CANCELED', canceled_at=?, version=version+1,
                                     updated_at=UTC_TIMESTAMP(3)
                WHERE id=? AND version=? AND order_stage='PENDING_PAYMENT'
                  AND payment_status='INIT' AND verification_status='UNVERIFIED'
                  AND payment_expire_at=? AND payment_expire_at<=?
                """, utc(observedNow), orderId, version,
                utc(deadline), utc(observedNow));
    }

    public void statusLog(long id, long orderId, String requestId) {
        if (jdbc.update("""
                INSERT INTO order_status_log
                  (id,order_id,dimension,from_status,to_status,event_type,operator_type,
                   operator_id,request_id,remark,created_at)
                VALUES (?,?,'ORDER_STAGE','PENDING_PAYMENT','CANCELED','PAYMENT_TIMEOUT',
                        'SYSTEM',NULL,?,'PAYMENT_TIMEOUT',UTC_TIMESTAMP(3))
                """, id, orderId, requestId) != 1) {
            throw new IllegalStateException("ORDER expiry audit was not persisted");
        }
    }

    public record OrderRow(long id, long reservationId, long storeId, String stage,
            String paymentStatus, String verificationStatus, BigDecimal payAmount,
            BigDecimal discountAmount, OffsetDateTime paymentExpireAt,
            long version) {}

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
}
