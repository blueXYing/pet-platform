package com.petplatform.aftersale.api.query;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.util.List;

/** Current authorization is mandatory even for a previously known case or receipt. */
public interface AfterSaleQueryApi {
    enum RouteParty { USER, MERCHANT, OPS }
    CaseView getCase(CommandContext context, String afterSaleId);
    default CaseView getCase(CommandContext context, String afterSaleId, RouteParty routeParty) { throw new UnsupportedOperationException("explicit party query unavailable"); }
    default CasePage listMine(CommandContext context, ListQuery query) { throw new UnsupportedOperationException("buyer list unavailable"); }
    default CasePage listForStore(CommandContext context, RouteParty routeParty, String merchantId,
            String storeId, ListQuery query) { throw new UnsupportedOperationException("store list unavailable"); }
    Eligibility checkEligibility(CommandContext context, String orderId);
    record ListQuery(Integer page, Integer pageSize, String status, String orderId) {
        public ListQuery { page=page==null?1:page; pageSize=pageSize==null?20:pageSize; }
    }
    record CasePage(int page, int pageSize, long total, List<CaseSummary> items) {
        public CasePage { items=List.copyOf(items); }
    }
    record CaseSummary(String afterSaleId, String orderId, String merchantId, String storeId,
            String status, String version, String sourceStage, String typeCode, String demandCode,
            BigDecimal requestedAmount, String createdAt, String deadline) {}
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
