package com.petplatform.aftersale.api.command;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** Minimal current unfulfilled case invalidation. No creation or refund decision authority. */
public interface AfterSaleVerificationApi {
 Evidence invalidateCurrent(String token,String orderId,String storeId,String verificationId,OffsetDateTime at,DataSource transactionSource);
 Evidence requireCommitted(String orderId,String storeId,String verificationId,OffsetDateTime at,DataSource transactionSource);
 record Evidence(String aftersaleId,String status,boolean invalidated) {}
}
