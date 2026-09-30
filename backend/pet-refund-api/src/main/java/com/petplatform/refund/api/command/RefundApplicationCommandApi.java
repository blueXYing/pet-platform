package com.petplatform.refund.api.command;
import com.petplatform.common.CommandContext;

/** Internal ordinary full-refund commands. Context is supplied by a trusted session adapter. */
public interface RefundApplicationCommandApi {
    Receipt apply(Apply command);
    Receipt decide(Decide command);
    record Apply(CommandContext context, String orderId, String reasonCode, String reasonText) {}
    record Decide(CommandContext context, String applicationId, String expectedApplicationVersion,
            String action, String reasonText) {}
    record Receipt(String orderId, String applicationId, String applicationStatus,
            String applicationVersion, String merchantDeadline, String decidedAt, String decisionId) {}
}
