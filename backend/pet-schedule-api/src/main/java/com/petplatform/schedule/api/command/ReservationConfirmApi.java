package com.petplatform.schedule.api.command;

import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;

/** Joins ORDER's paid transition in the same guarded main-database transaction. */
public interface ReservationConfirmApi {
    void confirm(ConfirmReservationCommand command);
    default void assertRescheduled(String orderId,String reservationId,String storeId,String changeId,long version,
            java.time.OffsetDateTime start,java.time.OffsetDateTime end,boolean releasedAllowed,QueryContext context) {
        throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Reschedule proof provider required");
    }
    void assertConfirmed(String orderId, String reservationId, String storeId,
            QueryContext context);
}
