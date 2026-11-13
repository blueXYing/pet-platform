package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAvailabilityWindowEntity;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.StaffAvailabilityEntity;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.StaffCapabilityEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** schedule_availability_window / schedule_reservation read statements (SQL in the XML). */
public interface ScheduleReadMapper {

    /** Every capability for the target service, including unknown statuses and dangling staff IDs. */
    List<StaffCapabilityEntity> selectServiceCapabilities(@Param("serviceId") long serviceId);

    /** All rows for qualified candidates in this store, so malformed intervals cannot be hidden. */
    List<StaffAvailabilityEntity> selectCandidateStaffAvailability(
            @Param("storeId") long storeId, @Param("staffIds") List<Long> staffIds);

    /**
     * OPEN/SOLD_OUT unified slots of the store overlapping [from, to), start-ascending
     * (CCR-SCH-SLOT-001: the slot timeline is store-wide; per-service bookability is a
     * computation, not a filter). Non-open status values (CLOSED or anything unknown) never
     * surface: the query fails toward "not bookable".
     */
    List<ScheduleAvailabilityWindowEntity> selectOpenWindows(
            @Param("storeId") long storeId,
            @Param("fromAt") LocalDateTime fromAt,
            @Param("toAt") LocalDateTime toAt);

    /** Effective claims (TEMP_LOCKED/CONFIRMED reservations) on one slot — the single-pool
     * occupancy notion shared with the SOLD_OUT deriver (CCR-SCH-SLOT-001, cross-service). */
    long countActiveClaimsOnWindow(@Param("windowId") long windowId);

    /** Workbench slot list (SCH-004 merchant reads): full states, status filter optional. A null
     * limit keeps the legacy full list; paged mode bounds it with LIMIT/OFFSET (53号 §3.3). */
    List<java.util.Map<String, Object>> listWindows(@Param("storeId") long storeId,
            @Param("status") String status, @Param("limit") Integer limit,
            @Param("offset") int offset);

    /** Filter-matched slot count for the paged workbench list; same filters as listWindows. */
    long countWindows(@Param("storeId") long storeId, @Param("status") String status);

    /** Workbench staff availability list for one staff member, all statuses. */
    List<java.util.Map<String, Object>> listStaffWindows(@Param("storeId") long storeId,
            @Param("staffId") long staffId);

    /** Store bookable range for the availability filter (CCR-SCH-SLOT-002); null = defaults. */
    java.util.Map<String, Object> selectBookingWindow(@Param("storeId") long storeId);
}
