package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentCenterMapper;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentFoundationMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Contract 75 payment center persistence (PAYMENT-owned). Center rows have NULL order/store/merchant and a
 * non-null biz pair, so they are read through {@link CenterRow} with nullable owner columns. ORDER readers
 * ({@link PaymentFoundationStore.Row}) cannot map such a row and fail closed. Shared state transitions
 * (dispatch fence, receipts, PAID) reuse the existing PaymentFoundationMapper statements by payment id.
 */
public final class PaymentCenterStore {
    public final PaymentCenterMapper center;
    public final PaymentFoundationMapper shared;

    public PaymentCenterStore(DataSource source) {
        SqlSessionTemplate template = PaymentMybatis.template(source);
        center = template.getMapper(PaymentCenterMapper.class);
        shared = template.getMapper(PaymentFoundationMapper.class);
    }

    public void session() { shared.setUtcTimeZone(); shared.setLockWaitTimeout(); }
    public OffsetDateTime now() { return shared.utcNow().atOffset(ZoneOffset.UTC); }

    /** Column order must match {@code centerColumns} in PaymentCenterMapper.xml (constructor mapping). */
    public record CenterRow(long id, long no, Long orderId, Long storeId, Long merchantId, Long userId,
            BigDecimal amount, String status, LocalDateTime expires, String merchantNo, String termNo,
            String subAppId, String currency, String dispatchState, String tradeNo, BigDecimal paidAmount,
            LocalDateTime paidAt, Long successEventId, String channel, String bizType, String bizNo,
            String settleType, String logNo, LocalDate logDate) {
        /** A payment center row never carries ORDER ownership. */
        public boolean isCenter() {
            return orderId == null && storeId == null && merchantId == null && bizType != null && bizNo != null;
        }
    }

    public record Route(long id, String bizType, Long orderId) {}

    public record RequestBinding(String bizType, String bizNo, long userId, BigDecimal amount,
            LocalDateTime expireAt, Long paymentId) {}

    public static LocalDateTime utc(OffsetDateTime time) { return PaymentFoundationStore.utc(time); }
}
