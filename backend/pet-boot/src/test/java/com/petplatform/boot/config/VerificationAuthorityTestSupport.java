package com.petplatform.boot.config;
import com.petplatform.user.biz.application.UserAuthService;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.verification.biz.application.CredentialPorts;
/** Exposes actual bean factories solely to the isolated acceptance fixture. */
public final class VerificationAuthorityTestSupport {
 public static CredentialPorts.AttemptAuthority authority(UserAuthService auth,MerchantOrderAuthorityApi merchant){
  var current=new MerchantOrderConfiguration.Runtime().merchantOrderSessions(auth);
  var config=new VerificationCredentialConfiguration.Runtime();return config.ownerVerificationAuthority(config.credentialSessions(current),merchant);
 }
}
