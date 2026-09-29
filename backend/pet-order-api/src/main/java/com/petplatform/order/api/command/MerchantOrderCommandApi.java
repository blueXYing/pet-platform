package com.petplatform.order.api.command;
import com.petplatform.common.CommandContext;
public interface MerchantOrderCommandApi {
    Receipt decide(Command command);
    record Command(CommandContext context, String orderId, int expectedConfirmRound,
            String action, String reasonCode, String reasonText, String internalNote) {}
    record Receipt(String orderId, String decisionId, int confirmRound, String action,
            String orderStageAtCommit, String decidedAt, String refundOrderId) {}
}
