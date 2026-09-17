package com.petplatform.task.core.mapper;

import org.apache.ibatis.annotations.Param;

/** async_task / async_task_attempt access; SQL lives in resources/mapper/AsyncTaskMapper.xml. */
public interface AsyncTaskMapper {

    void setTimeZoneUtc();

    AsyncTaskRowEntity selectClaimCandidate();

    int selectMaxAttemptNo(@Param("taskId") long taskId);

    int expireAbandonedAttempts(@Param("taskId") long taskId);

    int updateClaim(@Param("owner") String owner, @Param("micros") long micros,
                    @Param("version") long version, @Param("taskId") long taskId);

    int insertAttempt(@Param("attemptId") long attemptId, @Param("taskId") long taskId,
                      @Param("attemptNo") int attemptNo, @Param("instanceId") String instanceId);

    int updateHeartbeat(@Param("micros") long micros, @Param("taskId") long taskId,
                        @Param("owner") String owner, @Param("version") long version);

    int updateComplete(@Param("state") String state, @Param("retryCount") int retryCount,
                       @Param("resultCode") String resultCode, @Param("errorCode") String errorCode,
                       @Param("delayMicros") long delayMicros, @Param("taskId") long taskId,
                       @Param("owner") String owner, @Param("version") long version);

    int updateAttemptResult(@Param("attemptResult") String attemptResult,
                            @Param("errorCode") String errorCode, @Param("attemptId") long attemptId,
                            @Param("taskId") long taskId, @Param("attemptNo") int attemptNo,
                            @Param("instanceId") String instanceId);

    Long selectIdForUpdate(@Param("taskId") long taskId);
}
