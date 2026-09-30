package com.petplatform.aftersale.api.query;

import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.NormalPaymentOrigin;
import com.petplatform.payment.api.query.RefundFundingEligibilityFactsApi.FundingEvidence;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.sql.DataSource;

/** Immutable owner evidence. Pending facts require a live command and transaction-bound ORDER token. */
public interface AfterSaleRefundFactsApi {
    DecisionFact requirePendingDecision(String caseId, String decisionId, String orderToken,
            String storeId, QueryContext context, DataSource transactionSource);
    CreatedFact requireCreated(String caseId, String decisionId, String refundOrderId,
            String storeId, QueryContext context, DataSource transactionSource);
    record DecisionFact(String caseId, String orderId, String userId, String merchantId, String storeId,
            String reservationId, String sourceStage, String caseVersionBefore, String decisionId,
            String decisionType, String refundType, BigDecimal refundAmount, String operatorId,
            String commandId, String decidedEventId, OffsetDateTime decidedAt,
            NormalPaymentOrigin payment, FundingEvidence funding) {}
    record CreatedFact(DecisionFact decision, String refundOrderId, String refundNo,
            String createdEventId, OffsetDateTime createdAt) {}
}
