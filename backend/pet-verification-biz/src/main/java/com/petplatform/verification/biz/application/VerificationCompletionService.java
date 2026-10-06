package com.petplatform.verification.biz.application;
import static com.petplatform.verification.biz.application.VerificationCredentialService.*;
import com.petplatform.common.*;
import com.petplatform.verification.api.command.*;
import com.petplatform.verification.biz.infrastructure.persistence.VerificationCompletionStore;
import com.petplatform.verification.biz.infrastructure.persistence.mapper.VerificationCompletionMapper;
import com.petplatform.order.api.command.OrderVerificationCommitApi;
import com.petplatform.order.api.command.OrderVerificationCommitApi.OperatorIdentity;
import com.petplatform.aftersale.api.command.AfterSaleVerificationApi;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;
/** Internal OWNER command. Every success effect belongs to the same guarded transaction. */
public final class VerificationCompletionService implements VerificationCompletionApi,VerificationCommitProofApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private final VerificationCredentialService kernel;private final OrderVerificationCommitApi orders;private final AfterSaleVerificationApi aftersales;private final VerificationCompletionMapper db;
 public VerificationCompletionService(VerificationCredentialService credentials,OrderVerificationCommitApi orders,AfterSaleVerificationApi aftersales){kernel=Objects.requireNonNull(credentials);this.orders=Objects.requireNonNull(orders);this.aftersales=Objects.requireNonNull(aftersales);db=VerificationCompletionStore.mapper(kernel.source);}
 public Receipt verify(Command c){return safe(()->{
  if(c==null)throw invalid();validate(c.context(),c.orderId(),true);try{IDS.fromApi(c.storeId());}catch(RuntimeException e){throw invalid();}long expected=version(c.expectedCredentialVersion());
  if(!c.confirmed()||c.verificationCode()==null||!c.verificationCode().matches("[0-9A-Z]{1,128}"))throw invalid();top();
  var check=new VerificationCredentialApi.Check(c.context(),c.orderId(),c.storeId(),c.verificationCode());kernel.tx.executeWithoutResult(s->kernel.attemptLocation(check));
  var key=key("verification.complete",c.context(),"STORE:"+c.storeId());String purpose=purpose(key);var input=json(values("orderId",c.orderId(),"storeId",c.storeId(),"code",c.verificationCode(),"version",c.expectedCredentialVersion(),"confirmed",true));kernel.admit(key,purpose,input);
  return kernel.tx.execute(s->{kernel.defaults();var binding=kernel.db.binding(key);kernel.same(binding,purpose,input);var at0=kernel.attemptLocation(check);var loc=at0.loc();var identity=resolved(at0.identity());
   if("SUCCEEDED".equals(binding.state)){
    try{if(!Objects.equals(binding.resultVersion,1)||binding.resultBytes==null)throw bad();var saved=JSON.readValue(kernel.protection.reveal(purpose+":RESULT",binding.resultBytes),Receipt.class);if(!saved.equals(receipt(binding.id,identity,c.context().requestId(),c.orderId(),c.storeId())))throw bad();return saved;}catch(Exception e){throw bad();}
   }
   reserved(binding);var permit=orders.acquire(c.orderId(),c.storeId(),str(binding.id),c.context(),kernel.source);var fact=permit.fact();
   var state=kernel.state(fact.location(),true);if(state.version!=expected)throw error(CommonApiCodes.CONFLICT);var now=kernel.db.now();var current=kernel.current(state,fact);
   var checked=kernel.assess(check,binding,state,fact,now,current);boolean success="VALID".equals(checked.resultCode());
   var fields=values("order",IDS.fromApi(c.orderId()),"store",IDS.fromApi(c.storeId()),"actor",IDS.fromApi(identity.operatorId()),"requestId",c.context().requestId(),"command",binding.id,"attempt",IDS.fromApi(checked.attemptId()),"at",now,"result",success?"SUCCESS":"VERIFICATION_RISK_LOCKED".equals(checked.resultCode())?"LOCKED":"FAILED","reason",success?null:checked.resultCode());
   fields.put("operatorType",identity.operatorType());fields.put("membershipKind",identity.membershipKind());fields.put("staffId",identity.operatorStaffId()==null?null:IDS.fromApi(identity.operatorStaffId()));one(db.attempt(fields));
   Receipt result;
   if(!success){orders.release(permit.token(),c.orderId(),c.storeId(),kernel.source);result=new Receipt(c.orderId(),checked.attemptId(),checked.resultCode(),null,null,null);}
   else {
    String verification=Long.toString(kernel.next());long newVersion=Math.addExact(Long.parseLong(fact.orderVersion()),1);fields.put("verification",IDS.fromApi(verification));fields.put("credential",current.id);fields.put("version",newVersion);one(db.record(fields));
    var at=now.atOffset(ZoneOffset.UTC);aftersales.invalidateCurrent(permit.token(),c.orderId(),c.storeId(),verification,at,kernel.source);
    one(kernel.db.invalidate(values("order",state.orderId,"now",now,"reason","VERIFIED")));
    one(kernel.db.advance(values("order",state.orderId,"oldVersion",state.version,"epoch",state.epoch,"credential",null,"now",now)));
    String committedVersion=orders.markVerified(permit.token(),c.orderId(),c.storeId(),verification,str(current.id),checked.attemptId(),at,identity,kernel.source);if(!Long.toString(newVersion).equals(committedVersion))throw bad();
    result=new Receipt(c.orderId(),checked.attemptId(),"VERIFIED",verification,time(now),committedVersion);
    var proof=new Proof(c.orderId(),c.storeId(),loc.merchantId(),str(binding.id),verification,str(current.id),checked.attemptId(),identity.operatorType(),identity.operatorId(),identity.membershipKind(),identity.operatorStaffId(),c.context().requestId(),at,committedVersion);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){
     if(readOnly)throw bad();if(!resolved(kernel.authority.requireAuthorized(c.context(),loc.merchantId(),c.storeId())).equals(identity))throw bad();requireCommitted(proof,kernel.source);orders.requireCommitted(c.orderId(),c.storeId(),verification,at,kernel.source);
    }});
   }
   kernel.finish(binding,purpose,result);return result;
  });
 });
}
 /** Only the two K1 v0.2 identity shapes are persistable; anything else fails closed. */
 static OperatorIdentity resolved(OperatorIdentity identity){
  if(identity==null||identity.operatorType()==null||identity.operatorId()==null||identity.membershipKind()==null)throw bad();IDS.fromApi(identity.operatorId());
  if("OWNER".equals(identity.membershipKind())&&"USER".equals(identity.operatorType())&&identity.operatorStaffId()==null)return identity;
  if("STAFF".equals(identity.membershipKind())&&"MERCHANT_STAFF".equals(identity.operatorType())&&identity.operatorStaffId()!=null&&identity.operatorStaffId().equals(identity.operatorId()))return identity;
  throw bad();
 }
 private Receipt receipt(long command,OperatorIdentity identity,String request,String order,String store){
  var r=db.result(command);var risk=kernel.db.receiptAttempt(command);
  if(r==null||risk==null||!Objects.equals(risk.id,r.id)||!Objects.equals(risk.orderId,r.orderId)||!Objects.equals(risk.attemptedAt,r.createdAt)||!order.equals(str(r.orderId))||!store.equals(str(r.storeId))
   ||!identity.operatorType().equals(r.operatorType)||!identity.membershipKind().equals(r.membershipKind)||!identity.operatorId().equals(str(r.operatorId))
   ||!Objects.equals(identity.operatorStaffId(),r.operatorStaffId==null?null:str(r.operatorStaffId))||!request.equals(r.requestId))throw bad();
  if("SUCCESS".equals(r.result)){
   if(r.verificationId==null||r.credentialId==null||r.orderVersion==null||r.verifiedAt==null||!Objects.equals(r.verifiedAt,r.createdAt)||!"VALID".equals(risk.resultCode)||r.failReason!=null||!"SCAN".equals(r.verifyMethod))throw bad();
   var credential=kernel.db.code(r.credentialId);if(credential==null||!Objects.equals(credential.orderId,r.orderId)||!Objects.equals(credential.storeId,r.storeId)||!Objects.equals(credential.invalidatedAt,r.verifiedAt)||!"VERIFIED".equals(credential.invalidatedReason))throw bad();
   return new Receipt(order,str(r.id),"VERIFIED",str(r.verificationId),time(r.verifiedAt),str(r.orderVersion));
  }
  if(!Set.of("FAILED","LOCKED").contains(r.result)||r.verificationId!=null||r.failReason==null||!r.failReason.equals(risk.resultCode)||!Set.of("VERIFICATION_CODE_INVALID","VERIFICATION_CODE_EXPIRED","VERIFICATION_RISK_LOCKED").contains(r.failReason))throw bad();
  return new Receipt(order,str(r.id),r.failReason,null,null,null);
 }
 public void requireCommitted(Proof p,DataSource txSource){try{
  if(p==null||txSource!=kernel.source||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(kernel.source) instanceof ConnectionHolder))throw bad();kernel.guard.requireHeld(p.storeId(),kernel.source);
  var actual=receipt(IDS.fromApi(p.commandId()),new OperatorIdentity(p.operatorType(),p.operatorId(),p.membershipKind(),p.operatorStaffId()),p.requestId(),p.orderId(),p.storeId());var expected=new Receipt(p.orderId(),p.attemptId(),"VERIFIED",p.verificationId(),time(p.verifiedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()),p.orderVersion());
  var r=db.result(IDS.fromApi(p.commandId()));var code=kernel.db.code(IDS.fromApi(p.credentialId()));var state=kernel.db.state(IDS.fromApi(p.orderId()));
  if(!actual.equals(expected)||!p.credentialId().equals(str(r.credentialId))||code==null||!p.merchantId().equals(str(code.merchantId))||state==null||state.currentCredentialId!=null||state.version!=code.generation+1||kernel.db.liveCount(state.orderId)!=0||db.succeeded(IDS.fromApi(p.commandId()))!=1)throw bad();
 }catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(kernel.source) instanceof ConnectionHolder h)h.setRollbackOnly();if(e instanceof ApiException a)throw a;throw bad();}}
}
