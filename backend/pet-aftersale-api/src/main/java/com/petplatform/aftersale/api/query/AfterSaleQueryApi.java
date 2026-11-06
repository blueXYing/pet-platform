package com.petplatform.aftersale.api.query;

import com.petplatform.common.CommandContext;
import java.math.BigDecimal;
import java.util.List;

/** Current authorization is mandatory even for a previously known case or receipt. */
public interface AfterSaleQueryApi {
    enum RouteParty { USER, MERCHANT, OPS }
    default Options options(CommandContext context) { throw new UnsupportedOperationException("aftersale catalog unavailable"); }
    record Option(String code, String label) {}
    record Options(List<Option> typeOptions, List<Option> demandOptions) {
        public Options { typeOptions=List.copyOf(typeOptions); demandOptions=List.copyOf(demandOptions); }
    }
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
    /**
     * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06): merchant-read display labels — the customer
     * nickname (user domain masked basics, nickname only) and the order-time service/pet snapshot
     * (ORDER domain, breed level). Every value is nullable and never fabricated. The record itself
     * is null unless the verified reader is the current store OWNER (MERCHANT party); it never
     * carries a phone, user id, avatar, address or health field.
     */
    record CaseLabels(String customerNickname, String serviceName, String petName, String petType,
            String petBreed) {}
    record CaseSummary(String afterSaleId, String orderId, String merchantId, String storeId,
            String status, String version, String sourceStage, String typeCode, String demandCode,
            BigDecimal requestedAmount, String createdAt, String deadline, CaseLabels labels) {
        /** Pre-CCR-M-AFTERSALE-VIEW-001 shape: no merchant display labels. */
        public CaseSummary(String afterSaleId, String orderId, String merchantId, String storeId,
                String status, String version, String sourceStage, String typeCode, String demandCode,
                BigDecimal requestedAmount, String createdAt, String deadline) {
            this(afterSaleId, orderId, merchantId, storeId, status, version, sourceStage, typeCode,
                    demandCode, requestedAmount, createdAt, deadline, null);
        }
    }
    record Eligibility(boolean eligible, String sourceStage, String deadline, String blockingReason,
            String activeAfterSaleId) {}
    record CaseView(String afterSaleId, String orderId, String status, String version, String sourceStage,
            String typeCode, String demandCode, String description, BigDecimal requestedAmount,
            String createdAt, String deadline, String supplementRequestId, String supplementTarget,
            String supplementDeadline, String supplementReason, String finalSetVersion, List<String> priorFinalCaseIds,
            String newProblemStatement, String decisionType, BigDecimal refundAmount, String decisionReason,
            List<EvidenceBatch> evidence, CaseLabels labels) {
        public CaseView { priorFinalCaseIds=List.copyOf(priorFinalCaseIds); evidence=List.copyOf(evidence); }
        /** Pre-CCR-M-AFTERSALE-VIEW-001 shape: no merchant display labels. */
        public CaseView(String afterSaleId, String orderId, String status, String version, String sourceStage,
                String typeCode, String demandCode, String description, BigDecimal requestedAmount,
                String createdAt, String deadline, String supplementRequestId, String supplementTarget,
                String supplementDeadline, String supplementReason, String finalSetVersion, List<String> priorFinalCaseIds,
                String newProblemStatement, String decisionType, BigDecimal refundAmount, String decisionReason,
                List<EvidenceBatch> evidence) {
            this(afterSaleId, orderId, status, version, sourceStage, typeCode, demandCode, description,
                    requestedAmount, createdAt, deadline, supplementRequestId, supplementTarget,
                    supplementDeadline, supplementReason, finalSetVersion, priorFinalCaseIds,
                    newProblemStatement, decisionType, refundAmount, decisionReason, evidence, null);
        }
    }
    record EvidenceBatch(String batchId, String submitterType, String text, String opinionCode,
            String submittedAt, List<String> assetIds) {
        public EvidenceBatch { assetIds=List.copyOf(assetIds); }
    }
}
