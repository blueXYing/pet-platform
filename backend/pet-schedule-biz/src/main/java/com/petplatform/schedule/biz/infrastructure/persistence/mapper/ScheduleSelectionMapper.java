package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Read-only selection facts in one repeatable-read transaction. */
public interface ScheduleSelectionMapper {
    List<Map<String, Object>> windows(@Param("storeId") long storeId,
            @Param("serviceId") long serviceId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
    List<Map<String, Object>> claims(@Param("storeId") long storeId,
            @Param("serviceId") long serviceId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
    Integer brokenClaimLinks(@Param("storeId") long storeId,
            @Param("serviceId") long serviceId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
