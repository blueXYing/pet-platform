package com.petplatform.coupon.biz.infrastructure.persistence;

import com.petplatform.coupon.biz.infrastructure.persistence.mapper.CouponLifecycleMapper;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Lifecycle mapper access. Freeze/consume/release join the caller's guarded ORDER transaction
 * (no transaction of their own — the mapper template binds to the caller's DataSource-backed
 * connection); the refund-restore consumer drives its own TransactionTemplate on top.
 */
public final class CouponLifecycleStore {
    private final SqlSessionTemplate sql;

    private CouponLifecycleStore(SqlSessionTemplate sql) {
        this.sql = Objects.requireNonNull(sql, "sql is required");
    }

    public static CouponLifecycleStore joining(DataSource source) {
        return new CouponLifecycleStore(CouponMybatis.template(source));
    }

    public void sessionDefaults() {
        CouponLifecycleMapper mapper = sql.getMapper(CouponLifecycleMapper.class);
        mapper.setUtcTimeZone();
        mapper.setLockWaitTimeout();
    }

    public CouponLifecycleMapper mapper() {
        return sql.getMapper(CouponLifecycleMapper.class);
    }

    public <T> T work(Function<CouponLifecycleMapper, T> action) {
        return action.apply(sql.getMapper(CouponLifecycleMapper.class));
    }
}
