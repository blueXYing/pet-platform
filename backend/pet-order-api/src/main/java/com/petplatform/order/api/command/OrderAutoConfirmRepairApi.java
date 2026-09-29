package com.petplatform.order.api.command;

import com.petplatform.common.CommandContext;

/** Internal SYSTEM repair; preserves existing task state, lease, attempts and original deadline. */
public interface OrderAutoConfirmRepairApi {
    Result repairMissingTask(CommandContext context, String orderId);
    enum Result { CREATED, EXISTS, RECOVERED, NOT_DUE, STALE, BLOCKED_BY_REFUND }
}
