package com.petplatform.verification.api.command;
import com.petplatform.common.CommandContext;
import com.petplatform.common.QueryContext;
/** Internal only. Validation is not service completion or a permission to bypass VERIFY's final guard. */
public interface VerificationCredentialApi {
    Receipt issue(Issue command);
    View read(String orderId, QueryContext context);
    CheckResult check(Check command);
    record Issue(CommandContext context,String orderId,String expectedCredentialVersion,String refreshKind) {}
    record Receipt(String orderId,String credentialId,String credentialVersion,String code,String issuedAt,String expiresAt,String refreshAfter) {
        @Override public String toString(){return "CredentialReceipt[REDACTED]";}
    }
    record View(String orderId,String credentialVersion,String status,String code,String expiresAt,String refreshAfter,String lockedUntil) {
        @Override public String toString(){return "CredentialView[REDACTED]";}
    }
    record Check(CommandContext context,String orderId,String storeId,String verificationCode) {
        @Override public String toString(){return "CredentialCheck[REDACTED]";}
    }
    record CheckResult(String orderId,String attemptId,String resultCode,String checkedAt) {}
}
