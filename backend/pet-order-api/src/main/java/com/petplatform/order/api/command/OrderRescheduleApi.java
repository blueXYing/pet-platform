package com.petplatform.order.api.command;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
/** Internal, default-off command. No C HTTP route is registered by this delivery. */
public interface OrderRescheduleApi {
    Receipt reschedule(Command command);
    record Command(CommandContext context, String orderId, String expectedOrderVersion,
        OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd,
        OffsetDateTime pickupStart, OffsetDateTime returnStart, String selectedGeneralWindowId,
        String selectedPickupWindowId, String selectedReturnWindowId) {}
    record Receipt(String orderId, String reservationId, String rescheduleId, int confirmRound,
        String orderVersion, String orderStageAtCommit, String appointmentStart, String appointmentEnd,
        String pickupStart, String returnStart, String rescheduledAt, String confirmDeadline) {}
}
