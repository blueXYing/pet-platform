package com.petplatform.refund.api.query;

import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** REFUND-owned persisted evidence. Every call requires the same writable RC transaction/store guard. */
public interface RefundApplicationApprovalFactsApi {
    ApplicationFact requireApplication(String applicationId, String storeId, QueryContext context);
    ApprovalFact requireApproved(String applicationId, String decisionId, String storeId, QueryContext context);
    DecisionFact requireDecision(String applicationId, String decisionId, String storeId, QueryContext context);
    CreatedFact requireCreated(String applicationId, String decisionId, String refundId, String storeId, QueryContext context);

    record ApplicationFact(String applicationId, String orderId, String storeId, String merchantId,
            String userId, String reservationId, String paymentId, String paymentNo,
            String paymentSuccessEventId, String channelTradeNo, BigDecimal paidAmount, OffsetDateTime paidAt,
            String status, long version, OffsetDateTime createdAt, OffsetDateTime merchantDeadline,
            String commandId, String decisionId, String refundOrderId) {}
    record DecisionFact(ApplicationFact application, String decisionId, String status,
            String operatorType, String operatorId, OffsetDateTime decidedAt, String commandId,
            String eventId) {}
    record ApprovalFact(ApplicationFact application, String decisionId, String sourceType,
            String operatorType, String operatorId, OffsetDateTime decidedAt, String commandId,
            String eventId) {}
    record CreatedFact(ApprovalFact approval, String refundOrderId, String refundNo,
            String createdEventId, OffsetDateTime createdAt) {}
}
