package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.aftersale.api.query.*;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.api.query.OrderAfterSaleFactsApi;
import com.petplatform.order.api.query.OrderAfterSaleRefundFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAfterSaleMapper;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundAfterSaleFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;

/** ORDER owns current projection, immutable normal-payment snapshots and transaction capabilities. */
public final class OrderAfterSaleApiImpl implements OrderAfterSaleFactsApi,OrderAfterSaleCommitApi,OrderAfterSaleRefundFactsApi {
 public interface ScopeAuthority {Scope require(String merchantId,String storeId,QueryContext context);}
 public record Scope(String cityCode,String scopeVersion) {}
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 private final DataSource source;private final ScheduleCapacityGuardApi guard;private final SnowflakeIdGenerator ids;
 private final OrderRefundApplicationApiImpl normal;private final PaymentSuccessFactsApi payments;
 private final Supplier<AfterSaleCaseFactsApi> cases;private final Supplier<AfterSaleRefundFactsApi> decisions;
 private final Supplier<RefundAfterSaleFactsApi> refunds;private final ScopeAuthority scopes;
 private final OrderAfterSaleMapper db;private final OrderAutoConfirmStore orders;private final OrderPaymentStore paid;
 private final Map<String,Entry> tokens=new ConcurrentHashMap<>();
 private static final class Entry {final Permit permit;final Object tx;boolean committed;Entry(Permit p,Object tx){permit=p;this.tx=tx;}}
 public OrderAfterSaleApiImpl(DataSource source,ScheduleCapacityGuardApi guard,SnowflakeIdGenerator ids,
   OrderRefundApplicationApiImpl normal,PaymentSuccessFactsApi payments,Supplier<AfterSaleCaseFactsApi> cases,
   Supplier<AfterSaleRefundFactsApi> decisions,Supplier<RefundAfterSaleFactsApi> refunds,ScopeAuthority scopes){
  this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);this.ids=Objects.requireNonNull(ids);this.normal=Objects.requireNonNull(normal);this.payments=Objects.requireNonNull(payments);this.cases=Objects.requireNonNull(cases);this.decisions=Objects.requireNonNull(decisions);this.refunds=Objects.requireNonNull(refunds);this.scopes=Objects.requireNonNull(scopes);
  db=OrderAfterSaleStore.mapper(source);orders=new OrderAutoConfirmStore(source);paid=new OrderPaymentStore(source);
 }
 public Location locate(String order,QueryContext q){var l=normal.locate(order,q);return new Location(l.orderId(),l.userId(),l.merchantId(),l.storeId(),l.reservationId());}
 public OrderAfterSaleFactsApi.Fact requireCurrentEligible(String order,String store,QueryContext q,DataSource txSource){return safe(()->{scope(store,txSource);system(q);current(order,store,q,source);return fact(order,store,q);});}
 private OrderAfterSaleFactsApi.Fact fact(String order,String store,QueryContext q){
  var f=normal.requireNormalForAfterSale(order,store,q,source);var l=f.location();var s=new OrderVerificationStore(source).mapper().row(id(order));
  var p=payments.requireSucceeded(f.paymentId(),order,store,q);var v=new OrderVerificationStore(source).mapper().committed(id(order));
  if(p==null||!p.successEventId().equals(f.paymentSuccessEventId())||p.paidAmount().compareTo(f.paidAmount())!=0)throw bad();
  var sc=scopes.require(l.merchantId(),store,q);if(sc==null||sc.cityCode()==null||sc.cityCode().isBlank()||sc.scopeVersion()==null||sc.scopeVersion().isBlank())throw bad();
  return new OrderAfterSaleFactsApi.Fact(new Location(order,l.userId(),l.merchantId(),store,l.reservationId()),f.orderVersion(),f.appointmentStart(),f.verificationStatus(),v==null?null:str(v.verificationId),v==null?null:offset(v.verifiedAt),
   new NormalPaymentOrigin(p.paymentId(),p.paymentNo(),p.successEventId(),p.channelTradeNo(),p.paidAmount(),p.paidAt(),p.currency()),nullable(s.currentAftersaleId),s.aftersaleStatus,f.currentApplicationId(),f.currentApplicationStatus(),sc.cityCode(),sc.scopeVersion());
 }
 public Current current(String order,String store,QueryContext q,DataSource txSource){return safe(()->{
  scope(store,txSource);system(q);var row=new OrderVerificationStore(source).mapper().row(id(order));if(row==null||!store.equals(str(row.storeId)))throw bad();
  var c=cases.get().requireCurrent(order,store,nullable(row.currentAftersaleId),q,source);
  var identity=orders.lock(id(order));if(identity==null||c!=null&&c.afterSaleId()!=null&&!str(identity.userId).equals(c.userId()))throw bad();
  if(c==null||!order.equals(c.orderId())||!store.equals(c.storeId())||!Objects.equals(nullable(row.currentAftersaleId),c.afterSaleId())
   ||c.afterSaleId()!=null&&(!Objects.equals(row.aftersaleStatus,c.status())||!str(row.merchantId).equals(c.merchantId()))
   ||c.afterSaleId()==null&&row.aftersaleStatus!=null&&!"NONE".equals(row.aftersaleStatus))throw bad();
  return new Current(order,store,c.afterSaleId(),c.status(),str(row.version));
 });}
 public void bindCreated(String order,String store,String caseId,String expected,CommandContext context,DataSource txSource){safe(()->{
  scope(store,txSource);command(context);var q=q(context);var f=fact(order,store,q);var c=cases.get().requireCurrent(order,store,caseId,q,source);
  if(context.operatorType()!=OperatorType.USER||!context.operatorId().equals(f.location().userId())||!expected.equals(f.orderVersion())||c==null||!caseId.equals(c.afterSaleId())||!"PENDING".equals(c.status())||!c.active()||!"0".equals(c.version())||db.source(id(caseId))!=null)throw bad();
  sameIdentity(f,c);String stage="VERIFIED".equals(f.verificationStatus())?"VERIFIED":"UNVERIFIED_POST_START";if(!stage.equals(c.sourceStage()))throw bad();
  if(f.currentCaseId()!=null){var old=db.source(id(f.currentCaseId()));if(old==null||Set.of("PENDING","PROCESSING","WAITING_SUPPLEMENT").contains(old.status)&&!"INVALIDATED".equals(f.aftersaleStatus()))throw bad();}
  if(Set.of("PENDING_MERCHANT","APPROVED","AUTO_APPROVED").contains(Objects.toString(f.currentApplicationStatus(),"")))throw error("AFTERSALE_REFUND_APPLICATION_ACTIVE");
  var now=paid.databaseNow();var m=values("case",id(caseId),"order",id(order),"store",id(store),"oldVersion",Long.parseLong(expected),"version",Math.addExact(Long.parseLong(expected),1),"stage",stage,"json",json(f),"at",utc(now));
  one(db.insertSource(m));one(db.bind(m));audit(m,context,f.aftersaleStatus(),"PENDING","AFTERSALE_CREATED",caseId);
  before(()->{current(order,store,q,source);var saved=source(caseId,order,store);if(!json(f).equals(saved.sourceJson)) {if(!snapshot(saved).equals(f))throw bad();}});return null;
 });}
 public void projectTransition(String order,String store,String caseId,String newCaseVersion,CommandContext context,DataSource txSource){safe(()->{
  scope(store,txSource);command(context);var p=source(caseId,order,store);var r=orders.lock(id(order));var c=cases.get().requireCurrent(order,store,caseId,q(context),source);
  if(r==null||!Objects.equals(r.currentAftersaleId,id(caseId))||r.refundOrderId!=null||c==null||!newCaseVersion.equals(c.version())||Long.parseLong(newCaseVersion)!=Math.addExact(p.caseVersion,1)||!validTransition(p.status,c.status()))throw bad();
  sameIdentity(snapshot(p),c);var m=values("order",id(order),"store",id(store),"case",id(caseId),"status",c.status(),"oldVersion",r.version,"version",Math.addExact(r.version,1),"caseVersion",Long.parseLong(c.version()),"oldCaseVersion",p.caseVersion,"at",utc(paid.databaseNow()));
  one(db.transition(m));one(db.transitionSource(m));audit(m,context,p.status,c.status(),"AFTERSALE_TRANSITION",caseId);before(()->current(order,store,q(context),source));return null;
 });}
 public Permit acquireRefund(String order,String store,String caseId,String expectedCase,String decision,String commandId,CommandContext context,DataSource txSource){return safe(()->{
  scope(store,txSource);command(context);id(commandId);id(decision);if(context.operatorType()!=OperatorType.PLATFORM_OPERATOR)throw bad();
  var f=requireCurrentEligible(order,store,q(context),source);var p=source(caseId,order,store);var c=cases.get().requireCurrent(order,store,caseId,q(context),source);
  if(!caseId.equals(f.currentCaseId())||c==null||!c.active()||!"PROCESSING".equals(c.status())||!expectedCase.equals(c.version())||p.caseVersion!=Long.parseLong(expectedCase)||!p.status.equals(c.status())||db.committed(id(order))!=null||new OrderVerificationStore(source).mapper().active(id(order))!=0)throw bad();
  sameIdentity(f,c);var original=snapshot(p);sameOrigin(original,f);
  if(!p.sourceStage.equals(c.sourceStage())||"UNVERIFIED_POST_START".equals(p.sourceStage)&&!"UNVERIFIED".equals(f.verificationStatus())||"VERIFIED".equals(p.sourceStage)&&(!"VERIFIED".equals(f.verificationStatus())||!Objects.equals(original.verificationId(),f.verificationId())||!Objects.equals(original.verifiedAt(),f.verifiedAt())))throw error("AFTERSALE_REFUND_BLOCKED_BY_VERIFICATION");
  var permit=new Permit(UUID.randomUUID().toString(),f,caseId,expectedCase,p.sourceStage,decision,commandId,context);var e=new Entry(permit,TransactionSynchronizationManager.getResource(source));tokens.put(permit.token(),e);
  one(db.acquire(values("id",next(),"order",id(order),"token",permit.token())));
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly||!e.committed)throw bad();}public void afterCompletion(int status){tokens.remove(permit.token(),e);}});return permit;
 });}
 public Permit requirePending(String token,String order,String store,DataSource txSource){return safe(()->{scope(store,txSource);var e=tokens.get(token);if(e==null||e.committed||e.tx!=TransactionSynchronizationManager.getResource(source)||!order.equals(e.permit.fact().location().orderId())||!store.equals(e.permit.fact().location().storeId()))throw bad();return e.permit;});}
 public void commitCreated(String token,String caseId,String decision,String refund,OffsetDateTime at,DataSource txSource){safe(()->{
  var e=tokens.get(token);if(e==null)throw bad();var l=e.permit.fact().location();var p=requirePending(token,l.orderId(),l.storeId(),txSource);
  if(!caseId.equals(p.caseId())||!decision.equals(p.decisionId()))throw bad();var q=q(p.context());var a=decisions.get().requireCreated(caseId,decision,refund,l.storeId(),q,source);var rf=refunds.get().requireCreated(refund,caseId,decision,l.storeId(),q,source);
  var c=cases.get().requireCurrent(l.orderId(),l.storeId(),caseId,q,source);var sp=source(caseId,l.orderId(),l.storeId());var r=orders.lock(id(l.orderId()));
  if(a==null||rf==null||c==null||c.active()||!"RESOLVED".equals(c.status())||Long.parseLong(c.version())!=Long.parseLong(p.caseVersion())+1||r.refundOrderId!=null||!p.fact().orderVersion().equals(str(r.version))||!at.isEqual(a.createdAt())||!refund.equals(rf.execution().refundOrderId())||!a.refundNo().equals(rf.execution().refundNo())||!a.createdEventId().equals(rf.execution().createdEventId()))throw bad();
  var d=a.decision();if(!p.commandId().equals(d.commandId())||!p.caseVersion().equals(d.caseVersionBefore())||!p.context().operatorId().equals(d.operatorId())||!p.sourceStage().equals(d.sourceStage())||!d.funding().evidenceId().equals(rf.fundingEvidenceId())||!d.refundType().equals(rf.execution().refundType())||d.refundAmount().compareTo(rf.execution().refundAmount())!=0)throw bad();
  samePayment(p.fact().payment(),d.payment());
  var m=values("order",id(l.orderId()),"store",id(l.storeId()),"case",id(caseId),"decision",id(decision),"refund",id(refund),"oldVersion",r.version,"version",Math.addExact(r.version,1),"type",d.refundType(),"amount",d.refundAmount(),"stage",p.sourceStage(),"funding",d.funding().evidenceId(),"token",token,"status","RESOLVED","caseVersion",Long.parseLong(c.version()),"oldCaseVersion",sp.caseVersion,"at",utc(at));
  one(db.createRefund(m));one(db.transitionSource(m));one(db.insertCommit(m));one(db.commitGuard(m));e.committed=true;audit(m,p.context(),"PROCESSING","RESOLVED","AFTERSALE_REFUND_CREATED",refund);
  before(()->requireCreated(l.orderId(),l.storeId(),caseId,decision,refund,source));return null;
 });}
 public void requireCreated(String order,String store,String caseId,String decision,String refund,DataSource txSource){safe(()->{
  scope(store,txSource);var c=db.committed(id(order));var r=orders.lock(id(order));var p=source(caseId,order,store);
  if(c==null||r==null||!store.equals(str(c.storeId))||!caseId.equals(str(c.caseId))||!decision.equals(str(c.decisionId))||!refund.equals(str(c.refundOrderId))||!Objects.equals(r.refundOrderId,c.refundOrderId)||r.version<c.orderVersion||!Objects.equals(r.currentAftersaleId,c.caseId)||!"RESOLVED".equals(p.status))throw bad();
  var q=new QueryContext("afs-created-proof",OperatorType.SYSTEM,null);var a=decisions.get().requireCreated(caseId,decision,refund,store,q,source);var rf=refunds.get().requireCreated(refund,caseId,decision,store,q,source);
  var nowCurrent=current(order,store,q,source);if(!caseId.equals(nowCurrent.caseId())||!"RESOLVED".equals(nowCurrent.status())||!Objects.equals(p.orderVersion,c.orderVersion)||db.committedGuard(id(order),c.token)!=1)throw bad();
  if(a==null||rf==null||!offset(c.createdAt).isEqual(a.createdAt())||!c.refundType.equals(a.decision().refundType())||c.refundAmount.compareTo(a.decision().refundAmount())!=0||!c.refundType.equals(rf.execution().refundType())||c.refundAmount.compareTo(rf.execution().refundAmount())!=0||!c.fundingEvidenceId.equals(a.decision().funding().evidenceId())||!c.fundingEvidenceId.equals(rf.fundingEvidenceId())||!a.createdEventId().equals(rf.execution().createdEventId()))throw bad();
  samePayment(snapshot(p).payment(),a.decision().payment());return null;
 });}
 public OrderAfterSaleRefundFactsApi.Fact requireDecidedRefund(String order,String payment,String store,QueryContext q){return safe(()->{
  scope(store,source);system(q);var c=db.committed(id(order));if(c==null)throw bad();requireCreated(order,store,str(c.caseId),str(c.decisionId),str(c.refundOrderId),source);
  var f=snapshot(source(str(c.caseId),order,store));var p=f.payment();var r=orders.lock(id(order));var original=paid.lockResult(id(order));
  if(r==null||!"PAID".equals(r.paymentStatus)||r.canceledAt!=null||r.cancelReason!=null||r.payAmount==null||r.payAmount.compareTo(p.paidAmount())!=0||r.paidAt==null||!offset(r.paidAt).isEqual(p.paidAt()))throw bad();
  if(!payment.equals(p.paymentId())||original==null||!"NORMAL".equals(original.resultType())||original.paymentId()!=id(payment)||original.sourceEventId()!=id(p.paymentSuccessEventId())||original.paidAmount().compareTo(p.paidAmount())!=0||!original.paidAt().isEqual(p.paidAt())||!Objects.equals(original.channelTradeNo(),p.channelTradeNo())||!f.location().userId().equals(str(r.userId))||!f.location().merchantId().equals(str(r.merchantId))||!store.equals(str(r.storeId))||!f.location().reservationId().equals(str(r.reservationId)))throw bad();
  var l=f.location();return new OrderAfterSaleRefundFactsApi.Fact(new OrderRefundOriginFact(order,store,l.merchantId(),l.userId(),l.reservationId(),payment,p.paymentSuccessEventId(),p.channelTradeNo(),p.paidAmount(),p.paidAt(),"AFTERSALE_DECISION",null,str(c.refundOrderId),str(c.caseId),str(c.decisionId)),c.refundType,c.refundAmount,c.sourceStage,c.fundingEvidenceId);
 });}
 private OrderAfterSaleMapper.Source source(String c,String o,String s){var p=db.source(id(c));if(p==null||!o.equals(str(p.orderId))||!s.equals(str(p.storeId)))throw bad();return p;}
 private static OrderAfterSaleFactsApi.Fact snapshot(OrderAfterSaleMapper.Source p){try{return JSON.readValue(p.sourceJson,OrderAfterSaleFactsApi.Fact.class);}catch(Exception e){throw bad();}}
 private static boolean validTransition(String from,String to){if(!Set.of("PENDING","PROCESSING","WAITING_SUPPLEMENT").contains(from))return false;return switch(to){case "PENDING"->from.equals("PENDING");case "PROCESSING"->true;case "WAITING_SUPPLEMENT"->!from.equals("PENDING");case "WITHDRAWN"->true;case "CLOSED"->from.equals("PENDING");case "RESOLVED"->from.equals("PROCESSING");default->false;};}
 private static void sameIdentity(OrderAfterSaleFactsApi.Fact f,AfterSaleCaseFactsApi.CaseFact c){var l=f.location();if(!l.orderId().equals(c.orderId())||!l.storeId().equals(c.storeId())||!l.userId().equals(c.userId())||!l.merchantId().equals(c.merchantId()))throw bad();}
 private static void sameOrigin(OrderAfterSaleFactsApi.Fact a,OrderAfterSaleFactsApi.Fact b){if(!a.location().equals(b.location()))throw bad();samePayment(a.payment(),b.payment());}
 private static void samePayment(NormalPaymentOrigin a,NormalPaymentOrigin b){if(a==null||b==null||!a.paymentId().equals(b.paymentId())||!a.paymentNo().equals(b.paymentNo())||!a.paymentSuccessEventId().equals(b.paymentSuccessEventId())||!a.channelTradeNo().equals(b.channelTradeNo())||!a.paidAt().isEqual(b.paidAt())||a.paidAmount().compareTo(b.paidAmount())!=0||!a.currency().equals(b.currency()))throw bad();}
 private void audit(Map<String,Object> m,CommandContext c,String from,String to,String type,String reference){m.putAll(values("log",next(),"from",from,"status",to,"eventType",type,"actorType",c.operatorType().name(),"actor",c.operatorId()==null?null:id(c.operatorId()),"requestId",c.requestId(),"reference",reference));one(db.log(m));}
 private void scope(String store,DataSource tx){if(tx!=source||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);}
 private <T>T safe(Supplier<T> work){try{return work.get();}catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(e instanceof ApiException a)throw a;var failure=bad();if(e instanceof org.springframework.dao.DataAccessException||e instanceof org.springframework.transaction.TransactionException)failure.initCause(e);throw failure;}}
 private void before(Runnable r){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean ro){if(ro)throw bad();r.run();}});}
 private long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}private static void one(int n){if(n!=1)throw bad();}private static long id(String s){return IDS.fromApi(s);}private static String str(Long n){if(n==null||n<=0)throw bad();return n.toString();}private static String nullable(Long n){return n==null?null:str(n);}
 private static QueryContext q(CommandContext c){return new QueryContext(c.traceId(),OperatorType.SYSTEM,null);}private static void system(QueryContext q){if(q==null||q.operatorType()!=OperatorType.SYSTEM||q.operatorId()!=null)throw bad();}private static void command(CommandContext c){try{if(c==null||c.operatorType()==null||c.traceId()==null||c.traceId().isBlank())throw new IllegalArgumentException();PublicContractChecks.requireCommandRequestId(c);if(c.operatorType()!=OperatorType.SYSTEM)id(c.operatorId());}catch(IllegalArgumentException invalid){throw error(CommonApiCodes.INVALID_ARGUMENT);}}
 private static LocalDateTime utc(OffsetDateTime t){PublicContractChecks.requireMillisecondPrecision(t);return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}private static OffsetDateTime offset(LocalDateTime t){if(t==null)throw bad();return t.atOffset(ZoneOffset.UTC);}private static String json(Object o){try{return JSON.writeValueAsString(o);}catch(Exception e){throw bad();}}
 private static Map<String,Object> values(Object...a){var m=new LinkedHashMap<String,Object>();for(int i=0;i<a.length;i+=2)m.put((String)a[i],a[i+1]);return m;}private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}private static ApiException error(String code){return new ApiException(code,"ORDER aftersale source unavailable");}
}
