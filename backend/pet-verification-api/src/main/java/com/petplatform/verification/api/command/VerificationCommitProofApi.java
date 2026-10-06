package com.petplatform.verification.api.command;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
/** VER-owned persistent proof; consumers must not read VER tables. */
public interface VerificationCommitProofApi {
 void requireCommitted(Proof proof,DataSource transactionSource);
 record Proof(String orderId,String storeId,String merchantId,String commandId,String verificationId,String credentialId,String attemptId,
              String operatorType,String operatorId,String membershipKind,String operatorStaffId,String requestId,OffsetDateTime verifiedAt,String orderVersion) {}
}
