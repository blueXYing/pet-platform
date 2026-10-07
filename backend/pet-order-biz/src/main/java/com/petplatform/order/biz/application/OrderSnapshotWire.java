package com.petplatform.order.biz.application;

import com.petplatform.order.api.dto.OrderSnapshotDTO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP wire projection of an {@link OrderSnapshotDTO} for contract 10 §3.7 (C-004 read slice).
 * Lives inside the order module on purpose: ARCH-005 keeps the raw fact fields and the
 * domain-computed displayStatus together only here, so no other surface re-derives anything.
 * Renderers: string ids, two-decimal amounts, ISO-8601 millisecond instants.
 */
public final class OrderSnapshotWire {
    private OrderSnapshotWire() {}

    private static final java.time.format.DateTimeFormatter INSTANT =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    public static Map<String, Object> fields(OrderSnapshotDTO item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", item.orderId());
        row.put("orderNo", item.orderNo());
        // §3.8 reschedule page coordinates (46号 slice): the C face needs the service/store the
        // order belongs to for the §3.4 availability read and the current order version for the
        // expectedOrderVersion CAS — raw facts from the same single projection, never re-derived.
        row.put("serviceId", item.serviceId());
        row.put("storeId", item.storeId());
        row.put("orderVersion", Long.toString(item.version()));
        row.put("displayStatus", item.displayStatus());
        row.put("orderStage", item.orderStage());
        row.put("paymentStatus", item.paymentStatus());
        row.put("refundApplicationStatus", item.refundApplicationStatus());
        row.put("refundStatus", item.refundStatus());
        row.put("afterSaleStatus", item.afterSaleStatus());
        row.put("verificationStatus", item.verificationStatus());
        row.put("payAmount", amount(item.payAmount()));
        row.put("appointmentStart", instant(item.appointmentStart()));
        row.put("appointmentEnd", instant(item.appointmentEnd()));
        row.put("verifiedAt", instant(item.verifiedAt()));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("canPay", item.actions().canPay());
        actions.put("canReschedule", item.actions().canReschedule());
        actions.put("canApplyRefund", item.actions().canApplyRefund());
        actions.put("canShowVerificationCode", item.actions().canShowVerificationCode());
        actions.put("canReview", item.actions().canReview());
        actions.put("canApplyAfterSale", item.actions().canApplyAfterSale());
        row.put("actions", actions);
        return row;
    }

    private static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? null : INSTANT.format(value.toInstant());
    }
}
