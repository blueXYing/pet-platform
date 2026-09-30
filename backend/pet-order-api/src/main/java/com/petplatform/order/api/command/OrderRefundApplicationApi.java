package com.petplatform.order.api.command;

import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import javax.sql.DataSource;

/** ORDER-owned normal-payment facts and transaction-bound ordinary refund transitions. */
public interface OrderRefundApplicationApi {
    Location locate(String orderId, QueryContext context);
    Fact requireEligible(String orderId, String storeId, QueryContext context, DataSource transactionSource);
    void bindApplication(String applicationId, String orderId, String storeId, CommandContext context, DataSource transactionSource);
    void requireApplicationBound(String applicationId, String orderId, String storeId, QueryContext context, DataSource transactionSource);
    void recordDecision(String applicationId, String decisionId, String orderId, String storeId, CommandContext context, DataSource transactionSource);
    void requireDecisionRecorded(String applicationId, String decisionId, String orderId, String storeId, QueryContext context, DataSource transactionSource);
    Permit acquireCreate(String applicationId, String decisionId, String orderId, String storeId, String commandId, CommandContext context, DataSource transactionSource);
    void commitCreated(String token, String orderId, String storeId, String refundOrderId, OffsetDateTime createdAt, DataSource transactionSource);
    void requireCreated(String orderId, String storeId, String refundOrderId, DataSource transactionSource);

    record Location(String orderId, String userId, String merchantId, String storeId, String reservationId) {}
    record Fact(Location location, String orderVersion, OffsetDateTime appointmentStart, String verificationStatus,
            String paymentId, String paymentSuccessEventId, String channelTradeNo, BigDecimal paidAmount,
            OffsetDateTime paidAt, String currentApplicationId, String currentApplicationStatus) {}
    record Permit(String token, Fact fact, String applicationId, String decisionId, String commandId, CommandContext context) {}
}
