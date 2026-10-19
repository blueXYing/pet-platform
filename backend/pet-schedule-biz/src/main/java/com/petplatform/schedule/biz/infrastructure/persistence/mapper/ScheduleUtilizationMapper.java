package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * Contract 70 §2 排期利用率 statements (resources/mapper/ScheduleUtilizationMapper.xml):
 * store+merchant double-key scoped read aggregates over SCH-owned tables only. Both statements
 * clamp each row's [start,end) overlap against the stats window; minute granularity is exact
 * because every schedule fact is minute-aligned (SSOT §13).
 */
public interface ScheduleUtilizationMapper {

    /** Σ OPEN window capacity minutes overlapping [from,to); store prefix of
     *  idx_schedule_service_time keeps this an index range scan. */
    long availableMinutes(@Param("merchantId") long merchantId, @Param("storeId") long storeId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Σ CONFIRMED reservation minutes overlapping [from,to); store prefix of
     *  idx_reservation_service_time keeps this an index range scan. */
    long bookedMinutes(@Param("merchantId") long merchantId, @Param("storeId") long storeId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
