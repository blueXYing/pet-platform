package com.petplatform.schedule.api.dto;
import com.petplatform.common.QueryContext;
import java.time.OffsetDateTime;
import java.util.List;
public final class ScheduleProtectionTypes {
    private ScheduleProtectionTypes() {}
    public record WindowFact(String windowId, String merchantId, String storeId, String serviceId,
            String kind, OffsetDateTime startAt, OffsetDateTime endAt, int configuredCapacity,
            String status, String version) {}
    public record ReservationFact(String reservationId, String orderId, String userId,
            String merchantId, String storeId, String serviceId, String fulfillmentType,
            OffsetDateTime startAt, OffsetDateTime endAt, OffsetDateTime pickupStartAt,
            OffsetDateTime returnStartAt, String status, String version) {}
    public record ClaimFact(String claimId, String reservationId, String windowId,
            String storeId, String serviceId, String kind, OffsetDateTime startAt, OffsetDateTime endAt) {}
    public record StoreScheduleFacts(String storeId, boolean complete, List<WindowFact> windows,
            List<ReservationFact> reservations, List<ClaimFact> claims) {
        public StoreScheduleFacts { windows=List.copyOf(windows); reservations=List.copyOf(reservations); claims=List.copyOf(claims); }
    }
    public record CapacityProofQuery(String storeId, String serviceId, String fulfillmentType,
            OffsetDateTime appointmentStart, OffsetDateTime appointmentEnd,
            String selectedGeneralWindowId, String selectedPickupWindowId, String selectedReturnWindowId,
            QueryContext context) {}
    public record CapacityProofResult(String storeId, boolean feasible, int evaluatedReservations) {}
}
