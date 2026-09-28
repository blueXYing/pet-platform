package com.petplatform.order.api.command;

import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ReconcileExpiryTasksCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.RecoveryScanResult;

/** Internal SYSTEM command; the handler retries dependency uncertainty. */
public interface OrderExpiryApi {
    ExpireOrderResult expire(ExpireOrderCommand command);
    RecoveryScanResult reconcileMissingTasks(ReconcileExpiryTasksCommand command);
}
