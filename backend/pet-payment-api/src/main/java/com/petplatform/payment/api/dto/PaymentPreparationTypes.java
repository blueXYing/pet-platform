package com.petplatform.payment.api.dto;
import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
public final class PaymentPreparationTypes {
    private PaymentPreparationTypes() {}
    public record PreparePaymentCommand(CommandContext context,String orderId) {}
    public record PreparedPayment(String paymentId,String paymentNo,String orderId,BigDecimal amount,
            OffsetDateTime expireAt,boolean replayed) {}
}
