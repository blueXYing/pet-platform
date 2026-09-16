package com.petplatform.user.biz.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Update;

/**
 * Session-level statements executed on the caller's transaction connection before the binding
 * insert and the execution body (SQL kept verbatim, PLAT-006).
 */
public interface SessionControlMapper {

    @Update("SET SESSION time_zone = '+00:00'")
    void setTimeZoneUtc();

    @Update("SET SESSION innodb_lock_wait_timeout = 2")
    void setLockWaitTimeout2Seconds();
}
