package com.petplatform.payment.api.query;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
/** A facade, not a settlement ledger. Production requires approved authority and concurrency guarantees.
 * Remote evidence is acquired outside business locks; these final checks must not perform network I/O. */
public interface RefundFundingEligibilityFactsApi {
 FundingEvidence requireForDecision(FundingCheck check,QueryContext context);
 FundingEvidence requireForFirstSend(FundingCheck check,String committedEvidenceId,QueryContext context);
 record FundingCheck(String orderId,String paymentId,String paymentNo,String paymentSuccessEventId,String channelTradeNo,String userId,String merchantId,String storeId,String caseId,String decisionId,String commandId,String refundType,BigDecimal requestedRefundAmount,BigDecimal originalPaidAmount,String currency,String phase,String refundOrderId,String refundNo,String bindingVersion) {}
 record FundingEvidence(String evidenceId,String authorityId,String authorityContractVersion,String sourceFactId,String sourceFactVersion,String authorityEvidenceRef,String requestBindingSha256,String settlementState,String eligibility,String policyVersion,BigDecimal authorizedRefundAmount,String currency,OffsetDateTime observedAt,OffsetDateTime checkedAt,OffsetDateTime validUntil,String fencingReference) {}
}
