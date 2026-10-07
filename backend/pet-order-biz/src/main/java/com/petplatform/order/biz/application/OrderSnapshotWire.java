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
        return row;
    }

    private static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? null : INSTANT.format(value.toInstant());
    }
}
