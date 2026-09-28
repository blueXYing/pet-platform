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
                       verification_status,pay_amount,discount_amount,payment_expire_at,version
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
                    rs.getLong("version"));
        }, orderId);
    }

    public record Locator(long storeId, long userId) {}
    public record OrderRow(long id,long userId,long merchantId,long storeId,long reservationId,
            String stage,String paymentStatus,String verificationStatus,BigDecimal payAmount,
            BigDecimal discountAmount,OffsetDateTime paymentExpireAt,long version) {}
}
