package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentDemoMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import javax.sql.DataSource;

/** Test-only DEMO business owner table (SQL75-Demo). Never part of a production migration. */
public final class PaymentDemoStore {
    public final PaymentDemoMapper mapper;

    public PaymentDemoStore(DataSource source) {
        mapper = PaymentMybatis.template(source).getMapper(PaymentDemoMapper.class);
    }

    /** Column order must match {@code demoColumns} in PaymentDemoMapper.xml. */
    public record DemoRow(long id, long demoNo, long userId, BigDecimal amount, String status, Long paymentId,
            BigDecimal paidAmount, LocalDateTime paidAt, Long sourceEventId, LocalDateTime createdAt) {}
}
