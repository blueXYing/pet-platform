package com.petplatform.aftersale.api.query;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.util.List;

/** Current authorization is mandatory even for a previously known case or receipt. */
public interface AfterSaleQueryApi {
    CaseView getCase(CommandContext context, String afterSaleId);
    Eligibility checkEligibility(CommandContext context, String orderId);
    record Eligibility(boolean eligible, String sourceStage, String deadline, String blockingReason,
            String activeAfterSaleId) {}
    record CaseView(String afterSaleId, String orderId, String status, String version, String sourceStage,
            String typeCode, String demandCode, String description, BigDecimal requestedAmount,
            String createdAt, String deadline, String supplementRequestId, String supplementTarget,
            String supplementDeadline, String supplementReason, String finalSetVersion, List<String> priorFinalCaseIds,
            String newProblemStatement, String decisionType, BigDecimal refundAmount, String decisionReason,
            List<EvidenceBatch> evidence) {
        public CaseView { priorFinalCaseIds=List.copyOf(priorFinalCaseIds); evidence=List.copyOf(evidence); }
    }
    record EvidenceBatch(String batchId, String submitterType, String text, String opinionCode,
            String submittedAt, List<String> assetIds) {
        public EvidenceBatch { assetIds=List.copyOf(assetIds); }
    }
}
