package com.petplatform.order.biz.application;

import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetail;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderSummary;
import com.petplatform.order.api.query.AdminOrderQueryApi.StatusLog;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire projection for the contract-63 platform order face. The order module owns the
 * derivation AND the wire naming of its raw facts plus the server-computed display status
 * (ARCH-005: the same owner discipline as OrderSnapshotWire) — boot adapters only relay these
 * maps verbatim. All ids stay canonical decimal strings, money keeps two decimals, instants
 * render UTC milliseconds; no sensitive field (phone/address contact plaintext, credential or
 * reason ciphers) exists on these records.
 */
public final class AdminOrderSnapshotWire {

    private AdminOrderSnapshotWire() {}

    public static Map<String, Object> fields(AdminOrderSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", summary.orderId());
        row.put("orderNo", summary.orderNo());
        row.put("userId", summary.userId());
        row.put("merchantId", summary.merchantId());
        row.put("storeId", summary.storeId());
        row.put("serviceId", summary.serviceId());
        row.put("displayStatus", summary.displayStatus());
        row.put("orderStage", summary.orderStage());
        row.put("paymentStatus", summary.paymentStatus());
        row.put("merchantName", summary.merchantName());
        row.put("serviceName", summary.serviceName());
        row.put("payAmount", money(summary.payAmount()));
        row.put("refundedAmount", money(summary.refundedAmount()));
        row.put("appointmentStart", time(summary.appointmentStart()));
        row.put("appointmentEnd", time(summary.appointmentEnd()));
        row.put("createdAt", time(summary.createdAt()));
        row.put("rescheduleCount", summary.rescheduleCount());
        return row;
    }

    public static Map<String, Object> detailFields(AdminOrderDetail detail) {
        Map<String, Object> row = fields(new AdminOrderSummary(
                detail.orderId(), detail.orderNo(), detail.userId(), detail.merchantId(),
                detail.storeId(), detail.serviceId(), detail.displayStatus(), detail.orderStage(),
                detail.paymentStatus(), detail.serviceSnapshot().merchantName(),
                detail.serviceSnapshot().serviceName(), detail.payAmount(), detail.refundedAmount(),
                detail.appointmentStart(), detail.appointmentEnd(), detail.createdAt(),
                detail.rescheduleCount()));
        row.put("originalAmount", money(detail.originalAmount()));
        row.put("discountAmount", money(detail.discountAmount()));
        row.put("refundApplicationStatus", detail.refundApplicationStatus());
        row.put("refundStatus", detail.refundStatus());
        row.put("aftersaleStatus", detail.aftersaleStatus());
        row.put("verificationStatus", detail.verificationStatus());
        row.put("refundOrderId", detail.refundOrderId());
        row.put("paidAt", time(detail.paidAt()));
        row.put("confirmedAt", time(detail.confirmedAt()));
        row.put("verifiedAt", time(detail.verifiedAt()));
        row.put("canceledAt", time(detail.canceledAt()));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceName", detail.serviceSnapshot().serviceName());
        snapshot.put("categoryName", detail.serviceSnapshot().categoryName());
        snapshot.put("merchantName", detail.serviceSnapshot().merchantName());
        snapshot.put("storeName", detail.serviceSnapshot().storeName());
        snapshot.put("storeAddress", detail.serviceSnapshot().storeAddress());
        snapshot.put("servicePrice", money(detail.serviceSnapshot().servicePrice()));
        snapshot.put("serviceDurationMinutes", detail.serviceSnapshot().serviceDurationMinutes());
        row.put("serviceSnapshot", snapshot);
        row.put("rescheduleRecords", detail.rescheduleRecords().stream()
                .map(record -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rescheduleId", record.rescheduleId());
                    item.put("oldStart", time(record.oldStart()));
                    item.put("oldEnd", time(record.oldEnd()));
                    item.put("newStart", time(record.newStart()));
                    item.put("newEnd", time(record.newEnd()));
                    item.put("rescheduledAt", time(record.rescheduledAt()));
                    return item;
                })
                .toList());
        return row;
    }

    public static Map<String, Object> logFields(StatusLog log) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("logId", log.logId());
        row.put("orderId", log.orderId());
        row.put("dimension", log.dimension());
        row.put("fromStatus", log.fromStatus());
        row.put("toStatus", log.toStatus());
        row.put("eventType", log.eventType());
        row.put("operatorType", log.operatorType());
        row.put("operatorId", log.operatorId());
        row.put("remark", log.remark());
        row.put("createdAt", time(log.createdAt()));
        return row;
    }

    private static String money(java.math.BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    private static String time(OffsetDateTime value) {
        return value == null
                ? null
                : new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value.toInstant());
    }
}
