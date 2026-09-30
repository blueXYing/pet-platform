package com.petplatform.order.api.command;
import com.petplatform.common.CommandContext;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi.Fact;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** Same writable RC transaction and held store guard are mandatory. */
public interface OrderAfterSaleCommitApi {
 void bindCreated(String orderId,String storeId,String caseId,String expectedOrderVersion,CommandContext context,DataSource transactionSource);
 void projectTransition(String orderId,String storeId,String caseId,String expectedCaseVersion,CommandContext context,DataSource transactionSource);
 Permit acquireRefund(String orderId,String storeId,String caseId,String expectedCaseVersion,String decisionId,String commandId,CommandContext context,DataSource transactionSource);
 Permit requirePending(String token,String orderId,String storeId,DataSource transactionSource);
 void commitCreated(String token,String caseId,String decisionId,String refundOrderId,OffsetDateTime createdAt,DataSource transactionSource);
 void requireCreated(String orderId,String storeId,String caseId,String decisionId,String refundOrderId,DataSource transactionSource);
 record Permit(String token,Fact fact,String caseId,String caseVersion,String sourceStage,String decisionId,String commandId,CommandContext context) {}
}
