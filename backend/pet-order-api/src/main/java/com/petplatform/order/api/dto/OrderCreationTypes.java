package com.petplatform.order.api.dto;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
public final class OrderCreationTypes {
    private OrderCreationTypes() {}
    public record CreateOrderCommand(CommandContext context, String storeId, String serviceId,
            String petId, String fulfillmentType, OffsetDateTime appointmentStart,
            OffsetDateTime appointmentEnd, OffsetDateTime pickupStart, OffsetDateTime returnStart,
            String selectedGeneralWindowId, String selectedPickupWindowId, String selectedReturnWindowId,
            String couponInstanceId, String remark, String serviceAddress) {}
    public record CreateOrderResult(String orderId, String orderNo, String displayStatus,
            String payAmount, OffsetDateTime paymentExpireAt, boolean created, boolean replayed) {}
}
