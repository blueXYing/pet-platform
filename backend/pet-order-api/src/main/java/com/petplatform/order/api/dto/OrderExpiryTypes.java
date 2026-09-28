package com.petplatform.order.api.dto;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** The generation identifies one original ten-minute hold, not a later payment attempt. */
public final class OrderExpiryTypes {
    private OrderExpiryTypes() {}

    public record ExpireOrderCommand(CommandContext context, String orderId,
            String reservationId, long expectedReservationVersion,
            OffsetDateTime expectedPaymentExpireAt) {}

    public enum ExpireOrderResult { CLOSED, NOOP, NOT_DUE }

    /** afterOrderId="0" begins a bounded pass; null nextOrderId ends it. */
    public record ReconcileExpiryTasksCommand(CommandContext context, int limit,
            String afterOrderId) {}
    public record RecoveryScanResult(int scanned, String nextOrderId) {}
}
