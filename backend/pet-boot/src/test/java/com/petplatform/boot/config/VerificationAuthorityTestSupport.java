package com.petplatform.boot.config;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.api.query.MerchantStaffIdentityQueryApi;
import com.petplatform.verification.biz.application.CredentialPorts;
import com.petplatform.common.CommandContext;
import com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity;
/** Exposes actual bean factories solely to the isolated acceptance fixture. */
public final class VerificationAuthorityTestSupport {
 public static CredentialPorts.AttemptAuthority authority(UserAuthService auth,MerchantOrderAuthorityApi merchant){
  var current=new MerchantOrderConfiguration.Runtime().merchantOrderSessions(auth);
  var config=new VerificationCredentialConfiguration.Runtime();return config.ownerVerificationAuthority(config.credentialSessions(current),merchant);
 }
 /** Contract 48 K1 v0.2 staff-aware authority, exactly as the production bean composes it. */
 public static CredentialPorts.AttemptAuthority staffAwareAuthority(CredentialPorts.Sessions sessions,MerchantOrderAuthorityApi merchant,MerchantStaffIdentityQueryApi staffIdentity){
  return new VerificationCredentialConfiguration.Runtime().staffAwareVerificationAuthority(sessions,merchant,staffIdentity);
 }
}
