package com.petplatform.aftersale.api.command;

import com.petplatform.common.CommandContext;

/** Only authenticated durable tasks may execute this command. */
public interface AfterSaleSupplementTimeoutApi {
    Result handle(Timeout command);
    record Timeout(CommandContext context, String afterSaleId, String supplementRequestId,
            String storeId, String expectedDeadline) {}
    record Result(boolean completed, String retryAt) {}
}
