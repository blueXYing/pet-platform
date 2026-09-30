package com.petplatform.aftersale.biz.apiimpl;
import com.petplatform.common.*;
import com.petplatform.aftersale.api.command.AfterSaleVerificationApi;
import com.petplatform.aftersale.biz.infrastructure.persistence.AfterSaleVerificationStore;
import com.petplatform.aftersale.biz.infrastructure.persistence.mapper.AfterSaleVerificationMapper;
import com.petplatform.order.api.command.OrderVerificationCommitApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;
/** Only this owner reads/mutates AFTERSALE rows. A live ORDER capability supplies the source. */
public final class AfterSaleVerificationApiImpl implements AfterSaleVerificationApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private static final Set<String> LIVE=Set.of("PENDING","PROCESSING","WAITING_SUPPLEMENT"),ENDED=Set.of("RESOLVED","INVALIDATED","WITHDRAWN","CLOSED");
 private final DataSource source;private final ScheduleCapacityGuardApi guard;private final OrderVerificationCommitApi orders;private final SnowflakeIdGenerator ids;private final AfterSaleVerificationMapper db;
 public AfterSaleVerificationApiImpl(DataSource s,ScheduleCapacityGuardApi g,OrderVerificationCommitApi orders,SnowflakeIdGenerator ids){source=s;guard=g;this.orders=orders;this.ids=ids;db=AfterSaleVerificationStore.mapper(s);}
 public Evidence invalidateCurrent(String token,String order,String store,String verification,OffsetDateTime at,DataSource txSource){return safe(()->{
  scope(store,txSource);IDS.fromApi(verification);PublicContractChecks.requireMillisecondPrecision(at);var p=orders.requirePending(token,order,store,source);var loc=p.fact().location();
  if(db.proof(IDS.fromApi(order))!=null)throw bad();var active=db.active(IDS.fromApi(order));var c=p.currentAftersaleId()==null?null:db.current(IDS.fromApi(p.currentAftersaleId()));
  if(c==null&&(p.currentAftersaleId()!=null||active!=null))throw bad();boolean invalidate=false;String status="NONE";Long version=null;
  if(c!=null){
   if(!order.equals(str(c.orderId))||!store.equals(str(c.storeId))||!loc.userId().equals(str(c.userId))||!loc.merchantId().equals(str(c.merchantId))
    ||!"UNVERIFIED_POST_START".equals(c.sourceStage)||c.version==null||c.version<0||c.activeFlag==null)throw bad();
   if(c.activeFlag==1){if(active==null||!Objects.equals(active.id,c.id)||!LIVE.contains(c.status)||c.invalidatedAt!=null)throw bad();invalidate=true;status="INVALIDATED";version=Math.addExact(c.version,1);}
   else if(c.activeFlag==0&&ENDED.contains(c.status)&&active==null){status=c.status;version=c.version;}else throw bad();
   if(p.aftersaleStatus()!=null&&!p.aftersaleStatus().equals(c.status))throw bad();
  }else if(p.aftersaleStatus()!=null&&!"NONE".equals(p.aftersaleStatus()))throw bad();
  var v=values("verification",IDS.fromApi(verification),"order",IDS.fromApi(order),"store",IDS.fromApi(store),"case",c==null?null:c.id,"version",version,"status",status,"invalidated",invalidate,"at",at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
  if(invalidate){v.put("oldVersion",c.version);v.put("oldStatus",c.status);v.put("actor",IDS.fromApi(p.context().operatorId()));v.put("log",next());one(db.invalidate(v));one(db.log(v));}
  one(db.record(v));var evidence=new Evidence(p.currentAftersaleId(),status,invalidate);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){
   if(readOnly)throw bad();orders.requireCommitted(order,store,verification,at,source);if(!evidence.equals(requireCommitted(order,store,verification,at,source)))throw bad();
  }});return evidence;
 });}
 public Evidence requireCommitted(String order,String store,String verification,OffsetDateTime at,DataSource txSource){return safe(()->{
  scope(store,txSource);var p=db.proof(IDS.fromApi(order));if(p==null||!store.equals(str(p.storeId))||!verification.equals(str(p.verificationId))||p.verifiedAt==null||!at.isEqual(p.verifiedAt.atOffset(ZoneOffset.UTC))||p.invalidated==null||db.active(IDS.fromApi(order))!=null)throw bad();
  if(p.aftersaleId==null){if(!"NONE".equals(p.caseStatus)||p.caseVersion!=null||p.invalidated)throw bad();}
  else {var c=db.current(p.aftersaleId);if(c==null||!Objects.equals(c.orderId,p.orderId)||!Objects.equals(c.storeId,p.storeId)||!Objects.equals(c.version,p.caseVersion)||!Objects.equals(c.status,p.caseStatus)||!Objects.equals(c.activeFlag,0)||!"UNVERIFIED_POST_START".equals(c.sourceStage))throw bad();
   if(p.invalidated&&(!"INVALIDATED".equals(c.status)||!Objects.equals(c.invalidatedAt,p.verifiedAt)))throw bad();}
  return new Evidence(p.aftersaleId==null?null:p.aftersaleId.toString(),p.caseStatus,p.invalidated);
 });}
 private void scope(String store,DataSource txSource){if(source!=txSource||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);}
 private <T>T safe(Supplier<T> work){try{return work.get();}catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(e instanceof ApiException a)throw a;throw bad();}}
 private long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}private static void one(int n){if(n!=1)throw bad();}private static String str(Long n){if(n==null)throw bad();return n.toString();}
 private static Map<String,Object> values(Object...v){var m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
 private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Aftersale verification proof unavailable");}
}
