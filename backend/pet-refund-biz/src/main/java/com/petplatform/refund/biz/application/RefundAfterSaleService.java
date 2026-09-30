package com.petplatform.refund.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.aftersale.api.query.AfterSaleRefundFactsApi;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.command.OrderAfterSaleCommitApi;
import com.petplatform.payment.api.query.*;
import com.petplatform.refund.api.command.RefundAfterSaleCommandApi;
import com.petplatform.refund.api.query.RefundAfterSaleFactsApi;
import com.petplatform.refund.biz.infrastructure.persistence.*;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundAfterSaleMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;

/** Atomic creation only. The AFS command commits its final decision and ORDER capability in this transaction. */
public final class RefundAfterSaleService implements RefundAfterSaleCommandApi,RefundAfterSaleFactsApi {
 private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private final DataSource source;private final SnowflakeIdGenerator ids;private final ScheduleCapacityGuardApi guard;
 private final OrderAfterSaleCommitApi orders;private final PaymentSuccessFactsApi payments;private final Supplier<AfterSaleRefundFactsApi> decisions;
 private final IntegrationEventPublisher outbox;private final RefundAfterSaleMapper db;private final RefundExecutionStore execution;private final JdbcAsyncTaskSubmitter tasks;
 public RefundAfterSaleService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,OrderAfterSaleCommitApi orders,
   PaymentSuccessFactsApi payments,Supplier<AfterSaleRefundFactsApi> decisions,IntegrationEventPublisher outbox){
  this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);this.orders=Objects.requireNonNull(orders);this.payments=Objects.requireNonNull(payments);this.decisions=Objects.requireNonNull(decisions);this.outbox=Objects.requireNonNull(outbox);db=RefundAfterSaleStore.mapper(source);execution=new RefundExecutionStore(source);tasks=new JdbcAsyncTaskSubmitter(source,ids);
 }
 public Created create(Create c,DataSource txSource){validate(c);return safe(()->{
  scope(c.storeId(),txSource);
  var q=new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null);var permit=orders.requirePending(c.orderToken(),c.orderId(),c.storeId(),source);
  var d=decisions.get().requirePendingDecision(c.caseId(),c.decisionId(),c.orderToken(),c.storeId(),q,source);if(d==null||!c.orderId().equals(d.orderId())||!c.caseId().equals(d.caseId())||!c.decisionId().equals(d.decisionId())||!c.storeId().equals(d.storeId())||!c.context().operatorId().equals(d.operatorId())||!permit.commandId().equals(d.commandId())||!permit.caseVersion().equals(d.caseVersionBefore())||!permit.context().equals(c.context())||!permit.sourceStage().equals(d.sourceStage()))throw bad();
  var p=payments.requireSucceeded(d.payment().paymentId(),c.orderId(),c.storeId(),q);var origin=permit.fact().payment();
  if(p==null||!d.userId().equals(p.userId())||!d.merchantId().equals(p.merchantId())||!origin.paymentId().equals(p.paymentId())||!origin.paymentNo().equals(p.paymentNo())||!origin.paymentSuccessEventId().equals(p.successEventId())||!origin.channelTradeNo().equals(p.channelTradeNo())||origin.paidAmount().compareTo(p.paidAmount())!=0||!origin.paidAt().isEqual(p.paidAt())||!"CNY".equals(p.currency())||!origin.equals(d.payment()))throw bad();
  var now=execution.databaseNow().atOffset(ZoneOffset.UTC);RefundFundingEvidenceChecks.requireAllowed(check(d),d.funding(),now);
  if(!execution.lockPresence(id(c.orderId())).isEmpty())throw bad();long refund=next(),number=next(),event=next();
  var v=values("refund",refund,"number",number,"order",id(c.orderId()),"case",id(c.caseId()),"decision",id(c.decisionId()),"type",d.refundType(),"amount",d.refundAmount(),"ratio",RefundFundingEvidenceChecks.ratio(d.refundAmount(),p.paidAmount()),"operator",id(d.operatorId()),"now",now.toLocalDateTime(),"payment",id(p.paymentId()),"paymentNo",id(p.paymentNo()),"store",id(c.storeId()),"merchant",id(p.merchantId()),"user",id(p.userId()),"paymentEvent",id(p.successEventId()),"trade",p.channelTradeNo(),"paid",p.paidAmount(),"paidAt",p.paidAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),"key",executionKey(c.caseId(),c.decisionId()),"event",event,"command",id(d.commandId()),"funding",d.funding().evidenceId(),"proof",json(d));
  one(db.insertRefund(v));one(db.insertExecution(v));one(db.insertProof(v));
  outbox.publish(new IntegrationEvent<>(str(event),"RefundOrderCreatedEvent.v1",1,now,"REFUND",str(refund),c.context().traceId(),Map.of("refundOrderId",str(refund),"refundNo",str(number),"orderId",c.orderId(),"refundType",d.refundType(),"refundAmount",d.refundAmount(),"source","AFTERSALE_DECISION","createdAt",now.toString())));
  tasks.enqueue("AFTERSALE_REFUND_SUBMIT:"+refund+":0","REFUND","AFTERSALE_REFUND_SUBMIT","REFUND",refund,0L,LateRefundService.payload(refund,c.storeId()),8,"REFUND_CHANNEL");
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly)throw bad();RefundFundingEvidenceChecks.requireAllowed(check(d),d.funding(),execution.databaseNow().atOffset(ZoneOffset.UTC));requireCreated(str(refund),c.caseId(),c.decisionId(),c.storeId(),q,source);orders.requireCreated(c.orderId(),c.storeId(),c.caseId(),c.decisionId(),str(refund),source);}});
  return new Created(str(refund),str(number),d.refundType(),d.refundAmount(),p.paidAmount(),str(event),now);
 });}
 /** Historical leaf: reads no AFS/ORDER facts, so callers can compose both independent proofs. */
 public CreatedFact requireCreated(String refund,String caseId,String decision,String store,QueryContext q,DataSource txSource){return safe(()->{
  scope(store,txSource);if(q==null||q.operatorType()!=OperatorType.SYSTEM)throw bad();var proof=db.proof(id(refund));var row=LateRefundService.read(execution.lockById(id(refund)));if(proof==null||row==null||proof.refundOrderId!=id(refund)||proof.caseId!=id(caseId)||proof.decisionId!=id(decision))throw bad();
  var d=decode(proof.proofJson);var f=row.fact();var p=d.payment();RefundFundingEvidenceChecks.requireAllowed(check(d),d.funding(),d.decidedAt());
  if(!Objects.equals(proof.businessAftersaleId,proof.caseId)||proof.businessApplicationId!=null||!"OPS".equals(proof.initiatorType)||!Objects.equals(proof.initiatorId,id(d.operatorId())))throw bad();
  if(!caseId.equals(d.caseId())||!decision.equals(d.decisionId())||!store.equals(d.storeId())||!proof.fundingEvidenceId.equals(d.funding().evidenceId())||proof.commandId!=id(d.commandId())||!"AFTERSALE_DECISION".equals(f.sourceType())||f.lateEventId()!=null||f.sourceEventId()!=null||!caseId.equals(f.sourceBizId())||!decision.equals(f.sourceDecisionId())||!store.equals(f.storeId())||!d.orderId().equals(f.orderId())||!d.userId().equals(f.userId())||!d.merchantId().equals(f.merchantId())||!p.paymentId().equals(f.paymentId())||!p.paymentNo().equals(f.paymentNo())||!p.paymentSuccessEventId().equals(f.paymentSuccessEventId())||!p.channelTradeNo().equals(f.channelTradeNo())||!p.paidAt().isEqual(f.paidAt())||p.paidAmount().compareTo(f.originalPaidAmount())!=0||d.refundAmount().compareTo(f.refundAmount())!=0||!d.refundType().equals(f.refundType())||!d.refundType().equals(row.type())||!"AFTERSALE_DECISION".equals(row.refundSource())||!"LAKALA".equals(row.channel())||!"CNY".equals(f.currency())||f.bindingVersion()!=0||row.businessRefundId()!=id(refund)||row.businessRefundNo()!=id(f.refundNo())||row.businessOrderId()!=id(d.orderId())||!f.createdAt().isEqual(row.businessCreatedAt())||!f.createdAt().isEqual(proof.createdAt.atOffset(ZoneOffset.UTC))||f.createdAt().isBefore(d.decidedAt())||row.businessAmount().compareTo(d.refundAmount())!=0||row.ratio().compareTo(RefundFundingEvidenceChecks.ratio(d.refundAmount(),p.paidAmount()))!=0||!executionKey(caseId,decision).equals(row.requestId())||!Set.of("CREATED","PROCESSING","UNKNOWN","SUCCESS","FAILED").contains(f.status()))throw bad();
  id(f.createdEventId());return new CreatedFact(f,proof.fundingEvidenceId);
 });}
 public static RefundFundingEligibilityFactsApi.FundingCheck check(AfterSaleRefundFactsApi.DecisionFact d){var p=d.payment();return new RefundFundingEligibilityFactsApi.FundingCheck(d.orderId(),p.paymentId(),p.paymentNo(),p.paymentSuccessEventId(),p.channelTradeNo(),d.userId(),d.merchantId(),d.storeId(),d.caseId(),d.decisionId(),d.commandId(),d.refundType(),d.refundAmount(),p.paidAmount(),p.currency(),"DECISION_COMMIT",null,null,null);}
 public static String executionKey(String c,String d){return "AFTERSALE_REFUND:"+c+":"+d;}
 private static void validate(Create c){try{if(c==null||c.context()==null||c.context().operatorType()!=OperatorType.PLATFORM_OPERATOR||c.context().traceId()==null||c.context().traceId().isBlank()||c.orderToken()==null||c.orderToken().isBlank())throw new IllegalArgumentException();PublicContractChecks.requireCommandRequestId(c.context());id(c.context().operatorId());id(c.orderId());id(c.storeId());id(c.caseId());id(c.decisionId());}catch(IllegalArgumentException invalid){throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid internal after-sale refund command");}}
 private void scope(String store,DataSource tx){if(tx!=source||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);}
 private <T>T safe(Supplier<T> action){try{return action.get();}catch(RuntimeException failure){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(RefundApplicationService.infrastructure(failure))throw new RefundApplicationService.InfrastructureUnavailable(failure);throw failure instanceof ApiException a?a:bad();}}
 private long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}private static long id(String s){return IDS.fromApi(s);}private static String str(long n){return Long.toString(n);}private static void one(int n){if(n!=1)throw bad();}
 private static String json(Object o){try{return JSON.writeValueAsString(o);}catch(Exception e){throw bad();}}private static AfterSaleRefundFactsApi.DecisionFact decode(String s){try{return JSON.readValue(s,AfterSaleRefundFactsApi.DecisionFact.class);}catch(Exception e){throw bad();}}
 private static Map<String,Object> values(Object...p){var m=new LinkedHashMap<String,Object>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}
 private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"After-sale refund proof unavailable");}
}
