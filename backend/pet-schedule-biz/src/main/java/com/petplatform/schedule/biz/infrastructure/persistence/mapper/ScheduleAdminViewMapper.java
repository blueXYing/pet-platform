package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminReservationRow;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminStaffWindowRow;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminWindowRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * Contract-64 schedule view read side (windows with occupancy, staff shifts, reservation
 * ledger). Statements live in ScheduleAdminViewMapper.xml; module-owned tables only, every
 * filter binds with #{}.
 */
public interface ScheduleAdminViewMapper {

    long countWindows(
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("serviceId") Long serviceId,
            @Param("windowKind") String windowKind,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo);

    List<AdminWindowRow> selectWindowPage(
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("serviceId") Long serviceId,
            @Param("windowKind") String windowKind,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("offset") long offset);

    long countStaffWindows(
            @Param("storeId") Long storeId,
            @Param("staffId") Long staffId,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo);

    List<AdminStaffWindowRow> selectStaffWindowPage(
            @Param("storeId") Long storeId,
            @Param("staffId") Long staffId,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo,
            @Param("limit") int limit,
            @Param("offset") long offset);

    long countReservations(
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("serviceId") Long serviceId,
            @Param("orderId") Long orderId,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo);

    List<AdminReservationRow> selectReservationPage(
            @Param("merchantId") Long merchantId,
            @Param("storeId") Long storeId,
            @Param("serviceId") Long serviceId,
            @Param("orderId") Long orderId,
            @Param("status") String status,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
