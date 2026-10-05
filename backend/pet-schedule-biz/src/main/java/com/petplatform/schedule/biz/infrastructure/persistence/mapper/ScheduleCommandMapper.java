package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Transaction-bound schedule guards, protection facts, and reservation writes. */
public interface ScheduleCommandMapper {
    int releaseRefund(@org.apache.ibatis.annotations.Param("reservationId") long reservationId,
        @org.apache.ibatis.annotations.Param("version") long version);
    int refundReleaseProof(@org.apache.ibatis.annotations.Param("reservationId") long reservationId,
        @org.apache.ibatis.annotations.Param("requestId") byte[] requestId);
    int ensureStoreGuard(@Param("storeId") long storeId);
    Long lockStoreGuard(@Param("storeId") long storeId);
    List<Long> lockReservationByOrder(@Param("orderId") long orderId);
    Map<String, Object> lockReservation(@Param("reservationId") long reservationId);
    LocalDateTime utcNow();

    int insertReservation(@Param("row") Map<String, Object> row);
    int insertClaim(@Param("row") Map<String, Object> row);
    int insertHoldAudit(@Param("row") Map<String, Object> row);
    int confirmReservation(@Param("reservationId") long reservationId,
            @Param("orderId") long orderId, @Param("storeId") long storeId,
            @Param("version") long version, @Param("expires") LocalDateTime expires,
            @Param("now") LocalDateTime now);
    int expireReservation(@Param("reservationId") long reservationId,
            @Param("version") long version, @Param("expires") LocalDateTime expires,
            @Param("now") LocalDateTime now);
    int insertSystemAudit(@Param("row") Map<String, Object> row);

    /** Original windows of one reservation, for the derived SOLD_OUT release linkage. */
    List<Long> claimWindowIds(@Param("reservationId") long reservationId);

    /** CAS flip of the system-derived OPEN/SOLD_OUT window state (Contract52 §3). */
    int setWindowDerivedStatus(@Param("windowId") long windowId,
        @Param("expectedStatus") String expectedStatus,
        @Param("nextStatus") String nextStatus,
        @Param("now") LocalDateTime now);

    List<Map<String, Object>> lockedWindows(@Param("storeId") long storeId);
    List<Map<String, Object>> lockedReservations(@Param("storeId") long storeId);
    List<Map<String, Object>> lockedClaims(@Param("storeId") long storeId);
    List<Long> crossStoreClaims(@Param("storeId") long storeId);
    List<Map<String, Object>> lockedCapabilities(@Param("staffIds") List<Long> staffIds);
    List<Map<String, Object>> lockedAvailability(@Param("storeId") long storeId);
}
