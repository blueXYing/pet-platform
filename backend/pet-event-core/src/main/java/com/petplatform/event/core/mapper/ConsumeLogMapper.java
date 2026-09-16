package com.petplatform.event.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** integration_event_consume_log statements, SQL kept verbatim (Event Catalog §15/§21). */
public interface ConsumeLogMapper {

    @Insert("""
            INSERT INTO integration_event_consume_log
            (id,consumer_name,event_id,event_type,consumed_at)
            VALUES (#{id},#{consumerName},#{eventId},#{eventType},NOW(3))
            """)
    int insertClaim(@Param("id") long id, @Param("consumerName") String consumerName,
                    @Param("eventId") String eventId, @Param("eventType") String eventType);

    @Select("SELECT consumer_name FROM integration_event_consume_log WHERE event_id=#{eventId}")
    List<String> selectConsumerNames(@Param("eventId") String eventId);
}
