package com.petplatform.user.biz.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * Contract 66 account-deletion statements; SQL lives in resources/mapper/UserDeletionMapper.xml.
 * The audit row is append-only (never updated or deleted — it is the deletion audit fact); the
 * account flip is a guarded single-row CAS on the expected current status.
 */
public interface UserDeletionMapper {

    int insertDeletionRequest(@Param("id") long id, @Param("userId") long userId,
            @Param("requestId") String requestId, @Param("acknowledged") boolean acknowledged,
            @Param("outcome") String outcome, @Param("blockersJson") String blockersJson);

    /** CAS flip to CANCELED; returns 1 exactly when the account still held the expected status. */
    int cancelAccount(@Param("userId") long userId, @Param("expectedStatus") String expectedStatus);
}
