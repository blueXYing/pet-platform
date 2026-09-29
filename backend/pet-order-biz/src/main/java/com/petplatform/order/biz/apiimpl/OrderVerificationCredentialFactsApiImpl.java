package com.petplatform.order.biz.apiimpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.order.api.query.OrderVerificationCredentialFactsApi;
import com.petplatform.order.biz.application.*;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
public final class OrderVerificationCredentialFactsApiImpl implements OrderVerificationCredentialFactsApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private final DataSource source;private final ScheduleCapacityGuardApi guard;private final OrderPaymentStore paid;private final OrderAutoConfirmStore orders;
 private final PaymentSuccessFactsApi payments;private final RefundOrderFactsApi refunds;private final ReservationConfirmApi confirmed;
 private final ScheduleProtectionFactsApi schedule;private final MerchantOrderAuthorityApi merchant;
 public OrderVerificationCredentialFactsApiImpl(DataSource s,ScheduleCapacityGuardApi g,PaymentSuccessFactsApi p,RefundOrderFactsApi r,
   ReservationConfirmApi c,ScheduleProtectionFactsApi sch,MerchantOrderAuthorityApi m){source=s;guard=g;payments=p;refunds=r;confirmed=c;schedule=sch;merchant=m;paid=new OrderPaymentStore(s);orders=new OrderAutoConfirmStore(s);}
 public Location locate(String id,QueryContext q){system(q);paid.sessionDefaults();var r=new OrderCredentialStore(source).locate(IDS.fromApi(id));if(r==null)throw error(CommonApiCodes.FORBIDDEN);
  return new Location(str(r.id),str(r.userId),str(r.merchantId),str(r.storeId),str(r.reservationId));}
 private Row locked(String id,String store,QueryContext q){system(q);guard.requireHeld(store,source);var r=orders.lock(IDS.fromApi(id));if(r==null||!store.equals(str(r.storeId)))throw bad();return r;}
 public Fact requireEligible(String id,String store,QueryContext q){
  var r=locked(id,store,q);
  if(r.orderStage==null||!Set.of("PENDING_PAYMENT","PENDING_CONFIRM","PENDING_SERVICE","COMPLETED","CANCELED").contains(r.orderStage)
    ||r.verificationStatus==null||!Set.of("UNVERIFIED","VERIFIED").contains(r.verificationStatus))throw bad();
  var refund=refunds.findByOrder(id,store,q);if(refund==null)throw bad();
  if(r.refundOrderId!=null||refund.exists())throw error("VERIFICATION_BLOCKED_BY_REFUND");
  if("VERIFIED".equals(r.verificationStatus))throw error("VERIFICATION_ALREADY_DONE");
  if(!"PENDING_SERVICE".equals(r.orderStage))throw error("VERIFICATION_NOT_ALLOWED");
  if(!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus)||r.canceledAt!=null||r.cancelReason!=null
    ||r.confirmedAt==null||r.refundedAmount==null||r.refundedAmount.signum()!=0||r.payAmount==null||r.payAmount.signum()<=0)throw bad();
  merchant.requireExistingOrderAvailability(str(r.merchantId),store,q);OrderConfirmEpoch.requireSchedule(source,r,confirmed,false,q);
  var original=paid.lockResult(r.id);if(original==null||!"NORMAL".equals(original.resultType()))throw bad();
  var p=payments.requireSucceeded(Long.toString(original.paymentId()),id,store,q);
  if(p==null||!id.equals(p.orderId())||!store.equals(p.storeId())||!str(r.userId).equals(p.userId())||!str(r.merchantId).equals(p.merchantId())
   ||!Long.toString(original.paymentId()).equals(p.paymentId())||!Long.toString(original.sourceEventId()).equals(p.successEventId())
   ||!Objects.equals(original.channelTradeNo(),p.channelTradeNo())||p.paidAmount()==null||original.paidAmount()==null
   ||r.payAmount.compareTo(p.paidAmount())!=0||original.paidAmount().compareTo(p.paidAmount())!=0||p.paidAt()==null||original.paidAt()==null
   ||!original.paidAt().isEqual(p.paidAt())||!r.paidAt.atOffset(ZoneOffset.UTC).isEqual(p.paidAt())||!"CNY".equals(p.currency()))throw bad();
  confirmation(r);
  confirmed.assertConfirmed(id,str(r.reservationId),store,q);var facts=schedule.readStore(store,q);if(facts==null||!facts.complete())throw bad();
  var matches=facts.reservations().stream().filter(x->str(r.reservationId).equals(x.reservationId())).toList();if(matches.size()!=1)throw bad();var res=matches.getFirst();
  if(!id.equals(res.orderId())||!str(r.userId).equals(res.userId())||!str(r.merchantId).equals(res.merchantId())||!str(r.serviceId).equals(res.serviceId())
   ||!Objects.equals(r.fulfillmentType,res.fulfillmentType())||!res.startAt().isEqual(r.appointmentStartAt.atOffset(ZoneOffset.UTC))||!res.endAt().isEqual(r.appointmentEndAt.atOffset(ZoneOffset.UTC)))throw bad();
  var claims=facts.claims().stream().filter(c->str(r.reservationId).equals(c.reservationId())).toList();
  var kinds="IN_STORE".equals(r.fulfillmentType)?Set.of("GENERAL"):"PICKUP_DELIVERY".equals(r.fulfillmentType)?Set.of("PICKUP","RETURN"):Set.<String>of();
  if(kinds.isEmpty()||claims.size()!=kinds.size()||!new HashSet<>(claims.stream().map(c->c.kind()).toList()).equals(kinds))throw bad();
  for(var c:claims){
   if(!store.equals(c.storeId())||!str(r.serviceId).equals(c.serviceId())||!c.endAt().isAfter(c.startAt()))throw bad();
   var windows=facts.windows().stream().filter(w->c.windowId().equals(w.windowId())).toList();if(windows.size()!=1)throw bad();var w=windows.getFirst();
   if(!c.kind().equals(w.kind())||c.startAt().isBefore(w.startAt())||c.endAt().isAfter(w.endAt()))throw bad();
   if(kinds.size()==1){Integer duration=new OrderRescheduleStore(source).mapper().duration(r.id);
    if(duration==null||duration<=0||!c.startAt().isEqual(res.startAt())||!c.endAt().isEqual(res.endAt())||!c.startAt().plusMinutes(duration).isEqual(c.endAt()))throw bad();
   }else if(!c.startAt().isEqual(w.startAt())||!c.endAt().isEqual(w.endAt())
    ||"PICKUP".equals(c.kind())&&(!c.startAt().equals(res.pickupStartAt())||!c.startAt().isEqual(res.startAt()))
    ||"RETURN".equals(c.kind())&&(!c.startAt().equals(res.returnStartAt())||!c.endAt().isEqual(res.endAt())))throw bad();
  }
  if(kinds.size()==2&&(res.pickupStartAt()==null||res.returnStartAt()==null||res.returnStartAt().isBefore(res.pickupStartAt().plusMinutes(120))))throw bad();
  return new Fact(location(r),str(r.version),r.rescheduleCount,time(res.startAt()),time(res.endAt()),time(res.pickupStartAt()),time(res.returnStartAt()));
 }
 private void confirmation(Row r){
  if("MERCHANT".equals(r.confirmMode)){
   var d=new OrderMerchantStore(source).mapper().decision(r.id,r.rescheduleCount);
   if(d==null||!"CONFIRM".equals(d.action)||!Objects.equals(d.orderId,r.id)||!Objects.equals(d.storeId,r.storeId)||!Objects.equals(d.decidedAt,r.confirmedAt)
    ||d.eventId==null||d.eventId<=0||d.commandId==null||d.commandId<=0||d.refundOrderId!=null)throw bad();
  }else if("AUTO".equals(r.confirmMode)){
   var proofs=orders.proofs(r.id,"TASK:"+OrderAutoConfirmTaskSpec.key(str(r.id),r.rescheduleCount));if(proofs.size()!=1)throw bad();
   try{var p=new ObjectMapper().readTree(proofs.getFirst());
    if(!p.isObject()||p.size()!=8||!p.path("confirmRound").isIntegralNumber()||p.path("confirmRound").asInt(-1)!=r.rescheduleCount
     ||!str(r.id).equals(p.path("orderId").asText())||!str(r.reservationId).equals(p.path("reservationId").asText())
     ||!str(r.storeId).equals(p.path("storeId").asText())||!"AUTO".equals(p.path("confirmMode").asText())
     ||!r.confirmedAt.atOffset(ZoneOffset.UTC).isEqual(OffsetDateTime.parse(p.path("confirmedAt").asText()))
     ||!r.confirmDeadline.atOffset(ZoneOffset.UTC).isEqual(OffsetDateTime.parse(p.path("confirmDeadline").asText())))throw bad();IDS.fromApi(p.path("eventId").asText());
   }catch(RuntimeException e){throw bad();}catch(Exception e){throw bad();}
  }else throw bad();
 }
 public Location requireRescheduleSource(String id,String reservation,String store,String user,QueryContext q){
  var r=locked(id,store,q);if(!str(r.userId).equals(user)||!str(r.reservationId).equals(reservation)||r.rescheduleCount==null||r.rescheduleCount!=0
   ||!Set.of("PENDING_SERVICE","PENDING_CONFIRM").contains(r.orderStage)||!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus))throw bad();return location(r);
 }
 public void requireRescheduleCommitted(String id,String change,String fence,OffsetDateTime at,QueryContext q){
  system(q);var p=new OrderRescheduleStore(source).mapper().recordFor(IDS.fromApi(id));if(p==null)throw bad();var r=locked(id,str(p.storeId),q);
  OrderConfirmEpoch.require(source,r);if(!change.equals(str(p.id))||!fence.equals(str(p.verificationFenceId))||!at.isEqual(p.rescheduledAt.atOffset(ZoneOffset.UTC)))throw bad();
 }
 private static Location location(Row r){return new Location(str(r.id),str(r.userId),str(r.merchantId),str(r.storeId),str(r.reservationId));}
 private static String str(Long l){if(l==null)throw bad();return Long.toString(l);}
 private static String time(OffsetDateTime t){return t==null?null:t.withOffsetSameInstant(ZoneOffset.UTC).toString();}
 private static void system(QueryContext q){if(q==null||q.operatorType()!=OperatorType.SYSTEM||q.operatorId()!=null)throw error(CommonApiCodes.FORBIDDEN);}
 private static ApiException error(String c){return new ApiException(c,"Credential order facts unavailable");}
 private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
}
