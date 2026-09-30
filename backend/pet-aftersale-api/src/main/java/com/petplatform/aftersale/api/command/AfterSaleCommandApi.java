package com.petplatform.aftersale.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.aftersale.api.query.AfterSaleQueryApi.RouteParty;
import java.math.BigDecimal;
import java.util.List;

/** Trusted internal commands. Every write binds the five-part idempotency key. */
public interface AfterSaleCommandApi {
    Receipt create(Create command);
    default CreationResult createWithOutcome(Create command) { throw new UnsupportedOperationException("creation outcome unavailable"); }
    Receipt accept(Accept command);
    Receipt requestSupplement(RequestSupplement command);
    Receipt submitEvidence(SubmitEvidence command);
    default Receipt submitEvidence(SubmitEvidence command, RouteParty routeParty) { throw new UnsupportedOperationException("explicit party evidence unavailable"); }
    Receipt submitMerchantOpinion(SubmitMerchantOpinion command);
    Receipt withdraw(Withdraw command);
    Receipt closeDuplicate(CloseDuplicate command);
    Receipt decide(Decide command);

    record Create(CommandContext context, String orderId, String typeCode, String demandCode,
            String description, BigDecimal requestedAmount, List<String> evidenceAssetIds,
            String newProblemStatement) {
        public Create { evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds); }
    }
    record Accept(CommandContext context, String afterSaleId, String expectedVersion,
            String newProblemAssessment, String expectedFinalSetVersion) {}
    record RequestSupplement(CommandContext context, String afterSaleId, String expectedVersion,
            String targetParty, String reason, String deadline) {}
    record SubmitEvidence(CommandContext context, String afterSaleId, String expectedVersion,
            String supplementRequestId, String text, List<String> evidenceAssetIds) {
        public SubmitEvidence { evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds); }
    }
    record SubmitMerchantOpinion(CommandContext context, String afterSaleId, String expectedVersion,
            String supplementRequestId, String opinionCode, String explanation, List<String> evidenceAssetIds) {
        public SubmitMerchantOpinion { evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds); }
    }
    record Withdraw(CommandContext context, String afterSaleId, String expectedVersion) {}
    record CloseDuplicate(CommandContext context, String afterSaleId, String expectedVersion,
            String priorFinalCaseId, String reason) {}
    record Decide(CommandContext context, String afterSaleId, String expectedVersion,
            String decisionType, BigDecimal refundAmount, String reason) {}
    record Receipt(String commandId, String orderId, String afterSaleId, String status, String version,
            String occurredAt, String evidenceBatchId, String supplementRequestId,
            String decisionId, String refundOrderId) {}
    record CreationResult(Receipt receipt, boolean created) {}
}
