package com.petplatform.service.biz.infrastructure.persistence.mapper;

import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.BindingRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.FlashPriceRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ParticipantCountRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ReservationRow;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ServiceFactsRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Statement surface of FlashPriceMapper.xml (contract 77 / SQL73; all SQL lives in the XML). */
public interface FlashPriceMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    // ------------------------------------------------------------------ flash row

    int insertFlashPrice(
            @Param("id") long id,
            @Param("serviceId") long serviceId,
            @Param("merchantId") long merchantId,
            @Param("storeId") long storeId,
            @Param("title") String title,
            @Param("description") String description,
            @Param("discountRate") BigDecimal discountRate,
            @Param("flashPrice") BigDecimal flashPrice,
            @Param("listPriceSnapshot") BigDecimal listPriceSnapshot,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("totalQuota") Integer totalQuota,
            @Param("perUserLimit") Integer perUserLimit,
            @Param("status") String status,
            @Param("createdByType") String createdByType,
            @Param("createdById") long createdById,
            @Param("now") LocalDateTime now);

    /** Full-field rewrite of an editable row, optimistic on version (contract 77 §3.3). */
    int updateFlashPrice(
            @Param("id") long id,
            @Param("title") String title,
            @Param("description") String description,
            @Param("discountRate") BigDecimal discountRate,
            @Param("flashPrice") BigDecimal flashPrice,
            @Param("listPriceSnapshot") BigDecimal listPriceSnapshot,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("totalQuota") Integer totalQuota,
            @Param("perUserLimit") Integer perUserLimit,
            @Param("rejectReason") String rejectReason,
            @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);

    /** Pure status transition (submit/cancel/reject/pause/end), CAS on status+version. */
    int updateStatus(
            @Param("id") long id,
            @Param("fromStatuses") List<String> fromStatuses,
            @Param("toStatus") String toStatus,
            @Param("rejectReason") String rejectReason,
            @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);

    /** Approve: re-snapshots the current service price and the recomputed flash price. */
    int updateApprovedPrice(
            @Param("id") long id,
            @Param("fromStatuses") List<String> fromStatuses,
            @Param("toStatus") String toStatus,
            @Param("listPriceSnapshot") BigDecimal listPriceSnapshot,
            @Param("flashPrice") BigDecimal flashPrice,
            @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);

    FlashPriceRow selectById(@Param("id") long id);

    FlashPriceRow selectByIdForUpdate(@Param("id") long id);

    long countOverlap(
            @Param("serviceId") long serviceId,
            @Param("excludeId") Long excludeId,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    List<FlashPriceRow> selectMerchantPage(
            @Param("serviceId") long serviceId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countMerchant(@Param("serviceId") long serviceId, @Param("status") String status);

    List<FlashPriceRow> selectAdminPage(
            @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

    long countAdmin(@Param("status") String status);

    long countParticipants(@Param("flashPriceId") long flashPriceId);

    List<ParticipantCountRow> countParticipantsBatch(@Param("ids") List<Long> ids);

    // ------------------------------------------------------------------ service facts

    /** Locks the service_item row: the serialization point for window-overlap checks. */
    ServiceFactsRow lockServiceFacts(@Param("serviceId") long serviceId);

    ServiceFactsRow selectServiceFacts(@Param("serviceId") long serviceId);

    // ------------------------------------------------------------------ read side

    /** In-window SCHEDULED/ACTIVE candidates (overlap rule keeps this at most one). */
    List<FlashPriceRow> selectEffectiveFlash(
            @Param("serviceId") long serviceId, @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------ reserve

    FlashPriceRow selectFlashForUpdate(@Param("id") long id);

    long countActiveByUser(@Param("flashPriceId") long flashPriceId, @Param("userId") long userId);

    int incrementSoldCount(
            @Param("id") long id,
            @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);

    int insertReservation(
            @Param("id") long id,
            @Param("flashPriceId") long flashPriceId,
            @Param("orderId") long orderId,
            @Param("userId") long userId,
            @Param("reservedPrice") BigDecimal reservedPrice,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    ReservationRow selectReservationByOrderId(@Param("orderId") long orderId);

    ReservationRow selectReservationByIdForUpdate(@Param("id") long id);

    // ------------------------------------------------------------------ paid / release

    int markConsumedByOrderId(@Param("orderId") long orderId, @Param("now") LocalDateTime now);

    List<ReservationRow> selectExpiredReservations(
            @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    int releaseReservation(
            @Param("id") long id,
            @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);

    int decrementSoldCount(
            @Param("flashPriceId") long flashPriceId, @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------ audit + idempotency

    int insertAudit(
            @Param("id") long id,
            @Param("flashPriceId") long flashPriceId,
            @Param("actionType") String actionType,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operatorType") String operatorType,
            @Param("operatorId") long operatorId,
            @Param("requestId") String requestId,
            @Param("traceId") String traceId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    BindingRow selectBinding(@Param("requestKey") String requestKey);

    int insertBinding(
            @Param("id") long id,
            @Param("requestKey") String requestKey,
            @Param("commandType") String commandType,
            @Param("paramsSha256") String paramsSha256,
            @Param("paramsCanonical") byte[] paramsCanonical,
            @Param("now") LocalDateTime now);

    int markBindingSucceeded(
            @Param("requestKey") String requestKey,
            @Param("receiptJson") String receiptJson,
            @Param("now") LocalDateTime now);

    int deleteBinding(@Param("requestKey") String requestKey);
}
