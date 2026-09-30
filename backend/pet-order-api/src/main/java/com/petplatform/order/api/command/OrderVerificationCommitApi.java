package com.petplatform.order.api.command;
import com.petplatform.common.CommandContext;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi.Fact;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** VERIFY-only transaction capability. Never a cross-transaction authorization grant. */
public interface OrderVerificationCommitApi {
 Permit acquire(String orderId,String storeId,String commandId,CommandContext context,DataSource transactionSource);
 Permit requirePending(String token,String orderId,String storeId,DataSource transactionSource);
 void release(String token,String orderId,String storeId,DataSource transactionSource);
 String markVerified(String token,String orderId,String storeId,String verificationId,String credentialId,String attemptId,OffsetDateTime at,DataSource transactionSource);
 void requireCommitted(String orderId,String storeId,String verificationId,OffsetDateTime at,DataSource transactionSource);
 record Permit(String token,Fact fact,String commandId,CommandContext context,String currentAftersaleId,String aftersaleStatus) {}
}
