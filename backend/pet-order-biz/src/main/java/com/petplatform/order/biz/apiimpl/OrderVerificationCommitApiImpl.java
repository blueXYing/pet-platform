package com.petplatform.order.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.command.OrderVerificationCommitApi;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderVerificationStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderVerificationMapper;
import com.petplatform.aftersale.api.command.AfterSaleVerificationApi;
import com.petplatform.verification.api.command.VerificationCommitProofApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;
/** ORDER owns its transition and v2 event; tokens are bound to a live transaction resource. */
public final class OrderVerificationCommitApiImpl implements OrderVerificationCommitApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private final DataSource source;private final ScheduleCapacityGuardApi guard;private final OrderVerificationCredentialFactsApi facts;
 private final SnowflakeIdGenerator ids;private final IntegrationEventPublisher outbox;private final OrderVerificationMapper db;
 private final Supplier<VerificationCommitProofApi> verification;private final Supplier<AfterSaleVerificationApi> aftersales;
 private final Map<String,Entry> tokens=new ConcurrentHashMap<>();
 private static final class Entry {final Permit permit;final Object transaction;String status="ACQUIRED";Entry(Permit p,Object t){permit=p;transaction=t;}}
 public OrderVerificationCommitApiImpl(DataSource s,ScheduleCapacityGuardApi g,OrderVerificationCredentialFactsApi f,SnowflakeIdGenerator ids,IntegrationEventPublisher outbox,
  Supplier<VerificationCommitProofApi> verification,Supplier<AfterSaleVerificationApi> aftersales){source=s;guard=g;facts=f;this.ids=ids;this.outbox=outbox;this.verification=verification;this.aftersales=aftersales;db=new OrderVerificationStore(s).mapper();}
 public Permit acquire(String order,String store,String command,CommandContext context,DataSource txSource){return safe(()->{
  scope(store,txSource);IDS.fromApi(order);IDS.fromApi(command);if(context==null||context.operatorType()!=OperatorType.USER)throw bad();IDS.fromApi(context.operatorId());PublicContractChecks.requireTerminalRequestId(context.requestId());
  var f=facts.requireEligible(order,store,new QueryContext(context.traceId(),OperatorType.SYSTEM,null));var row=db.row(IDS.fromApi(order));
  if(row==null||row.verifiedAt!=null||row.completedAt!=null||db.committed(row.id)!=null||db.active(row.id)!=0||!f.orderVersion().equals(str(row.version)))throw bad();
  var p=new Permit(UUID.randomUUID().toString(),f,command,context,nullable(row.currentAftersaleId),row.aftersaleStatus);var e=new Entry(p,TransactionSynchronizationManager.getResource(source));tokens.put(p.token(),e);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){scope(store,source);if(readOnly||"ACQUIRED".equals(e.status))throw bad();}public void afterCompletion(int status){tokens.remove(p.token(),e);}});
  one(db.acquire(values("id",next(),"order",row.id,"token",p.token())));return p;
 });}
 public Permit requirePending(String token,String order,String store,DataSource txSource){return safe(()->{
  scope(store,txSource);var e=tokens.get(token);if(e==null||e.transaction!=TransactionSynchronizationManager.getResource(source)||!"ACQUIRED".equals(e.status)
   ||!order.equals(e.permit.fact().location().orderId())||!store.equals(e.permit.fact().location().storeId()))throw bad();return e.permit;
 });}
 public void release(String token,String order,String store,DataSource txSource){safe(()->{requirePending(token,order,store,txSource);one(db.guardStatus(values("token",token,"order",IDS.fromApi(order),"status","RELEASED")));tokens.get(token).status="RELEASED";return null;});}
 public String markVerified(String token,String order,String store,String verificationId,String credential,String attempt,OffsetDateTime at,OperatorIdentity identity,DataSource txSource){return safe(()->{
  var p=requirePending(token,order,store,txSource);IDS.fromApi(verificationId);IDS.fromApi(credential);IDS.fromApi(attempt);PublicContractChecks.requireMillisecondPrecision(at);
  var resolved=checked(identity);
  var current=facts.requireEligible(order,store,new QueryContext(p.context().traceId(),OperatorType.SYSTEM,null));if(!current.equals(p.fact()))throw bad();
  var afs=aftersales.get().requireCommitted(order,store,verificationId,at,source);if(afs==null||!Objects.equals(p.currentAftersaleId(),afs.aftersaleId()))throw bad();
  long version=Math.addExact(Long.parseLong(p.fact().orderVersion()),1);long event=next();
  var proof=new VerificationCommitProofApi.Proof(order,store,p.fact().location().merchantId(),p.commandId(),verificationId,credential,attempt,resolved.operatorType(),resolved.operatorId(),resolved.membershipKind(),resolved.operatorStaffId(),p.context().requestId(),at,Long.toString(version));
  var v=values("order",IDS.fromApi(order),"store",IDS.fromApi(store),"verification",IDS.fromApi(verificationId),"credential",IDS.fromApi(credential),"attempt",IDS.fromApi(attempt),"command",IDS.fromApi(p.commandId()),"actor",IDS.fromApi(p.context().operatorId()),"requestId",p.context().requestId(),"oldVersion",Long.parseLong(p.fact().orderVersion()),"version",version,"event",event,"log",next(),"at",at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),"aftersale",afs.aftersaleId()==null?null:IDS.fromApi(afs.aftersaleId()),"aftersaleStatus",afs.status());
  one(db.complete(v));one(db.record(v));one(db.log(v));one(db.guardStatus(values("token",token,"order",IDS.fromApi(order),"status","COMMITTED")));tokens.get(token).status="COMMITTED";
  outbox.publish(new IntegrationEvent<>(Long.toString(event),"OrderVerifiedEvent.v2",2,at,"ORDER",order,p.context().traceId(),values("orderId",order,"verificationId",verificationId,"merchantId",p.fact().location().merchantId(),"storeId",store,"operatorType",resolved.operatorType(),"operatorId",resolved.operatorId(),"membershipKind",resolved.membershipKind(),"operatorStaffId",resolved.operatorStaffId(),"verifiedAt",at.withOffsetSameInstant(ZoneOffset.UTC).toString())));
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){
   var committedRow=db.row(IDS.fromApi(order));if(committedRow==null||!Objects.equals(committedRow.version,version))throw bad();
   requireCommitted(order,store,verificationId,at,source);verification.get().requireCommitted(proof,source);
   if(!afs.equals(aftersales.get().requireCommitted(order,store,verificationId,at,source)))throw bad();
  }});return Long.toString(version);
 });}
 /** Only the two K1 shapes may be mirrored; anything else fails closed before any write. */
 private static OperatorIdentity checked(OperatorIdentity identity){
  if(identity==null||identity.operatorType()==null||identity.operatorId()==null||identity.membershipKind()==null)throw bad();IDS.fromApi(identity.operatorId());
  if("OWNER".equals(identity.membershipKind())){if("USER".equals(identity.operatorType())&&identity.operatorStaffId()==null)return identity;throw bad();}
  if("STAFF".equals(identity.membershipKind())){if("MERCHANT_STAFF".equals(identity.operatorType())&&identity.operatorStaffId()!=null&&identity.operatorStaffId().equals(identity.operatorId()))return identity;throw bad();}
  throw bad();
 }
 public void requireCommitted(String order,String store,String verificationId,OffsetDateTime at,DataSource txSource){safe(()->{
  scope(store,txSource);var r=db.row(IDS.fromApi(order));var p=db.committed(IDS.fromApi(order));
  if(r==null||p==null||!store.equals(str(r.storeId))||!store.equals(str(p.storeId))||!verificationId.equals(str(p.verificationId))||!"COMPLETED".equals(r.orderStage)||!"VERIFIED".equals(r.verificationStatus)
   ||r.version==null||p.orderVersion==null||r.version<p.orderVersion||!Objects.equals(r.verifiedAt,p.verifiedAt)||!Objects.equals(r.completedAt,p.verifiedAt)||!at.isEqual(p.verifiedAt.atOffset(ZoneOffset.UTC))
   )throw bad();
  var historical=aftersales.get().requireCommitted(order,store,verificationId,at,source);
  if(historical==null||!Objects.equals(p.aftersaleId,historical.aftersaleId()==null?null:IDS.fromApi(historical.aftersaleId()))||!Objects.equals(p.aftersaleStatus,historical.status()))throw bad();return null;
 });}
 private void scope(String store,DataSource txSource){if(source!=txSource||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);}
 private <T>T safe(Supplier<T> work){try{return work.get();}catch(RuntimeException failure){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(failure instanceof ApiException a)throw a;throw bad();}}
 private long next(){long id=ids.nextId();if(id<=0)throw bad();return id;}
 private static void one(int n){if(n!=1)throw bad();}private static String str(Long id){if(id==null)throw bad();return id.toString();}private static String nullable(Long id){return id==null?null:id.toString();}
 private static Map<String,Object> values(Object...v){var m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
 private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Verification commit unavailable");}
}
