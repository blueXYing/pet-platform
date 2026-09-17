package com.petplatform.event.core.mapper;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Param;

/** integration_event_outbox access; SQL lives in resources/mapper/OutboxMapper.xml. */
public interface OutboxMapper {

    void setTimeZoneUtc();

    OutboxRowEntity selectClaimCandidate();

    int updateClaim(@Param("owner") String owner, @Param("micros") long micros, @Param("id") long id);

    int updateHeartbeat(@Param("micros") long micros, @Param("id") long id, @Param("owner") String owner);

    int updateMarkPublished(@Param("id") long id, @Param("owner") String owner);

    int updateMarkFailed(@Param("micros") long micros, @Param("id") long id, @Param("owner") String owner);

    int updateRelease(@Param("id") long id, @Param("owner") String owner);

    Long selectIdForUpdate(@Param("id") long id);

    int insertOutbox(@Param("rowId") long rowId, @Param("eventId") String eventId,
                     @Param("aggregateType") String aggregateType, @Param("aggregateId") long aggregateId,
                     @Param("eventType") String eventType, @Param("eventVersion") int eventVersion,
                     @Param("payload") String payload, @Param("occurredAt") OffsetDateTime occurredAt,
                     @Param("traceId") String traceId);
}
