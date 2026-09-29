package com.petplatform.order.api.command;

import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** Internal SYSTEM command; owns a new guarded transaction. Initial confirmation round only. */
public interface OrderAutoConfirmApi {
    Result autoConfirm(AutoConfirmOrderCommand command);
    record AutoConfirmOrderCommand(CommandContext context, String orderId,
            int expectedConfirmRound, OffsetDateTime expectedConfirmDeadline) {}
    enum Result { CONFIRMED, ALREADY_CONFIRMED, STALE, NOT_DUE, BLOCKED_BY_REFUND }
}
