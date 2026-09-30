package com.petplatform.order.biz.apiimpl;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.query.*;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAfterSaleMapper;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** A real channel success projects the adjudicated amount and releases the original reservation. */
public final class OrderAfterSaleRefundProjectionConsumer implements IntegrationEventConsumer {
 private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private final DataSource source;private final ScheduleCapacityGuardApi guard;private final OrderAfterSaleFactsApi locations;
 private final OrderAfterSaleRefundFactsApi origins;private final RefundExecutionFactsApi refunds;private final ReservationRefundReleaseApi release;
 private final OrderAfterSaleMapper db;private final OrderAutoConfirmStore orders;private final JdbcOutboxConsumeGuard claims;private final TransactionTemplate tx;
 public OrderAfterSaleRefundProjectionConsumer(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,OrderAfterSaleFactsApi locations,OrderAfterSaleRefundFactsApi origins,RefundExecutionFactsApi refunds,ReservationRefundReleaseApi release){
  this.source=source;this.guard=guard;this.locations=locations;this.origins=origins;this.refunds=refunds;this.release=release;db=OrderAfterSaleStore.mapper(source);orders=new OrderAutoConfirmStore(source);claims=new JdbcOutboxConsumeGuard(source,ids);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
 }
 public String consumerName(){return "ORDER_AFTERSALE_REFUND";}public Set<String> eventTypes(){return Set.of("RefundSucceededEvent.v1");}
 public void consume(DispatchedEvent event){if(TransactionSynchronizationManager.isActualTransactionActive())throw bad();try{
  if(event==null||event.eventVersion()!=1||!eventTypes().contains(event.eventType())||!"REFUND".equals(event.aggregateType()))throw bad();var n=JSON.readTree(event.payloadJson());if(n==null||!n.isObject())throw bad();Set<String> keys=new HashSet<>();n.fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("refundOrderId","refundNo","orderId","refundType","refundSource","refundAmount","originalPaidAmount","channelRefundNo","succeededAt")))throw bad();
  String family=text(n,"refundSource");if(Set.of("LATE_PAYMENT_TIMEOUT","MERCHANT_REJECT_ORDER","MERCHANT_APPROVED","MERCHANT_TIMEOUT_AUTO").contains(family))return;if(!"AFTERSALE_DECISION".equals(family))throw bad();
  String refund=text(n,"refundOrderId"),order=text(n,"orderId"),type=text(n,"refundType");id(order);id(text(n,"refundNo"));id(event.eventId());if(id(refund)!=event.aggregateId()||!n.path("refundAmount").isNumber()||!n.path("originalPaidAmount").isNumber())throw bad();
  com.petplatform.payment.api.query.RefundFundingEvidenceChecks.amount(type,n.path("refundAmount").decimalValue(),n.path("originalPaidAmount").decimalValue());var at=OffsetDateTime.parse(text(n,"succeededAt"));PublicContractChecks.requireMillisecondPrecision(at);if(event.occurredAt()==null||!at.isEqual(event.occurredAt()))throw bad();var q=new QueryContext(event.traceId(),OperatorType.SYSTEM,null);String store=locations.locate(order,q).storeId();
  tx.executeWithoutResult(s->{guard.acquire(List.of(store),q);guard.requireHeld(store,source);var f=refunds.requireForChannel(refund,store,q);var o=origins.requireDecidedRefund(order,f.paymentId(),store,q);var success=refunds.requireSucceeded(refund,order,store,q);var origin=o.origin();
   if(!family.equals(f.sourceType())||!family.equals(origin.sourceType())||!family.equals(success.refundSource())||!type.equals(f.refundType())||!type.equals(o.refundType())||!type.equals(success.refundType())||!refund.equals(origin.refundOrderId())||!f.sourceBizId().equals(origin.sourceBizId())||!f.sourceDecisionId().equals(origin.sourceDecisionId())||f.sourceEventId()!=null||origin.sourceEventId()!=null||!event.eventId().equals(success.successEventId())||!text(n,"refundNo").equals(success.refundNo())||!text(n,"channelRefundNo").equals(success.channelRefundNo())||!at.isEqual(success.succeededAt())||!order.equals(success.orderId())||!store.equals(success.storeId())||!f.paymentId().equals(success.paymentId())||o.refundAmount().compareTo(success.refundAmount())!=0||f.refundAmount().compareTo(success.refundAmount())!=0||n.path("refundAmount").decimalValue().compareTo(success.refundAmount())!=0||n.path("originalPaidAmount").decimalValue().compareTo(success.originalPaidAmount())!=0||origin.channelPaidAmount().compareTo(success.originalPaidAmount())!=0||!origin.paymentSuccessEventId().equals(f.paymentSuccessEventId())||!origin.channelTradeNo().equals(f.channelTradeNo())||!origin.channelPaidAt().isEqual(f.paidAt())||!origin.userId().equals(f.userId())||!origin.merchantId().equals(f.merchantId()))throw bad();
   var c=db.committed(id(order));var r=orders.lock(id(order));if(c==null||r==null||c.refundOrderId!=id(refund)||!type.equals(c.refundType)||c.refundAmount.compareTo(success.refundAmount())!=0)throw bad();boolean first=claims.tryClaim(consumerName(),event);
   if(c.successEventId==null){if(!first||c.succeededAt!=null||r.refundedAmount.signum()!=0)throw bad();var v=values("order",id(order),"store",id(store),"refund",id(refund),"event",id(event.eventId()),"amount",success.refundAmount(),"at",at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());if(db.projectSuccess(v)!=1||db.success(v)!=1)throw bad();}
   else if(first||c.successEventId!=id(event.eventId())||c.succeededAt==null||!c.succeededAt.atOffset(ZoneOffset.UTC).isEqual(at)||r.refundedAmount.compareTo(success.refundAmount())!=0)throw bad();
   if(!Set.of("UNVERIFIED_POST_START","VERIFIED").contains(o.sourceStage()))throw bad();release.release(order,origin.reservationId(),store,refund,q);
  });
 }catch(ApiException e){throw e;}catch(Exception e){throw bad();}}
 private static long id(String s){return IDS.fromApi(s);}private static String text(JsonNode n,String f){if(!n.path(f).isTextual()||n.path(f).asText().isBlank()||n.path(f).asText().codePoints().anyMatch(Character::isISOControl))throw bad();return n.path(f).asText();}
 private static Map<String,Object> values(Object...p){var m=new LinkedHashMap<String,Object>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}private static ApiException bad(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"After-sale refund projection unavailable");}
}
