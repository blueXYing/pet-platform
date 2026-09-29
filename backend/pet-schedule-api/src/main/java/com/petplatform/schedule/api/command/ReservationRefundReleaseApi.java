package com.petplatform.schedule.api.command;
import com.petplatform.common.QueryContext;
public interface ReservationRefundReleaseApi {
    void release(String orderId, String reservationId, String storeId, String refundId, QueryContext context);
}
