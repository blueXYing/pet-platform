package com.petplatform.payment.api.dto;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** Internal refund coordination. Results are hints; only guarded facts prove channel success. */
public final class PaymentRefundTypes {
    private PaymentRefundTypes() {}

    public record ChannelRefundSubmitCommand(CommandContext context, String refundOrderId,
            String refundNo, String paymentId, String storeId, long bindingVersion) {}

    public record ChannelRefundQuery(CommandContext context, String refundOrderId,
            String refundNo, String paymentId, String storeId, long bindingVersion) {}

    public enum CoordinationState { QUERY_PENDING, VERIFIED_SUCCESS, VERIFIED_TERMINAL_FAILURE,
        RECONCILIATION_REQUIRED }

    public record ChannelRefundProgress(String refundOrderId, String refundNo,
            CoordinationState state, OffsetDateTime queryNotBefore) {}
}
