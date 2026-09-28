package com.petplatform.order.biz.infrastructure.persistence;

import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderExpiryMapper;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderMapperRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;

/** ORDER-owned current reads and writes. The caller holds the store capacity guard. */
public final class OrderExpiryStore {
    private final OrderExpiryMapper mapper;

    public OrderExpiryStore(DataSource source) {
        mapper = OrderMybatis.template(source).getMapper(OrderExpiryMapper.class);
    }

    public void sessionDefaults() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }

    public String storeId(long orderId) {
        Long value = mapper.storeId(orderId);
        return value == null ? null : value.toString();
    }

    public List<Long> scanPendingAfter(long afterOrderId, int limit) {
        return mapper.scanPendingAfter(afterOrderId, limit);
    }

    public OffsetDateTime databaseNow() {
        // Epoch seconds avoid JVM timezone decoding of a DATETIME on non-UTC hosts.
        BigDecimal seconds = mapper.databaseEpochSeconds();
        if (seconds == null) throw new IllegalStateException("Database time is unavailable");
        return Instant.ofEpochMilli(seconds.movePointRight(3).longValueExact()).atOffset(ZoneOffset.UTC);
    }

    public OrderRow lock(long orderId) {
        OrderMapperRows.ExpiryOrder row = mapper.lock(orderId);
        if (row == null) return null;
        return new OrderRow(row.id, row.reservationId, row.storeId, row.orderStage,
                row.paymentStatus, row.verificationStatus, row.payAmount, row.discountAmount,
                offset(row.paymentExpireAt), row.cancelReason, row.version);
    }

    public boolean hasExpiryLog(long orderId, String requestId) {
        return mapper.findExpiryLog(orderId, requestId) != null;
    }

    public boolean hasLatePaymentResult(long orderId) {
        return mapper.findLatePaymentResult(orderId) != null;
    }

    public int cancel(long orderId, long version, OffsetDateTime deadline, OffsetDateTime observedNow) {
        return mapper.cancel(orderId, version, utc(deadline), utc(observedNow));
    }

    public void statusLog(long id, long orderId, String requestId) {
        if (mapper.statusLog(id, orderId, requestId) != 1) {
            throw new IllegalStateException("ORDER expiry audit was not persisted");
        }
    }

    public record OrderRow(long id, long reservationId, long storeId, String stage,
            String paymentStatus, String verificationStatus, BigDecimal payAmount,
            BigDecimal discountAmount, OffsetDateTime paymentExpireAt, String cancelReason,
            long version) {}

    private static LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
