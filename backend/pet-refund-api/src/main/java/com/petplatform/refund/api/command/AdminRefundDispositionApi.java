package com.petplatform.refund.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization;
import java.time.OffsetDateTime;

/**
 * Contract-63 v0.2 platform refund disposal actions (HTTP10 §5.2 draft; user ruling 2026-10-15,
 * doc 33): channel-query and retry over ONE existing business refund. Neither action creates a
 * second business refund order (SSOT §22: 渠道查询/重试不算第二次业务退款); both re-trigger the
 * existing durable REFUND_CHANNEL_QUERY / REFUND_SUBMIT task family for the refund's source
 * family. The kernel re-proves the ADMIN_WEB session, the disposal action code and the resource
 * scope inside the same transaction that writes the audit row and the task trigger.
 */
public interface AdminRefundDispositionApi {

    /** Re-trigger the existing channel-query task family for this refund (对账查单). */
    DispositionReceipt channelQuery(DispositionCommand command);

    /** Re-trigger the existing submit task family for this refund (渠道动作重试). */
    DispositionReceipt retry(DispositionCommand command);

    record DispositionCommand(
            String refundOrderId,
            CommandContext context,
            AdminAuthorization authorization) {}

    /**
     * Kernel-owned receipt: what was triggered and what the durable task looked like afterwards.
     * taskState is the exact async_task status vocabulary (READY/RUNNING/RETRY_WAIT/SUCCEEDED/
     * DEAD/CANCELED) or NOT_FOUND when the task row does not exist yet.
     */
    record DispositionReceipt(
            String refundOrderId,
            String action,
            String taskKey,
            String taskState,
            String refundStatus,
            OffsetDateTime occurredAt) {}
}
