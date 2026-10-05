package com.petplatform.schedule.biz.apiimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.schedule.api.command.ReservationSwapApi;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.*;
import com.petplatform.schedule.api.protection.*;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleSwapMapper;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;

public final class ReservationSwapApiImpl implements ReservationSwapApi {
 private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
 private static final ObjectMapper JSON=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 private final DataSource source; private final SnowflakeIdGenerator ids;
 private final ScheduleCapacityGuardApi guard; private final ScheduleProtectionFactsApi facts;
 private final ScheduleCapacityProofApiImpl proof; private final OrderProtectionFactsApi orders;
 private final ScheduleSwapMapper mapper;
 private final com.petplatform.order.api.query.OrderRescheduleCommitApi commit;
 public ReservationSwapApiImpl(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
   ScheduleProtectionFactsApi facts,ScheduleCapacityProofApiImpl proof,OrderProtectionFactsApi orders,com.petplatform.order.api.query.OrderRescheduleCommitApi commit){
  this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);
  this.facts=Objects.requireNonNull(facts);this.proof=Objects.requireNonNull(proof);this.orders=Objects.requireNonNull(orders);this.commit=Objects.requireNonNull(commit);
  mapper=ScheduleMybatis.template(source).getMapper(ScheduleSwapMapper.class);
  commandMapper=ScheduleMybatis.template(source).getMapper(com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper.class);
 }
 private final com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper commandMapper;
 public Result swap(Command c){
  if(c==null||c.context()==null||c.context().operatorType()!=OperatorType.USER||!Objects.equals(c.userId(),c.context().operatorId()))throw error(CommonApiCodes.FORBIDDEN);
  guard.requireHeld(c.storeId(),source);
  try{
   var q=new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null);
   var snapshot=facts.readStore(c.storeId(),q);
   if(snapshot==null||!snapshot.complete())throw bad();
   var old=snapshot.reservations().stream().filter(r->c.reservationId().equals(r.reservationId())).findFirst().orElseThrow(ReservationSwapApiImpl::bad);
   if(!"CONFIRMED".equals(old.status())||!c.orderId().equals(old.orderId())||!c.userId().equals(old.userId())||!c.merchantId().equals(old.merchantId())
    ||!c.serviceId().equals(old.serviceId())||!c.fulfillmentType().equals(old.fulfillmentType())||!c.oldStart().isEqual(old.startAt())||!c.oldEnd().isEqual(old.endAt()))throw bad();
   var original=snapshot.claims().stream().filter(x->c.reservationId().equals(x.reservationId())).sorted(Comparator.comparing(ClaimFact::kind)).toList();
   if("IN_STORE".equals(c.fulfillmentType())&&old.startAt().isEqual(c.appointmentStart())&&old.endAt().isEqual(c.appointmentEnd()))throw error(CommonApiCodes.CONFLICT);
   if("PICKUP_DELIVERY".equals(c.fulfillmentType())){
    var selected=snapshot.windows().stream().filter(w->w.windowId().equals(c.selectedPickupWindowId())||w.windowId().equals(c.selectedReturnWindowId())).toList();
    if(selected.size()==2&&original.size()==2&&selected.stream().allMatch(w->original.stream().anyMatch(o->o.kind().equals(w.kind())&&o.startAt().isEqual(w.startAt())&&o.endAt().isEqual(w.endAt())))
      &&Objects.equals(old.pickupStartAt(),c.pickupStart())&&Objects.equals(old.returnStartAt(),c.returnStart()))throw error(CommonApiCodes.CONFLICT);
   }
   var plan=proof.prepareForSwap(new CapacityProofQuery(c.storeId(),c.serviceId(),c.fulfillmentType(),c.appointmentStart(),c.appointmentEnd(),
    c.selectedGeneralWindowId(),c.selectedPickupWindowId(),c.selectedReturnWindowId(),q),c.reservationId());
   if(plan.claims().size()!=original.size())throw bad();
   for(var claim:plan.claims())if(!c.merchantId().equals(claim.merchantId()))throw bad();
   var start=plan.claims().stream().map(x->x.startAt()).min(Comparator.naturalOrder()).orElseThrow();
   var end=plan.claims().stream().map(x->x.endAt()).max(Comparator.naturalOrder()).orElseThrow();
   boolean pickup="PICKUP_DELIVERY".equals(c.fulfillmentType());
   OffsetDateTime returning=pickup?plan.claims().stream().filter(x->"RETURN".equals(x.kind())).findFirst().orElseThrow().startAt():null;
   if(pickup&&(!start.isEqual(c.pickupStart())||!returning.isEqual(c.returnStart())))throw error(CommonApiCodes.CONFLICT);
   if(!start.isAfter(c.rescheduledAt()))throw error("ORDER_RESCHEDULE_AFTER_START");
   boolean unchanged=plan.claims().stream().allMatch(n->original.stream().anyMatch(o->o.kind().equals(n.kind())&&o.startAt().isEqual(n.startAt())&&o.endAt().isEqual(n.endAt())));
   if(unchanged)throw error(CommonApiCodes.CONFLICT);
   long version=Long.parseLong(old.version()),change=ids.nextId();if(change<=0||version<0||version==Long.MAX_VALUE)throw bad();
   var parent=mapper.parent(IDS.fromApi(c.reservationId()));if(parent==null)throw bad();
   var oldSnapshot=values("reservation",old,"claims",original,"capacity",parent);
   List<ClaimFact> current=new ArrayList<>();
   for(var n:plan.claims()){
    var o=original.stream().filter(x->x.kind().equals(n.kind())).findFirst().orElseThrow(ReservationSwapApiImpl::bad);
    if(mapper.claim(values("id",IDS.fromApi(o.claimId()),"reservation",IDS.fromApi(c.reservationId()),"window",IDS.fromApi(n.windowId()),"kind",n.kind(),"start",utc(n.startAt()),"end",utc(n.endAt())))!=1)throw bad();
    current.add(new ClaimFact(o.claimId(),c.reservationId(),n.windowId(),c.storeId(),c.serviceId(),n.kind(),n.startAt(),n.endAt()));
   }
   int capacity=Math.min(plan.configuredCapacity(),plan.qualifiedStaffCount());
   if(mapper.swap(values("id",IDS.fromApi(c.reservationId()),"orderId",IDS.fromApi(c.orderId()),"version",version,"start",utc(start),"end",utc(end),
    "pickup",pickup?utc(start):null,"returning",utc(returning),"capacity",capacity,"qualified",plan.qualifiedStaffCount(),"now",utc(c.rescheduledAt())))!=1)throw bad();
   var updated=new ReservationFact(old.reservationId(),old.orderId(),old.userId(),old.merchantId(),old.storeId(),old.serviceId(),old.fulfillmentType(),start,end,pickup?start:null,returning,"CONFIRMED",Long.toString(version+1));
   var newSnapshot=values("reservation",updated,"claims",current,"capacity",values("id",c.reservationId(),"capacity_snapshot",capacity,"qualified_staff_count_snapshot",plan.qualifiedStaffCount()));
   // Snapshot IDs are all public strings, including the diagnostic parent identity.
   parent.put("id",c.reservationId());
   if(mapper.history(values("id",change,"reservation",IDS.fromApi(c.reservationId()),"orderId",IDS.fromApi(c.orderId()),"storeId",IDS.fromApi(c.storeId()),
     "command",IDS.fromApi(c.commandId()),"version",version,"old",json(oldSnapshot),"new",json(newSnapshot),"now",utc(c.rescheduledAt())))!=1)throw bad();
   // The swap moves confirmed occupancy between windows: both sides re-derive in this
   // transaction (Contract53 §3, 2026-10-05 SOLD_OUT ruling).
   var movedWindows=new java.util.LinkedHashSet<Long>();
   for(var o:original)movedWindows.add(IDS.fromApi(o.windowId()));
   for(var n:plan.claims())movedWindows.add(IDS.fromApi(n.windowId()));
   WindowSoldOutDeriver.rederive(c.storeId(),movedWindows,q,facts,commandMapper::setWindowDerivedStatus,utc(c.rescheduledAt()));
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    public void beforeCommit(boolean readOnly){
     guard.requireHeld(c.storeId(),source);
     commit.requireCommitted(c.orderId(),c.storeId(),c.reservationId(),c.commandId(),Long.toString(change),version+1,q);
     var finalFacts=facts.readStore(c.storeId(),q);
     var actual=finalFacts.reservations().stream().filter(x->c.reservationId().equals(x.reservationId())).findFirst().orElseThrow(ReservationSwapApiImpl::bad);
     if(!updated.equals(actual)||!new HashSet<>(current).equals(new HashSet<>(finalFacts.claims().stream().filter(x->c.reservationId().equals(x.reservationId())).toList())))throw bad();
     var linked=orders.getByReservations(c.storeId(),List.of(c.reservationId()),q);
     if(readOnly||linked==null||!linked.complete()||linked.items().size()!=1)throw bad();
     var o=linked.items().getFirst();
     if(!c.orderId().equals(o.orderId())||!c.userId().equals(o.userId())||!c.merchantId().equals(o.merchantId())
       ||!c.serviceId().equals(o.serviceId())||!c.fulfillmentType().equals(o.fulfillmentType()))throw bad();
    }
   });
   return new Result(Long.toString(change),c.reservationId(),version,version+1,old.pickupStartAt(),old.returnStartAt(),start,end,pickup?start:null,returning);
  }catch(RuntimeException failure){
   if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();
   if(failure instanceof ApiException known)throw known;throw bad();
  }
 }
 private static LocalDateTime utc(OffsetDateTime t){return t==null?null:t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
 private static Map<String,Object> values(Object...pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
 private static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw bad();}}
 private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
 private static ApiException error(String c){return new ApiException(c,"Reservation swap cannot be applied");}
}
