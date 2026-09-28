package com.petplatform.schedule.api.dto;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

public final class ReservationConfirmTypes {
    private ReservationConfirmTypes() {}
    /** The original hold identity and deadline are a generation fence. */
    public record ConfirmReservationCommand(CommandContext context, String orderId,
            String reservationId, String storeId, long expectedVersion,
            OffsetDateTime expectedExpireAt) {}
}
