package com.petplatform.refund.api.command;
import com.petplatform.common.CommandContext;
import java.time.OffsetDateTime;

/** Only registered trusted workers may construct these SYSTEM commands. */
public interface RefundApplicationTimeoutApi {
    TaskResult handle(Timeout command);
    String createApproved(Create command);
    record Timeout(CommandContext context, String applicationId, String storeId,
            OffsetDateTime expectedMerchantDeadline) {}
    record Create(CommandContext context, String applicationId, String decisionId, String storeId) {}
    record TaskResult(boolean done, OffsetDateTime nextAt) {}
}
