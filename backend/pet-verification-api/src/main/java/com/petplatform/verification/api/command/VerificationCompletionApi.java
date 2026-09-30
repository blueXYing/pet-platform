package com.petplatform.verification.api.command;
import com.petplatform.common.CommandContext;
/** Contract 48. Internal OWNER-only scan completion; no public HTTP route. */
public interface VerificationCompletionApi {
 Receipt verify(Command command);
 record Command(CommandContext context,String orderId,String storeId,String verificationCode,String expectedCredentialVersion,boolean confirmed) {
  @Override public String toString(){return "VerificationCompletionCommand[redacted]";}
 }
 record Receipt(String orderId,String attemptId,String resultCode,String verificationId,String verifiedAt,String orderVersion) {}
}
