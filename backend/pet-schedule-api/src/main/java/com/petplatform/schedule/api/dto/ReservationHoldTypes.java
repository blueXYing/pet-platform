package com.petplatform.schedule.api.dto;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
import java.util.List;
public final class ReservationHoldTypes {
    private ReservationHoldTypes() {}
    public record HoldCommand(CommandContext context, String orderId, String userId,
            String merchantId, String storeId, String serviceId, String fulfillmentType,
            OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd,
            OffsetDateTime pickupStart, OffsetDateTime returnStart,
            String selectedGeneralWindowId, String selectedPickupWindowId, String selectedReturnWindowId) {}
    public record HeldClaim(String claimId, String windowId, String kind,
            OffsetDateTime startAt, OffsetDateTime endAt) {}
    public record HoldResult(String reservationId, String orderId, OffsetDateTime startAt,
            OffsetDateTime endAt, OffsetDateTime holdExpireAt, List<HeldClaim> claims) {
        public HoldResult { claims=List.copyOf(claims); }
    }
}
