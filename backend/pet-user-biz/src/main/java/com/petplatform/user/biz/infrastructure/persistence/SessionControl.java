package com.petplatform.user.biz.infrastructure.persistence;

import com.petplatform.user.biz.infrastructure.persistence.mapper.SessionControlMapper;
import javax.sql.DataSource;

/**
 * Session-level statements for the admission/execution transactions, applied on the caller's
 * current transaction connection (SQL kept verbatim, PLAT-006).
 */
public final class SessionControl {

    private final org.mybatis.spring.SqlSessionTemplate template;

    public SessionControl(DataSource dataSource) {
        this.template = UserMybatis.template(dataSource);
    }

    /** UTC timestamps plus the 2-second lock-wait budget the execution transactions rely on. */
    public void applyExecutionDefaults() {
        SessionControlMapper session = template.getMapper(SessionControlMapper.class);
        session.setTimeZoneUtc();
        session.setLockWaitTimeout2Seconds();
    }
}
