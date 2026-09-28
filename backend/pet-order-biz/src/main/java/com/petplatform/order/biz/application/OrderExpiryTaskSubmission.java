package com.petplatform.order.biz.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.time.OffsetDateTime;
import java.util.Map;

/** One stable payload for both creation and bounded missing-task recovery. */
public final class OrderExpiryTaskSubmission {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OrderExpiryTaskSubmission() {}

    public static void submit(JdbcAsyncTaskSubmitter tasks, long orderId, long reservationId,
            OffsetDateTime deadline) {
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of(
                    "orderId", Long.toString(orderId),
                    "reservationId", Long.toString(reservationId),
                    "expectedReservationVersion", 0,
                    "expectedPaymentExpireAt", deadline.toString()));
        } catch (JsonProcessingException broken) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "ORDER expiry task payload cannot be serialized");
        }
        tasks.enqueueAt("RESERVATION_HOLD_EXPIRE:" + reservationId + ":0", "ORDER",
                "RESERVATION_HOLD_EXPIRE", "RESERVATION", reservationId, 0L,
                payload, 20, "FAST_INTERNAL", deadline);
    }
}
