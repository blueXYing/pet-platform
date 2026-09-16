package com.petplatform.task.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** async_task / async_task_attempt statements, SQL kept verbatim (SQL13, PLAT-006). */
public interface AsyncTaskMapper {

    @Update("SET SESSION time_zone = '+00:00'")
    void setTimeZoneUtc();

    @Select("""
            SELECT * FROM async_task
            WHERE (status IN ('READY','RETRY_WAIT') AND execute_at <= NOW(3))
               OR (status = 'RUNNING' AND lease_until < NOW(3))
            ORDER BY priority DESC, execute_at ASC, id ASC
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    AsyncTaskRowEntity selectClaimCandidate();

    @Select("SELECT COALESCE(MAX(attempt_no),0) FROM async_task_attempt WHERE task_id=#{taskId}")
    int selectMaxAttemptNo(@Param("taskId") long taskId);

    @Update("""
            UPDATE async_task_attempt SET result='RETRY', error_code='LEASE_EXPIRED',
            finished_at=NOW(3), duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,NOW(3)) DIV 1000)
            WHERE task_id=#{taskId} AND finished_at IS NULL
            """)
    int expireAbandonedAttempts(@Param("taskId") long taskId);

    @Update("""
            UPDATE async_task SET status='RUNNING',lease_owner=#{owner},
            lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3)), version=#{version}, updated_at=NOW(3)
            WHERE id=#{taskId}
            """)
    int updateClaim(@Param("owner") String owner, @Param("micros") long micros,
                    @Param("version") long version, @Param("taskId") long taskId);

    @Insert("""
            INSERT INTO async_task_attempt
            (id,task_id,attempt_no,instance_id,started_at,created_at)
            VALUES (#{attemptId},#{taskId},#{attemptNo},#{instanceId},NOW(3),NOW(3))
            """)
    int insertAttempt(@Param("attemptId") long attemptId, @Param("taskId") long taskId,
                      @Param("attemptNo") int attemptNo, @Param("instanceId") String instanceId);

    @Update("""
            UPDATE async_task SET lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3)), updated_at=NOW(3)
            WHERE id=#{taskId} AND status='RUNNING' AND lease_owner=#{owner} AND version=#{version} AND lease_until>=NOW(3)
            """)
    int updateHeartbeat(@Param("micros") long micros, @Param("taskId") long taskId,
                        @Param("owner") String owner, @Param("version") long version);

    @Update("""
            UPDATE async_task SET status=#{state},retry_count=#{retryCount},last_result_code=#{resultCode},
            last_error_code=#{errorCode},last_error_message=NULL,lease_owner=NULL,lease_until=NULL,updated_at=NOW(3),
            execute_at=IF(#{state}='RETRY_WAIT',TIMESTAMPADD(MICROSECOND,#{delayMicros},NOW(3)),execute_at),
            finished_at=IF(#{state}='RETRY_WAIT',NULL,NOW(3))
            WHERE id=#{taskId} AND status='RUNNING' AND lease_owner=#{owner} AND version=#{version} AND lease_until>=NOW(3)
            """)
    int updateComplete(@Param("state") String state, @Param("retryCount") int retryCount,
                       @Param("resultCode") String resultCode, @Param("errorCode") String errorCode,
                       @Param("delayMicros") long delayMicros, @Param("taskId") long taskId,
                       @Param("owner") String owner, @Param("version") long version);

    @Update("""
            UPDATE async_task_attempt SET result=#{attemptResult},error_code=#{errorCode},finished_at=NOW(3),
            duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,started_at,NOW(3)) DIV 1000)
            WHERE id=#{attemptId} AND task_id=#{taskId} AND attempt_no=#{attemptNo} AND instance_id=#{instanceId} AND finished_at IS NULL
            """)
    int updateAttemptResult(@Param("attemptResult") String attemptResult,
                            @Param("errorCode") String errorCode, @Param("attemptId") long attemptId,
                            @Param("taskId") long taskId, @Param("attemptNo") int attemptNo,
                            @Param("instanceId") String instanceId);

    @Select("SELECT id FROM async_task WHERE id=#{taskId} FOR UPDATE")
    Long selectIdForUpdate(@Param("taskId") long taskId);
}
