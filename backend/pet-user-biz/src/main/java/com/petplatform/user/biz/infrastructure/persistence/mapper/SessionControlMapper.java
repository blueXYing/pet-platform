package com.petplatform.user.biz.infrastructure.persistence.mapper;

/**
 * Session-level statements executed on the caller's transaction connection before the binding
 * insert and the execution body; SQL lives in resources/mapper/SessionControlMapper.xml.
 */
public interface SessionControlMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();
}
