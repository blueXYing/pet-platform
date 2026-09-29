package com.petplatform.order.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.command.OrderRescheduleApi;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.command.ReservationSwapApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.command.VerificationRescheduleFenceApi;
import com.petplatform.task.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** R1/R2/R3: durable admission precedes the atomic guarded exchange. No public route. */
public final class OrderRescheduleService implements OrderRescheduleApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private static final ObjectMapper JSON=new ObjectMapper();
 private final DataSource source; private final SnowflakeIdGenerator ids; private final ScheduleCapacityGuardApi guard;
 private final com.petplatform.merchant.api.query.MerchantOrderAuthorityApi merchant;
 private final PaymentSuccessFactsApi payments; private final RefundOrderFactsApi refunds;
 private final ReservationConfirmApi confirmed; private final ReservationSwapApi swap;
 private final VerificationRescheduleFenceApi verification; private final IntegrationEventPublisher outbox;
 private final MerchantOrderPorts.Protection protection; private final MerchantOrderPorts.SessionAuthority sessions;
 private final OrderPaymentStore paid; private final OrderAutoConfirmStore orders; private final OrderRescheduleMapper mapper;
 private final TransactionTemplate tx; private final TaskCancellation cancel; private final JdbcAsyncTaskSubmitter tasks;
 public OrderRescheduleService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
   PaymentSuccessFactsApi payments,RefundOrderFactsApi refunds,ReservationConfirmApi confirmed,ReservationSwapApi swap,
   VerificationRescheduleFenceApi verification,IntegrationEventPublisher outbox,
   MerchantOrderPorts.Protection protection,MerchantOrderPorts.SessionAuthority sessions,com.petplatform.merchant.api.query.MerchantOrderAuthorityApi merchant){
  this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);
  this.payments=Objects.requireNonNull(payments);this.refunds=Objects.requireNonNull(refunds);this.confirmed=Objects.requireNonNull(confirmed);
  this.swap=Objects.requireNonNull(swap);this.verification=Objects.requireNonNull(verification);this.outbox=Objects.requireNonNull(outbox);
  this.protection=Objects.requireNonNull(protection);this.sessions=Objects.requireNonNull(sessions);this.merchant=Objects.requireNonNull(merchant);
  paid=new OrderPaymentStore(source);orders=new OrderAutoConfirmStore(source);mapper=new OrderRescheduleStore(source).mapper();
  cancel=new TaskCancellation(source);tasks=new JdbcAsyncTaskSubmitter(source,ids);
  tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public Receipt reschedule(Command c){
  validate(c);if(TransactionSynchronizationManager.isActualTransactionActive())throw bad();
  try{
   // Authenticate and check ownership before durable admission, then again under the guard on replay/execution.
   tx.executeWithoutResult(s->lock(c));
   byte[] canonical=canonical(c);
   String purpose="ORDER_RESCHEDULE:"+c.context().operatorId()+":"+c.context().requestId();
   var key=values("namespace",bytes("order.reschedule"),"actor",IDS.fromApi(c.context().operatorId()),"scope",bytes("CONSUMER"),"requestId",bytes(c.context().requestId()));
   tx.executeWithoutResult(s->{paid.sessionDefaults();var v=new LinkedHashMap<>(key);v.put("id",next());v.put("hash",MerchantOrderService.sha(canonical));
    v.put("canonical",protection.protect(purpose,canonical));mapper.reserve(v);same(mapper.binding(key),purpose,canonical);});
   return tx.execute(s->{
    paid.sessionDefaults();var b=mapper.binding(key);same(b,purpose,canonical);var r=lock(c);
    if("SUCCEEDED".equals(b.state))return replay(c,b,purpose);
    if(!"RESERVED".equals(b.state))throw bad();
    qualify(c,r);
    var now=paid.databaseNow();if(!now.isBefore(offset(r.appointmentStartAt)))throw error("ORDER_RESCHEDULE_AFTER_START");
    var newStart=c.appointmentStart()!=null?c.appointmentStart():c.pickupStart();
    if(!newStart.isAfter(now))throw error("ORDER_RESCHEDULE_AFTER_START");
    long reschedule=next(),event=next();var deadline=now.plusMinutes(30);
    var changed=swap.swap(new ReservationSwapApi.Command(c.context(),Long.toString(b.id),c.orderId(),Long.toString(r.reservationId),
     Long.toString(r.userId),Long.toString(r.merchantId),Long.toString(r.storeId),Long.toString(r.serviceId),r.fulfillmentType,
     offset(r.appointmentStartAt),offset(r.appointmentEndAt),c.appointmentStart(),c.appointmentEnd(),c.pickupStart(),c.returnStart(),
     c.selectedGeneralWindowId(),c.selectedPickupWindowId(),c.selectedReturnWindowId(),now));
    if(changed==null||!Long.toString(r.reservationId).equals(changed.reservationId())||changed.newVersion()!=changed.oldVersion()+1)throw bad();
    IDS.fromApi(changed.changeId());
    var fence=verification.invalidate(c.orderId(),Long.toString(r.reservationId),Long.toString(r.storeId),Long.toString(reschedule),now,c.context(),source);
    if(fence==null||!c.orderId().equals(fence.orderId())||!Long.toString(reschedule).equals(fence.rescheduleId()))throw bad();
    long fenceId=IDS.fromApi(fence.fenceId());
    var canceled=cancel.cancel(OrderAutoConfirmTaskSpec.key(c.orderId()),t->OrderAutoConfirmTaskSpec.matches(t,c.orderId(),offset(r.confirmDeadline)));
    var v=values("id",reschedule,"orderId",r.id,"commandId",b.id,"eventId",event,"userId",r.userId,"storeId",r.storeId,"reservationId",r.reservationId,
     "oldOrderVersion",r.version,"newOrderVersion",Math.addExact(r.version,1),"oldReservationVersion",changed.oldVersion(),"newReservationVersion",changed.newVersion(),
     "oldOrderStage",r.orderStage,"oldConfirmMode",r.confirmMode,"oldConfirmedAt",r.confirmedAt,"oldConfirmDeadline",r.confirmDeadline,
     "oldStart",r.appointmentStartAt,"oldEnd",r.appointmentEndAt,"oldPickup",utc(changed.oldPickup()),"oldReturn",utc(changed.oldReturn()),
     "newStart",utc(changed.start()),"newEnd",utc(changed.end()),"newPickup",utc(changed.pickup()),"newReturn",utc(changed.returning()),
     "rescheduledAt",utc(now),"newConfirmDeadline",utc(deadline),"fromRound",0,"toRound",1,"scheduleChangeId",IDS.fromApi(changed.changeId()),
     "verificationFenceId",fenceId,"oldTaskOutcome",canceled.name());
    if(mapper.change(v)!=1||mapper.record(v)!=1)throw bad();
    tasks.enqueueAt(OrderAutoConfirmTaskSpec.key(c.orderId(),1),"ORDER",OrderAutoConfirmTaskSpec.TYPE,"ORDER",r.id,null,
     OrderAutoConfirmTaskSpec.payload(c.orderId(),1,deadline),OrderAutoConfirmTaskSpec.MAX_RETRIES,OrderAutoConfirmTaskSpec.TYPE,deadline);
    var current=orders.lock(r.id);OrderConfirmEpoch.requireSchedule(source,current,confirmed,false,system(c));
    confirmed.assertConfirmed(c.orderId(),Long.toString(r.reservationId),Long.toString(r.storeId),system(c));
    var payload=values("orderId",c.orderId(),"reservationId",Long.toString(r.reservationId),"storeId",Long.toString(r.storeId),"rescheduleId",Long.toString(reschedule),"confirmRound",1,
     "oldAppointmentStart",offset(r.appointmentStartAt).toString(),"oldAppointmentEnd",offset(r.appointmentEndAt).toString(),
     "oldPickupStart",text(changed.oldPickup()),"oldReturnStart",text(changed.oldReturn()),"appointmentStart",text(changed.start()),"appointmentEnd",text(changed.end()),
     "pickupStart",text(changed.pickup()),"returnStart",text(changed.returning()),"rescheduledAt",now.toString(),"confirmDeadline",deadline.toString());
    outbox.publish(new IntegrationEvent<>(Long.toString(event),"OrderRescheduledEvent.v1",1,now,"ORDER",c.orderId(),c.context().traceId(),payload));
    v.put("logId",next());v.put("requestId",c.context().requestId());v.put("remark","rescheduleId="+reschedule+",eventId="+event);
    if(mapper.log(v)!=1)throw bad();
    var receipt=new Receipt(c.orderId(),Long.toString(r.reservationId),Long.toString(reschedule),1,Long.toString(r.version+1),"PENDING_CONFIRM",
     text(changed.start()),text(changed.end()),text(changed.pickup()),text(changed.returning()),text(now),text(deadline));
    if(mapper.succeed(b.id,protection.protect(purpose+":RESULT",json(receipt)))!=1)throw bad();
    return receipt;
   });
  }catch(ApiException known){throw known;}catch(org.springframework.dao.CannotAcquireLockException busy){throw error("ORDER_OPERATION_BUSY");}
  catch(RuntimeException failure){throw bad();}
 }
 private Row lock(Command c){
  paid.sessionDefaults();sessions.requireCurrent(c.context().operatorId());
  var loc=paid.locate(IDS.fromApi(c.orderId()));
  if(loc.size()!=1||!c.context().operatorId().equals(Long.toString(loc.getFirst().userId())))throw error(CommonApiCodes.FORBIDDEN);
  String store=Long.toString(loc.getFirst().storeId());guard.acquire(List.of(store),system(c));guard.requireHeld(store,source);
  var r=orders.lock(IDS.fromApi(c.orderId()));if(r==null||!store.equals(Long.toString(r.storeId))||!c.context().operatorId().equals(Long.toString(r.userId)))throw error(CommonApiCodes.FORBIDDEN);
  sessions.requireCurrent(c.context().operatorId());return r;
 }
 private void qualify(Command c,Row r){
  if(r.rescheduleCount==null||r.rescheduleCount<0||r.rescheduleCount>1)throw bad();
  if(r.rescheduleCount==1)throw error("ORDER_RESCHEDULE_LIMIT_REACHED");
  if(!Objects.equals(r.version,Long.parseLong(c.expectedOrderVersion())))throw error(CommonApiCodes.CONFLICT);
  if(!Set.of("PENDING_CONFIRM","PENDING_SERVICE").contains(r.orderStage)||!"UNVERIFIED".equals(r.verificationStatus))throw error("ORDER_STATE_NOT_ALLOWED");
  if(!"PAID".equals(r.paymentStatus)||r.canceledAt!=null||r.cancelReason!=null||r.refundedAmount==null||r.refundedAmount.signum()!=0
   ||r.payAmount==null||r.payAmount.signum()<=0||r.appointmentStartAt==null||r.appointmentEndAt==null||r.serviceId==null)throw bad();
  if("PENDING_CONFIRM".equals(r.orderStage)&&(r.confirmMode!=null||r.confirmedAt!=null))throw bad();
  if("PENDING_SERVICE".equals(r.orderStage)&&(r.confirmedAt==null||!Set.of("AUTO","MERCHANT").contains(r.confirmMode==null?"":r.confirmMode)))throw bad();
  merchant.requireExistingOrderAvailability(Long.toString(r.merchantId),Long.toString(r.storeId),system(c));
  OrderConfirmEpoch.require(source,r);
  var original=paid.lockResult(r.id);if(original==null||!"NORMAL".equals(original.resultType()))throw bad();
  var p=payments.requireSucceeded(Long.toString(original.paymentId()),c.orderId(),Long.toString(r.storeId),system(c));
  if(p==null||!c.orderId().equals(p.orderId())||!Long.toString(r.userId).equals(p.userId())||!Long.toString(r.storeId).equals(p.storeId())
   ||!Long.toString(r.merchantId).equals(p.merchantId())||!Long.toString(original.paymentId()).equals(p.paymentId())||!Long.toString(original.sourceEventId()).equals(p.successEventId())
   ||!Objects.equals(original.channelTradeNo(),p.channelTradeNo())||p.paidAmount()==null||original.paidAmount()==null||r.payAmount.compareTo(p.paidAmount())!=0
   ||original.paidAmount().compareTo(p.paidAmount())!=0||p.paidAt()==null||original.paidAt()==null||!original.paidAt().isEqual(p.paidAt())
   ||!offset(r.paidAt).isEqual(p.paidAt())||!"CNY".equals(p.currency()))throw bad();
  var presence=refunds.findByOrder(c.orderId(),Long.toString(r.storeId),system(c));if(presence==null)throw bad();
  if(r.refundOrderId!=null||presence.exists())throw error("ORDER_REFUND_ALREADY_CREATED");
  if(r.currentAftersaleId!=null||r.currentRefundApplicationId!=null)throw bad();
  confirmed.assertConfirmed(c.orderId(),Long.toString(r.reservationId),Long.toString(r.storeId),system(c));
  if("IN_STORE".equals(r.fulfillmentType)){
   if(c.appointmentStart()==null)throw error(CommonApiCodes.INVALID_ARGUMENT);
   Integer duration=mapper.duration(r.id);if(duration==null||duration<=0)throw bad();
   if(!c.appointmentStart().plusMinutes(duration).isEqual(c.appointmentEnd()))throw error(CommonApiCodes.INVALID_ARGUMENT);
  }else if(!"PICKUP_DELIVERY".equals(r.fulfillmentType)||c.pickupStart()==null)throw error(CommonApiCodes.INVALID_ARGUMENT);
 }
 private Receipt replay(Command c,OrderMerchantMapper.Binding b,String purpose){
  try{
   if(!Objects.equals(b.resultVersion,1)||b.resultBytes==null)throw bad();
   var p=mapper.recordFor(IDS.fromApi(c.orderId()));
   var result=JSON.readValue(protection.reveal(purpose+":RESULT",b.resultBytes),Receipt.class);
   var expected=new Receipt(c.orderId(),Long.toString(p.reservationId),Long.toString(p.id),1,Long.toString(p.newOrderVersion),"PENDING_CONFIRM",
    text(offset(p.newStart)),text(offset(p.newEnd)),text(offset(p.newPickup)),text(offset(p.newReturn)),text(offset(p.rescheduledAt)),text(offset(p.newConfirmDeadline)));
   if(!Objects.equals(p.commandId,b.id)||!expected.equals(result))throw bad();return result;
  }catch(Exception failure){throw bad();}
 }
 private void same(OrderMerchantMapper.Binding b,String purpose,byte[] input){
  if(b==null||!"canonical-v1".equals(b.canonicalVersion))throw bad();
  if(!MerchantOrderService.sha(input).equals(b.payloadSha256)||!MessageDigest.isEqual(input,protection.reveal(purpose,b.canonicalBytes)))throw error(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT);
 }
 private static byte[] canonical(Command c){return json(values("orderId",c.orderId(),"expectedOrderVersion",c.expectedOrderVersion(),
  "appointmentStart",text(c.appointmentStart()),"appointmentEnd",text(c.appointmentEnd()),"pickupStart",text(c.pickupStart()),"returnStart",text(c.returnStart()),
  "selectedGeneralWindowId",c.selectedGeneralWindowId(),"selectedPickupWindowId",c.selectedPickupWindowId(),"selectedReturnWindowId",c.selectedReturnWindowId()));}
 private static void validate(Command c){
  try{
   if(c==null||c.context()==null||c.context().operatorType()!=OperatorType.USER)throw new IllegalArgumentException();
   IDS.fromApi(c.orderId());IDS.fromApi(c.context().operatorId());PublicContractChecks.requireTerminalRequestId(c.context().requestId());
   if(c.context().traceId()==null||c.context().traceId().isBlank()||c.context().traceId().length()>64||c.context().traceId().codePoints().anyMatch(Character::isISOControl)
    ||c.expectedOrderVersion()==null||!c.expectedOrderVersion().matches("0|[1-9][0-9]*")||Long.parseLong(c.expectedOrderVersion())<0)throw new IllegalArgumentException();
   if(c.appointmentStart()!=null){
    minute(c.appointmentStart());minute(c.appointmentEnd());IDS.fromApi(c.selectedGeneralWindowId());
    if(!c.appointmentEnd().isAfter(c.appointmentStart())||c.pickupStart()!=null||c.returnStart()!=null||c.selectedPickupWindowId()!=null||c.selectedReturnWindowId()!=null)throw new IllegalArgumentException();
   }else{
    minute(c.pickupStart());minute(c.returnStart());IDS.fromApi(c.selectedPickupWindowId());IDS.fromApi(c.selectedReturnWindowId());
    if(c.appointmentEnd()!=null||c.selectedGeneralWindowId()!=null||c.returnStart().isBefore(c.pickupStart().plusMinutes(120))||c.selectedPickupWindowId().equals(c.selectedReturnWindowId()))throw new IllegalArgumentException();
   }
  }catch(RuntimeException bad){throw error(CommonApiCodes.INVALID_ARGUMENT);}
 }
 private static void minute(OffsetDateTime d){if(d==null||d.getSecond()!=0||d.getNano()!=0||d.getOffset().getTotalSeconds()%60!=0)throw new IllegalArgumentException();}
 private long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}
 private static QueryContext system(Command c){return new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null);}
 private static LocalDateTime utc(OffsetDateTime t){return t==null?null:t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
 private static OffsetDateTime offset(LocalDateTime t){return t==null?null:t.atOffset(ZoneOffset.UTC);}
 private static String text(OffsetDateTime t){return t==null?null:t.withOffsetSameInstant(ZoneOffset.UTC).toString();}
 private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 private static byte[] json(Object v){try{return JSON.writeValueAsBytes(v);}catch(Exception e){throw bad();}}
 private static Map<String,Object> values(Object...pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
 private static ApiException error(String code){return new ApiException(code,"Reschedule cannot be applied; refresh or retry the original request");}
 private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
}
