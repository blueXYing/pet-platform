package com.petplatform.schedule.api.command;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
/** R1: preserves reservation/claim identity, joins the caller's guarded transaction. */
public interface ReservationSwapApi {
    Result swap(Command command);
    record Command(CommandContext context, String commandId, String orderId, String reservationId,
        String userId, String merchantId, String storeId, String serviceId, String fulfillmentType,
        OffsetDateTime oldStart, OffsetDateTime oldEnd, OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd,
        OffsetDateTime pickupStart, OffsetDateTime returnStart, String selectedGeneralWindowId,
        String selectedPickupWindowId, String selectedReturnWindowId, OffsetDateTime rescheduledAt) {}
    record Result(String changeId, String reservationId, long oldVersion, long newVersion,
        OffsetDateTime oldPickup, OffsetDateTime oldReturn, OffsetDateTime start, OffsetDateTime end,
        OffsetDateTime pickup, OffsetDateTime returning) {}
}
