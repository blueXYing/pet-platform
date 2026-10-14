package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only platform order face for the single operator (contract 63, HTTP10 §5.11): list,
 * detail and the append-only status timeline. The caller is always the ADMIN_WEB operator
 * carried by {@link QueryContext}; {@code authorization} re-proves the current session,
 * action code and resource scope inside the order kernel on every call (controller snapshots
 * are never authorization evidence). {@code displayStatus} reuses the same ten-value
 * DisplayOrderStatus vocabulary and truth table the C/M lists filter with. Money keeps full
 * BigDecimal precision; ids cross as canonical decimal strings. No write path exists here —
 * abnormal-close (HTTP10 §5.5) stays a separate drafted slice.
 */
public interface AdminOrderQueryApi {

    /** One page of platform orders, newest first (created_at DESC, id DESC). */
    PageResult<AdminOrderSummary> listOrders(AdminOrderListQuery query);

    /** One platform order detail: projection + service snapshot + reschedule records. */
    AdminOrderDetail getOrder(AdminOrderDetailQuery query);

    /** Append-only status timeline of one order, oldest first (created_at ASC, id ASC). */
    PageResult<StatusLog> listStatusLogs(AdminStatusLogQuery query);

    /** Wire-neutral platform summary; amounts/instants render at the HTTP adapter. */
    record AdminOrderSummary(
            String orderId, String orderNo, String userId, String merchantId, String storeId,
            String serviceId, String displayStatus, String orderStage, String paymentStatus,
            String merchantName, String serviceName, BigDecimal payAmount,
            BigDecimal refundedAmount, OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd,
            OffsetDateTime createdAt, int rescheduleCount) {}

    record ServiceSnapshot(
            String serviceName, String categoryName, String merchantName, String storeName,
            String storeAddress, BigDecimal servicePrice, int serviceDurationMinutes) {}

    /** One applied reschedule (contract 46: at most one per order, from_round=0, to_round=1). */
    record RescheduleRecord(
            String rescheduleId, OffsetDateTime oldStart, OffsetDateTime oldEnd,
            OffsetDateTime newStart, OffsetDateTime newEnd, OffsetDateTime rescheduledAt) {}

    record AdminOrderDetail(
            String orderId, String orderNo, String userId, String merchantId, String storeId,
            String serviceId, String displayStatus, String orderStage, String paymentStatus,
            String refundApplicationStatus, String refundStatus, String aftersaleStatus,
            String verificationStatus, String refundOrderId, BigDecimal originalAmount,
            BigDecimal discountAmount, BigDecimal payAmount, BigDecimal refundedAmount,
            OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd, OffsetDateTime paidAt,
            OffsetDateTime confirmedAt, OffsetDateTime verifiedAt, OffsetDateTime canceledAt,
            OffsetDateTime createdAt, int rescheduleCount, ServiceSnapshot serviceSnapshot,
            List<RescheduleRecord> rescheduleRecords) {}

    /** Six-dimension status log row (order_status_log); operatorId is null for SYSTEM rows. */
    record StatusLog(
            String logId, String orderId, String dimension, String fromStatus, String toStatus,
            String eventType, String operatorType, String operatorId, String remark,
            OffsetDateTime createdAt) {}

    /**
     * Trusted final-authorization input re-checked by the order kernel against the admin
     * authorization owner; mirrors the merchant application slice's reference shape.
     */
    record AdminAuthorization(String sessionId, long sessionGeneration) {}

    /**
     * @param displayStatus one of the ten DisplayOrderStatus values or null (no filter)
     * @param paymentStatus INIT or PAID, or null (no filter)
     * @param keyword 2..64 chars; pure Snowflake-shaped digits match orderNo exactly, anything
     *                else matches the service snapshot merchant name
     */
    record AdminOrderListQuery(
            String displayStatus, String paymentStatus, String merchantId, String storeId,
            String orderNo, String keyword, OffsetDateTime createdFrom, OffsetDateTime createdTo,
            boolean rescheduledOnly, int page, int pageSize, QueryContext context,
            AdminAuthorization authorization) {}

    record AdminOrderDetailQuery(String orderId, QueryContext context, AdminAuthorization authorization) {}

    record AdminStatusLogQuery(String orderId, QueryContext context, AdminAuthorization authorization) {}
}
