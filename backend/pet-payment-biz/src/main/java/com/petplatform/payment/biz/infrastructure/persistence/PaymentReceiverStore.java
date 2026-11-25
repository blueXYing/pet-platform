package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentFoundationMapper;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentReceiverMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** PAYMENT-owned split-receiver persistence sharing the caller's DataSourceTransactionManager transaction. */
public final class PaymentReceiverStore {
    public final PaymentReceiverMapper receiver;
    public final PaymentFoundationMapper shared;

    public PaymentReceiverStore(DataSource source) {
        SqlSessionTemplate template = PaymentMybatis.template(source);
        this.receiver = template.getMapper(PaymentReceiverMapper.class);
        this.shared = template.getMapper(PaymentFoundationMapper.class);
    }

    public void session() {
        shared.setUtcTimeZone();
        shared.setLockWaitTimeout();
    }

    public OffsetDateTime now() {
        return shared.utcNow().atOffset(ZoneOffset.UTC);
    }

    public static LocalDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
