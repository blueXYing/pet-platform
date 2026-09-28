package com.petplatform.schedule.api.command;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
/** Joins ORDER's guarded transaction; a standalone expiration cannot commit. */
public interface ReservationExpiryApi {
    void expire(ExpireHoldCommand command);
    void assertExpired(String orderId, String reservationId, String storeId, QueryContext context);
}
