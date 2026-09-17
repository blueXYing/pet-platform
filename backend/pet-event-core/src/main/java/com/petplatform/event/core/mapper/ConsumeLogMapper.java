package com.petplatform.event.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** integration_event_consume_log access; SQL lives in resources/mapper/ConsumeLogMapper.xml. */
public interface ConsumeLogMapper {

    int insertClaim(@Param("id") long id, @Param("consumerName") String consumerName,
                    @Param("eventId") String eventId, @Param("eventType") String eventType);

    List<String> selectConsumerNames(@Param("eventId") String eventId);
}
