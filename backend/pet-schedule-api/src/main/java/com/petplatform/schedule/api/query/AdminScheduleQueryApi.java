package com.petplatform.schedule.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;

/**
 * Read-only platform schedule view for the single operator (contract 64, PRD 6.2.5): window
 * distribution with occupancy facts, staff shifts and the reservation/lock ledger. The caller
 * is always the ADMIN_WEB operator carried by {@link QueryContext}; {@code authorization}
 * re-proves the current session, action code and resource scope inside the schedule kernel on
 * every call (controller snapshots are never authorization evidence). Strictly read-only —
 * SCH-004/53号 owns every schedule write; the 29号 capacity/protection semantics are
 * merchant-side maintenance facts this face only renders. Occupied counts reuse the 53号
 * SOLD_OUT notion: claims on the window's original row whose reservation is TEMP_LOCKED or
 * CONFIRMED. No user identity travels on these rows (orderId links into the 63号 trade face).
 */
public interface AdminScheduleQueryApi {

    /** Service window rows, newest first (start_at DESC, id DESC). */
    PageResult<AdminScheduleWindow> listWindows(AdminWindowListQuery query);

    /** Staff availability rows, newest first (start_at DESC, id DESC). */
    PageResult<AdminStaffWindow> listStaffWindows(AdminStaffWindowListQuery query);

    /** Reservation/lock rows, newest first (start_at DESC, id DESC). */
    PageResult<AdminReservationRow> listReservations(AdminReservationListQuery query);

    /**
     * Trusted final-authorization input re-checked by the schedule kernel against the admin
     * authorization owner; mirrors the contract-63 read slices' reference shape.
     */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    record AdminWindowListQuery(
            String merchantId, String storeId, String serviceId, String windowKind,
            String status, OffsetDateTime startFrom, OffsetDateTime startTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminStaffWindowListQuery(
            String storeId, String staffId, String status,
            OffsetDateTime startFrom, OffsetDateTime startTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    record AdminReservationListQuery(
            String merchantId, String storeId, String serviceId, String orderId, String status,
            OffsetDateTime startFrom, OffsetDateTime startTo,
            int page, int pageSize, QueryContext context, AdminAuthorization authorization) {}

    /**
     * One service window with occupancy facts. status is the storage-derived OPEN/CLOSED/
     * SOLD_OUT vocabulary (53号 §7); confirmedCount counts claims of CONFIRMED reservations,
     * tempLockedCount claims of unexpired TEMP_LOCKED reservations — the same effective-claim
     * notion the SOLD_OUT deriver uses. Utilization renders client-side from these facts.
     */
    record AdminScheduleWindow(
            String windowId, String merchantId, String storeId, String serviceId,
            String windowKind, OffsetDateTime startAt, OffsetDateTime endAt,
            int configuredCapacity, String status, String version, OffsetDateTime updatedAt,
            int confirmedCount, int tempLockedCount) {}

    /** One staff shift row (staff_availability_window has no merchant column by design;
     *  the storeId filter carries store attribution; no cross-module merchant join). */
    record AdminStaffWindow(
            String windowId, String storeId, String staffId,
            OffsetDateTime startAt, OffsetDateTime endAt, String status, String version,
            OffsetDateTime updatedAt) {}

    /**
     * One reservation/lock row (SQL06 §2): the 占用/临时锁定 ledger behind 6.2.5, including
     * the EXPIRED/RELEASED timeout-release history; pickup/return facts let the operator
     * re-verify the ≥120-minute return constraint without deriving anything.
     */
    record AdminReservationRow(
            String reservationId, String orderId, String storeId, String serviceId,
            String fulfillmentType, OffsetDateTime startAt, OffsetDateTime endAt,
            OffsetDateTime pickupStartAt, OffsetDateTime returnStartAt, String status,
            OffsetDateTime lockExpireAt, int capacitySnapshot, int qualifiedStaffCountSnapshot,
            OffsetDateTime createdAt) {}
}
