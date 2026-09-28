package com.petplatform.order.api.dto;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class OrderPaymentResultTypes {
    private OrderPaymentResultTypes() {}
    /** Carries the event claim; PAYMENT's guarded facts remain the authority. */
    public record ConsumePaymentSucceededCommand(CommandContext context, String sourceEventId,
            String paymentId, String orderId, String channelTradeNo,
            BigDecimal paidAmount, OffsetDateTime paidAt) {}
    public enum ConsumePaymentResult { NORMAL_PAID, LATE_PAYMENT, NOOP }
}
