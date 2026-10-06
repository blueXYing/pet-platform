package com.petplatform.boot.config;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi;
import com.petplatform.order.biz.apiimpl.OrderVerificationCredentialFactsApiImpl;
import com.petplatform.order.biz.application.MerchantOrderPorts;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.verification.biz.application.*;
import javax.sql.DataSource;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration(proxyBeanMethods=false)
public class VerificationCredentialConfiguration {
 /** Contract 48 K1 v0.2 / binding-CCR D2: the single action a V1 staff member may hold. */
 public static final String VERIFY_ACTION="merchant.order.verify";
 @Bean Object credentialFlags(Environment e){
  if(e.getProperty("pet.verification.completion.enabled",Boolean.class,false)&&!e.getProperty("pet.verification.credential.enabled",Boolean.class,false))throw new IllegalStateException("Verification completion requires credentials");
  if(e.getProperty("pet.verification.credential.http.enabled",Boolean.class,false)&&!e.getProperty("pet.verification.credential.enabled",Boolean.class,false))throw new IllegalStateException("Credential HTTP requires the credential kernel");
  if(e.getProperty("pet.verification.completion.http.enabled",Boolean.class,false)&&!e.getProperty("pet.verification.completion.enabled",Boolean.class,false))throw new IllegalStateException("Completion HTTP requires the completion kernel");
  if(e.getProperty("pet.verification.credential.enabled",Boolean.class,false))for(String key:List.of("pet.schedule.protection.enabled","pet.payment.foundation.enabled","pet.order.auto-confirm.enabled","pet.order.merchant.enabled"))
   if(!e.getProperty(key,Boolean.class,false))throw new IllegalStateException("Credentials require "+key);return new Object();
 }
 @Bean Object staffIdentityFlags(Environment e){
  if(e.getProperty("pet.verification.staff-identity.enabled",Boolean.class,false)&&!e.getProperty("pet.merchant.staff-identity.enabled",Boolean.class,false))throw new IllegalStateException("Verification staff identity requires merchant staff identity");
  return new Object();
 }
 @Configuration(proxyBeanMethods=false)
 @ConditionalOnProperty(name="pet.verification.credential.enabled",havingValue="true")
 static class Runtime {
  @Bean @ConditionalOnMissingBean(CredentialProtection.class)
  CredentialProtection credentialProtection(@Value("${pet.verification.credential.key-id}")String id,
   @Value("${pet.verification.credential.encryption-key}")String encryption,@Value("${pet.verification.credential.lookup-key}")String lookup){
   try{return new CredentialProtection(id,Map.of(id,Base64.getDecoder().decode(encryption)),Map.of(id,Base64.getDecoder().decode(lookup)));}
   catch(RuntimeException e){throw new IllegalStateException("Credential key configuration unavailable");}
  }
  @Bean OrderVerificationCredentialFactsApi credentialOrderFacts(DataSource s,ScheduleCapacityGuardApi g,PaymentSuccessFactsApi p,RefundOrderFactsApi r,
   ReservationConfirmApi c,ScheduleProtectionFactsApi facts,MerchantOrderAuthorityApi merchant){return new OrderVerificationCredentialFactsApiImpl(s,g,p,r,c,facts,merchant);}
  @Bean CredentialPorts.Sessions credentialSessions(MerchantOrderPorts.SessionAuthority current){return current::requireCurrent;}
  @Bean
  @ConditionalOnMissingBean(CredentialPorts.AttemptAuthority.class)
  @ConditionalOnProperty(name="pet.verification.staff-identity.enabled",havingValue="false",matchIfMissing=true)
  CredentialPorts.AttemptAuthority ownerVerificationAuthority(CredentialPorts.Sessions sessions,MerchantOrderAuthorityApi merchant){
   return (context,merchantId,storeId)->{
    if(context==null||context.operatorType()!=com.petplatform.common.OperatorType.USER||context.operatorId()==null)
     throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.FORBIDDEN,"Owner session required");
    sessions.requireCurrent(context.operatorId());
    merchant.requireOwner(merchantId,storeId,new com.petplatform.common.QueryContext(context.traceId(),context.operatorType(),context.operatorId()));
    return new com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity("USER",context.operatorId(),"OWNER",null);
   };
  }
  /**
   * K1 v0.2 staff-aware authority, default off. OWNER is tried first (unchanged semantics);
   * a non-owner session must pass the contract-52 member action gate (merchant.order.verify
   * only, FROZEN/OFFLINE fail closed per D5) and resolve a traceable grant staffId, else the
   * command is refused. Everything re-reads inside the caller's guarded transaction.
   */
  @Bean
  @ConditionalOnMissingBean(CredentialPorts.AttemptAuthority.class)
  @ConditionalOnProperty(name="pet.verification.staff-identity.enabled",havingValue="true")
  public CredentialPorts.AttemptAuthority staffAwareVerificationAuthority(CredentialPorts.Sessions sessions,MerchantOrderAuthorityApi merchant,
   com.petplatform.merchant.api.query.MerchantStaffIdentityQueryApi staffIdentity){
   return (context,merchantId,storeId)->{
    if(context==null||context.operatorType()!=com.petplatform.common.OperatorType.USER||context.operatorId()==null)
     throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.FORBIDDEN,"Operator session required");
    sessions.requireCurrent(context.operatorId());
    var query=new com.petplatform.common.QueryContext(context.traceId(),context.operatorType(),context.operatorId());
    try{
     merchant.requireOwner(merchantId,storeId,query);
     return new com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity("USER",context.operatorId(),"OWNER",null);
    }catch(com.petplatform.common.ApiException denied){
     if(!com.petplatform.common.CommonApiCodes.FORBIDDEN.equals(denied.code()))throw denied;
     staffIdentity.requireStaffAction(new com.petplatform.merchant.api.query.MerchantStaffActionQuery(merchantId,storeId,VERIFY_ACTION,query));
     var facts=staffIdentity.getStaffFacts(new com.petplatform.merchant.api.query.MerchantStaffIdentityFactsQuery(merchantId,storeId,query));
     if(!"STAFF".equals(facts.membershipKind())||facts.staffId()==null)
      throw new com.petplatform.common.ApiException(com.petplatform.common.CommonApiCodes.FORBIDDEN,"Staff verification requires a traceable staff identity");
     return new com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity("MERCHANT_STAFF",facts.staffId(),"STAFF",facts.staffId());
    }
   };
  }
  @Bean VerificationCredentialService verificationCredentialService(DataSource s,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi g,OrderVerificationCredentialFactsApi facts,
   CredentialProtection protection,CredentialPorts.Sessions sessions,CredentialPorts.AttemptAuthority trustedAttemptAuthority,IntegrationEventPublisher outbox){
   return new VerificationCredentialService(s,ids,g,facts,protection,sessions,trustedAttemptAuthority,outbox);
  }
 }
 @Configuration(proxyBeanMethods=false)
 @ConditionalOnProperty(name="pet.verification.completion.enabled",havingValue="true")
 static class Completion {
  @Bean com.petplatform.order.api.command.OrderVerificationCommitApi verificationOrderCommit(DataSource s,ScheduleCapacityGuardApi g,OrderVerificationCredentialFactsApi f,SnowflakeIdGenerator ids,IntegrationEventPublisher outbox,
   org.springframework.beans.factory.ObjectProvider<VerificationCompletionService> verification,org.springframework.beans.factory.ObjectProvider<com.petplatform.aftersale.api.command.AfterSaleVerificationApi> aftersales){
   return new com.petplatform.order.biz.apiimpl.OrderVerificationCommitApiImpl(s,g,f,ids,outbox,verification::getObject,aftersales::getObject);
  }
  @Bean com.petplatform.aftersale.api.command.AfterSaleVerificationApi verificationAftersale(DataSource s,ScheduleCapacityGuardApi g,com.petplatform.order.api.command.OrderVerificationCommitApi orders,SnowflakeIdGenerator ids,IntegrationEventPublisher outbox,
   ObjectProvider<com.petplatform.aftersale.api.query.AfterSaleCaseFactsApi> workflow){
   return new com.petplatform.aftersale.biz.apiimpl.AfterSaleVerificationApiImpl(s,g,orders,ids,outbox,workflow::getIfAvailable);
  }
  @Bean VerificationCompletionService verificationCompletionService(VerificationCredentialService credentials,com.petplatform.order.api.command.OrderVerificationCommitApi orders,com.petplatform.aftersale.api.command.AfterSaleVerificationApi aftersales){return new VerificationCompletionService(credentials,orders,aftersales);}
 }

}
