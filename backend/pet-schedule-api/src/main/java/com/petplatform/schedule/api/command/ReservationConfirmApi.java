package com.petplatform.schedule.api.command;

import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;

/** Joins ORDER's paid transition in the same guarded main-database transaction. */
public interface ReservationConfirmApi {
    void confirm(ConfirmReservationCommand command);
    void assertConfirmed(String orderId, String reservationId, String storeId,
            QueryContext context);
}
