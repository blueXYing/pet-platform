package com.petplatform.schedule.biz.infrastructure.persistence.mapper;

import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleCommandBindingEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Merchant schedule write statements: idempotency bindings, windows, staff windows,
 * capability sets and append-only write actions. All statements run inside the caller's
 * guarded top-level transaction (or the short admission transaction for bindings only). */
public interface ScheduleWriteMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    // ------------------------------------------------------------ 23号 idempotency (14号 table)

    ScheduleCommandBindingEntity selectBindingForUpdate(@Param("requestKey") String requestKey);

    int insertBinding(@Param("id") long id, @Param("requestKey") String requestKey,
            @Param("canonicalVersion") String canonicalVersion,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical);

    int markBindingSucceeded(@Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson);

    // ------------------------------------------------------------ service windows

    Map<String, Object> selectWindowByIdForUpdate(@Param("windowId") long windowId);

    int insertWindow(@Param("row") Map<String, Object> row);

    int updateWindow(@Param("windowId") long windowId,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
            @Param("capacity") int capacity, @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    int closeWindow(@Param("windowId") long windowId,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    int openWindow(@Param("windowId") long windowId,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    // ------------------------------------------------------------ staff availability windows

    Map<String, Object> selectStaffWindowByIdForUpdate(@Param("windowId") long windowId);

    int insertStaffWindow(@Param("row") Map<String, Object> row);

    int updateStaffWindow(@Param("windowId") long windowId,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    int closeStaffWindow(@Param("windowId") long windowId,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    int openStaffWindow(@Param("windowId") long windowId,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    /** Guarded staff-window listing for in-transaction overlap checks (all statuses). */
    List<Map<String, Object>> listStaffWindows(@Param("storeId") long storeId,
            @Param("staffId") long staffId);

    // ------------------------------------------------------------ capability set (SCHC-2)

    Map<String, Object> selectCapabilitySetForUpdate(@Param("staffId") long staffId);

    int insertCapabilitySet(@Param("staffId") long staffId, @Param("storeId") long storeId,
            @Param("version") long version, @Param("now") LocalDateTime now);

    int casCapabilitySetVersion(@Param("staffId") long staffId,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextVersion") long nextVersion, @Param("now") LocalDateTime now);

    List<Map<String, Object>> selectCapabilitiesForUpdate(@Param("staffId") long staffId);

    int insertCapability(@Param("id") long id, @Param("staffId") long staffId,
            @Param("serviceId") long serviceId, @Param("now") LocalDateTime now);

    int deleteCapability(@Param("staffId") long staffId, @Param("serviceId") long serviceId);

    // ------------------------------------------------------------ append-only write audit

    int insertWriteAction(@Param("row") Map<String, Object> row);
}
