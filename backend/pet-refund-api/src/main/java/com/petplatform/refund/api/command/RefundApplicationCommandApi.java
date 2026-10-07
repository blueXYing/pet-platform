package com.petplatform.refund.api.command;
import com.petplatform.common.CommandContext;

/** Internal ordinary full-refund commands. Context is supplied by a trusted session adapter. */
public interface RefundApplicationCommandApi {
    Receipt apply(Apply command);
    /** HTTP boundary outcome (201 first / 200 replay) for the buyer's own apply; kernel-owned truth. */
    default CreationResult applyWithOutcome(Apply command) { throw new UnsupportedOperationException("creation outcome unavailable"); }
    Receipt decide(Decide command);
    record Apply(CommandContext context, String orderId, String reasonCode, String reasonText) {}
    record CreationResult(Receipt receipt, boolean created) {}
    record Decide(CommandContext context, String applicationId, String expectedApplicationVersion,
            String action, String reasonText) {}
    record Receipt(String orderId, String applicationId, String applicationStatus,
            String applicationVersion, String merchantDeadline, String decidedAt, String decisionId) {}
}
