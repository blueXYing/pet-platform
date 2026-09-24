package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAvailabilityWindowEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** schedule_availability_window / schedule_reservation read statements (SQL in the XML). */
public interface ScheduleReadMapper {

    /**
     * OPEN windows of the store/service overlapping [from, to), start-ascending. Non-OPEN status
     * values (CLOSED or anything unknown) never surface: the query fails toward "not bookable".
     */
    List<ScheduleAvailabilityWindowEntity> selectOpenWindows(
            @Param("storeId") long storeId,
            @Param("serviceId") long serviceId,
            @Param("fromAt") LocalDateTime fromAt,
            @Param("toAt") LocalDateTime toAt);

    /** Active (TEMP_LOCKED/CONFIRMED) reservations overlapping the window half-open interval. */
    long countActiveReservations(
            @Param("storeId") long storeId,
            @Param("serviceId") long serviceId,
            @Param("fromAt") LocalDateTime fromAt,
            @Param("toAt") LocalDateTime toAt);
}
