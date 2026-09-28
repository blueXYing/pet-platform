package com.petplatform.schedule.api.dto;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
public final class ReservationExpiryTypes {
    private ReservationExpiryTypes() {}
    public record ExpireHoldCommand(CommandContext context, String orderId, String reservationId,
            String storeId, long expectedVersion, OffsetDateTime expectedExpireAt,
            OffsetDateTime observedNow) {}
}
