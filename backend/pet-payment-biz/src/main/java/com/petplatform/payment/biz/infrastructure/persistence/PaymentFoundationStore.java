package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentFoundationMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/** PAYMENT-owned persistence, sharing the caller's transaction and connection. */
public final class PaymentFoundationStore {
    public final PaymentFoundationMapper mapper;

    public PaymentFoundationStore(DataSource source) {
        mapper = PaymentMybatis.template(source).getMapper(PaymentFoundationMapper.class);
    }

    public void session() { mapper.setUtcTimeZone(); mapper.setLockWaitTimeout(); }
    public OffsetDateTime now() { return mapper.utcNow().atOffset(ZoneOffset.UTC); }
    public Row byNo(long no) { return mapper.selectByNo(no); }
    public Row byId(long id, boolean lock) {
        return lock ? mapper.selectByIdForUpdate(id) : mapper.selectById(id);
    }
    public Row byOrder(long id) { return mapper.selectByOrderForUpdate(id); }

    /** Named bind values, including nullable channel fields, without SQL string composition. */
    public static Map<String, Object> values(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("uneven bind values");
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]);
        return values;
    }

    public record Row(long id, long no, long orderId, long storeId, long merchantId, long userId,
            BigDecimal amount, String status, LocalDateTime expires, String merchantNo,
            String termNo, String subAppId, String currency, String dispatchState,
            String tradeNo, BigDecimal paidAmount, LocalDateTime paidAt, Long successEventId,
            String channel) {}

    public record IntentBinding(long orderId, long userId) {}

    public record Dispatch(long paymentId, String state, LocalDateTime preorderRequestTime,
            LocalDate tradeRequestDate, Integer timeoutMinutes, String identityHmac,
            LocalDateTime mayHaveSentAt, String preorderResponseSha256, byte[] parametersCiphertext,
            byte[] parametersIv, LocalDateTime parameterValidUntil, LocalDateTime fencedAt,
            LocalDateTime closeMayHaveSentAt, String closeResponseSha256,
            String terminalQuerySha256, LocalDateTime terminalConfirmedAt,
            boolean terminalCapability) {}

    public record ExposureDispatch(String state, LocalDateTime preorderReqTime,
            LocalDate tradeReqDate, Integer timeoutMinutes, LocalDateTime mayHaveSentAt,
            String preorderResponseSha, byte[] parametersCiphertext, byte[] parametersIv,
            LocalDateTime parameterValidUntil, LocalDateTime fencedAt,
            LocalDateTime closeMayHaveSentAt, String closeResponseSha, String terminalQuerySha,
            LocalDateTime terminalConfirmedAt, int closeCapability) {}

    public record ExposureReceipt(String source, String status, String digest, String responseSha,
            BigDecimal paidAmount, BigDecimal totalAmount, String tradeNo) {}

    public static LocalDateTime utc(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
