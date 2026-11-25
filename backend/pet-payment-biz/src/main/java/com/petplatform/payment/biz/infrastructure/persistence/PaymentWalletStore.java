package com.petplatform.payment.biz.infrastructure.persistence;

import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentFoundationMapper;
import com.petplatform.payment.biz.infrastructure.persistence.mapper.PaymentWalletMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/** PAYMENT-owned Stage 5 merchant wallet & withdrawal persistence. */
public final class PaymentWalletStore {
    public final PaymentWalletMapper wallet;
    public final PaymentFoundationMapper shared;

    public PaymentWalletStore(DataSource source) {
        SqlSessionTemplate template = PaymentMybatis.template(source);
        this.wallet = template.getMapper(PaymentWalletMapper.class);
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
