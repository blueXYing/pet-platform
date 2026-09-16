package com.petplatform.event.core.mapper;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** integration_event_outbox statements, SQL kept verbatim (Schema 06 §12, Scheduler §21/§22). */
public interface OutboxMapper {

    @Update("SET SESSION time_zone = '+00:00'")
    void setTimeZoneUtc();

    @Select("""
            SELECT id,event_id,event_type,event_version,occurred_at,aggregate_type,
                   aggregate_id,trace_id,payload,retry_count
            FROM integration_event_outbox
            WHERE status = 'NEW'
               OR (status = 'FAILED' AND next_retry_at <= NOW(3))
               OR (status = 'PUBLISHING' AND lease_until < NOW(3))
            ORDER BY created_at ASC, id ASC
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    OutboxRowEntity selectClaimCandidate();

    @Update("""
            UPDATE integration_event_outbox SET status='PUBLISHING',lease_owner=#{owner},
            lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE id=#{id}
            """)
    int updateClaim(@Param("owner") String owner, @Param("micros") long micros, @Param("id") long id);

    @Update("""
            UPDATE integration_event_outbox SET lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE id=#{id} AND status='PUBLISHING' AND lease_owner=#{owner} AND lease_until>=NOW(3)
            """)
    int updateHeartbeat(@Param("micros") long micros, @Param("id") long id, @Param("owner") String owner);

    @Update("""
            UPDATE integration_event_outbox SET status='PUBLISHED',published_at=NOW(3),
            lease_owner=NULL,lease_until=NULL
            WHERE id=#{id} AND status='PUBLISHING' AND lease_owner=#{owner} AND lease_until>=NOW(3)
            """)
    int updateMarkPublished(@Param("id") long id, @Param("owner") String owner);

    @Update("""
            UPDATE integration_event_outbox SET status='FAILED',
            retry_count=retry_count+1,next_retry_at=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3)),
            lease_owner=NULL,lease_until=NULL
            WHERE id=#{id} AND status='PUBLISHING' AND lease_owner=#{owner} AND lease_until>=NOW(3)
            """)
    int updateMarkFailed(@Param("micros") long micros, @Param("id") long id, @Param("owner") String owner);

    @Update("""
            UPDATE integration_event_outbox SET status='NEW',lease_owner=NULL,lease_until=NULL
            WHERE id=#{id} AND status='PUBLISHING' AND lease_owner=#{owner} AND lease_until>=NOW(3)
            """)
    int updateRelease(@Param("id") long id, @Param("owner") String owner);

    @Select("SELECT id FROM integration_event_outbox WHERE id=#{id} FOR UPDATE")
    Long selectIdForUpdate(@Param("id") long id);

    @Insert("""
            INSERT INTO integration_event_outbox
            (id,event_id,aggregate_type,aggregate_id,event_type,event_version,payload,
             occurred_at,status,retry_count,trace_id,created_at)
            VALUES (#{rowId},#{eventId},#{aggregateType},#{aggregateId},#{eventType},#{eventVersion},#{payload},
             #{occurredAt},'NEW',0,#{traceId},NOW(3))
            """)
    int insertOutbox(@Param("rowId") long rowId, @Param("eventId") String eventId,
                     @Param("aggregateType") String aggregateType, @Param("aggregateId") long aggregateId,
                     @Param("eventType") String eventType, @Param("eventVersion") int eventVersion,
                     @Param("payload") String payload, @Param("occurredAt") OffsetDateTime occurredAt,
                     @Param("traceId") String traceId);
}
