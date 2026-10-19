package com.petplatform.user.biz.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * Contract 66 account-deletion statements; SQL lives in resources/mapper/UserDeletionMapper.xml.
 * v0.2 cooling-period state machine: the request row moves PENDING → (user cancel, CAS)
 * CANCELED / (due durable task) ACCEPTED; the only column updates are the guarded state
 * transitions with their timestamps. The account flip is a guarded single-row CAS on the
 * expected current status and happens exclusively in the due-task execution transaction.
 */
public interface UserDeletionMapper {

    int insertDeletionRequest(@Param("id") long id, @Param("userId") long userId,
            @Param("requestId") String requestId, @Param("acknowledged") boolean acknowledged,
            @Param("status") String status, @Param("blockersJson") String blockersJson,
            @Param("cooldownDays") int cooldownDays, @Param("effectiveAt") java.time.LocalDateTime effectiveAt);

    /** The cooling-period row of a user, if any (at most one PENDING per user). */
    UserDeletionRequestRow findPendingByUser(@Param("userId") long userId);

    /** Row-locked read for the due-task execution path. */
    UserDeletionRequestRow selectByIdForUpdate(@Param("id") long id);

    /**
     * CAS cancel of the cooling-period row (user revocation); 1 row = the user held the only
     * PENDING request and it is now CANCELED.
     */
    int cancelPending(@Param("id") long id, @Param("cancelRequestId") String cancelRequestId);

    /** Auto-cancel recorded by the due task when the precheck blocks or the account is frozen. */
    int autoCancelPending(@Param("id") long id, @Param("blockersJson") String blockersJson);

    /** CAS completion of the due task: PENDING → ACCEPTED with the execution timestamp. */
    int markExecuted(@Param("id") long id);

    /** CAS flip to CANCELED; returns 1 exactly when the account still held the expected status. */
    int cancelAccount(@Param("userId") long userId, @Param("expectedStatus") String expectedStatus);

    class UserDeletionRequestRow {
        public Long id, userId;
        public String requestId, status, cancelRequestId;
        public Integer cooldownDays;
        public java.time.LocalDateTime effectiveAt, canceledAt, executedAt, createdAt, updatedAt;
    }
}
