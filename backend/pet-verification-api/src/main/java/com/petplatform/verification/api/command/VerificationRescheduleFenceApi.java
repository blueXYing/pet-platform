package com.petplatform.verification.api.command;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** Must invalidate all pre-reschedule codes durably in the caller's same guarded transaction.
 * No production implementation exists yet. Never substitute an empty successful implementation. */
public interface VerificationRescheduleFenceApi {
    Fence invalidate(String orderId, String reservationId, String storeId, String rescheduleId,
        OffsetDateTime rescheduledAt, CommandContext context, DataSource transactionSource);
    record Fence(String fenceId, String orderId, String rescheduleId) {}
}
