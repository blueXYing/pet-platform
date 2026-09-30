package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.order.api.command.OrderRefundApplicationApi;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.order.biz.application.OrderAutoConfirmTaskSpec;
import com.petplatform.order.biz.application.OrderConfirmEpoch;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper.Row;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderRefundApplicationMapper;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderRefundApplicationMapper.Proof;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi.ApplicationFact;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.*;

/** Ordinary refunds use normal-payment proof, including after a genuine successful verification. */
public final class OrderRefundApplicationApiImpl implements OrderRefundApplicationApi, OrderRefundApplicationFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final SnowflakeIdGenerator ids;
    private final PaymentSuccessFactsApi payments;
    private final RefundOrderFactsApi refunds;
    private final ReservationConfirmApi reservations;
    private final ScheduleProtectionFactsApi schedule;
    private final Supplier<RefundApplicationApprovalFactsApi> applications;
    private final Supplier<com.petplatform.aftersale.api.query.AfterSaleCaseFactsApi> aftersales;
    private final OrderPaymentStore paid;
    private final OrderAutoConfirmStore orders;
    private final OrderRefundApplicationMapper db;
    private final Map<String,Entry> tokens=new ConcurrentHashMap<>();
    private static final class Entry {
        final Permit permit; final Object transaction; boolean committed;
        Entry(Permit permit,Object transaction){this.permit=permit;this.transaction=transaction;}
    }

    public OrderRefundApplicationApiImpl(DataSource source,ScheduleCapacityGuardApi guard,SnowflakeIdGenerator ids,
            PaymentSuccessFactsApi payments,RefundOrderFactsApi refunds,ReservationConfirmApi reservations,
            ScheduleProtectionFactsApi schedule,Supplier<RefundApplicationApprovalFactsApi> applications) {
        this(source,guard,ids,payments,refunds,reservations,schedule,applications,null);
    }
    public OrderRefundApplicationApiImpl(DataSource source,ScheduleCapacityGuardApi guard,SnowflakeIdGenerator ids,
            PaymentSuccessFactsApi payments,RefundOrderFactsApi refunds,ReservationConfirmApi reservations,
            ScheduleProtectionFactsApi schedule,Supplier<RefundApplicationApprovalFactsApi> applications,
            Supplier<com.petplatform.aftersale.api.query.AfterSaleCaseFactsApi> aftersales) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);this.ids=Objects.requireNonNull(ids);
        this.payments=Objects.requireNonNull(payments);this.refunds=Objects.requireNonNull(refunds);
        this.reservations=Objects.requireNonNull(reservations);this.schedule=Objects.requireNonNull(schedule);
        this.applications=Objects.requireNonNull(applications);paid=new OrderPaymentStore(source);orders=new OrderAutoConfirmStore(source);
        this.aftersales=aftersales;
        db=new OrderRefundApplicationStore(source).mapper();
    }

    public Location locate(String order,QueryContext q) {
        system(q);IDS.fromApi(order);paid.sessionDefaults();
        var row=new OrderCredentialStore(source).locate(IDS.fromApi(order));
        if(row==null)throw error(CommonApiCodes.FORBIDDEN);
        return new Location(str(row.id),str(row.userId),str(row.merchantId),str(row.storeId),str(row.reservationId));
    }

    public Fact requireEligible(String order,String store,QueryContext q,DataSource txSource) {
        return safe(()->{scope(store,txSource);system(q);return normal(order,store,q,false);});
    }

    public void bindApplication(String application,String order,String store,CommandContext context,DataSource txSource) {
        safe(()->{
            scope(store,txSource);user(context);IDS.fromApi(application);var q=query(context);
            var f=normal(order,store,q,false);if(!context.operatorId().equals(f.location().userId()))throw error(CommonApiCodes.FORBIDDEN);
            if(f.currentApplicationId()!=null){
                if(!"REJECTED".equals(f.currentApplicationStatus()))throw error(CommonApiCodes.CONFLICT);
                requireApplicationBound(f.currentApplicationId(),order,store,q,source);
            }
            var a=applications.get().requireApplication(application,store,q);same(a,f);
            if(!"PENDING_MERCHANT".equals(a.status())||a.version()!=0||a.decisionId()!=null||a.refundOrderId()!=null
                    ||a.createdAt()==null||a.merchantDeadline()==null||!a.createdAt().plusHours(24).isEqual(a.merchantDeadline())
                    ||!"VERIFIED".equals(f.verificationStatus())&&a.createdAt().isBefore(f.appointmentStart())
                    ||db.application(IDS.fromApi(application))!=null)throw bad();
            var v=sourceValues(a);v.putAll(values("oldVersion",Long.parseLong(f.orderVersion()),"version",Math.addExact(Long.parseLong(f.orderVersion()),1)));
            one(db.insertApplication(v));one(db.bind(v));
            audit(order,f.currentApplicationStatus(),"PENDING_MERCHANT","REFUND_APPLICATION_CREATED",application,a.createdAt(),context);
            before(()->requireApplicationBound(application,order,store,q,source));return null;
        });
    }

    public void requireApplicationBound(String application,String order,String store,QueryContext q,DataSource txSource) {
        safe(()->{
            scope(store,txSource);system(q);var p=proof(application,order,store);
            var a=applications.get().requireApplication(application,store,q);sameProof(p,a);
            var r=orders.lock(IDS.fromApi(order));var c=db.current(IDS.fromApi(order));
            if(r==null||c==null||!store.equals(str(r.storeId))||r.version<p.appliedOrderVersion)throw bad();
            // Old REJECTED facts remain valid history, but may never replace a newer current pointer.
            if(Objects.equals(c.currentRefundApplicationId,p.applicationId)){
                if(!Objects.equals(c.refundApplicationStatus,p.applicationStatus))throw bad();
            }else if(!"REJECTED".equals(p.applicationStatus))throw bad();
            return null;
        });
    }

    public void recordDecision(String application,String decision,String order,String store,CommandContext context,DataSource txSource) {
        safe(()->{
            scope(store,txSource);command(context);var q=query(context);var f=normal(order,store,q,false);
            var p=proof(application,order,store);var d=applications.get().requireDecision(application,decision,store,q);same(d.application(),f);
            if(!application.equals(f.currentApplicationId())||!"PENDING_MERCHANT".equals(f.currentApplicationStatus())
                    ||!"PENDING_MERCHANT".equals(p.applicationStatus)||p.decisionId!=null||p.applicationVersion==null
                    ||d.application().version()!=Math.addExact(p.applicationVersion,1)||!decision.equals(d.application().decisionId())
                    ||!Set.of("APPROVED","AUTO_APPROVED","REJECTED").contains(d.status())
                    ||!d.status().equals(d.application().status())||d.decidedAt()==null)throw bad();
            if("AUTO_APPROVED".equals(d.status())){
                if(context.operatorType()!=OperatorType.SYSTEM||!"SYSTEM".equals(d.operatorType())||d.operatorId()!=null
                        ||d.decidedAt().isBefore(d.application().merchantDeadline()))throw bad();
            }else if(context.operatorType()!=OperatorType.USER||!"USER".equals(d.operatorType())
                    ||!context.operatorId().equals(d.operatorId())||!d.decidedAt().isBefore(d.application().merchantDeadline()))throw bad();
            var v=values("application",IDS.fromApi(application),"order",IDS.fromApi(order),"store",IDS.fromApi(store),
                "decision",IDS.fromApi(decision),"status",d.status(),"at",utc(d.decidedAt()),"oldVersion",Long.parseLong(f.orderVersion()),
                "applicationVersion",d.application().version(),"oldApplicationVersion",p.applicationVersion);
            one(db.decideProof(v));one(db.decideOrder(v));
            audit(order,"PENDING_MERCHANT",d.status(),"REFUND_APPLICATION_DECIDED",decision,d.decidedAt(),context);
            before(()->requireDecisionRecorded(application,decision,order,store,q,source));return null;
        });
    }

    public void requireDecisionRecorded(String application,String decision,String order,String store,QueryContext q,DataSource txSource) {
        safe(()->{
            requireApplicationBound(application,order,store,q,txSource);var p=proof(application,order,store);
            var d=applications.get().requireDecision(application,decision,store,q);
            if(!decision.equals(str(p.decisionId))||!d.status().equals(p.applicationStatus)||p.decidedAt==null
                    ||!d.decidedAt().isEqual(offset(p.decidedAt)))throw bad();return null;
        });
    }

    public Permit acquireCreate(String application,String decision,String order,String store,String commandId,CommandContext context,DataSource txSource) {
        return safe(()->{
            scope(store,txSource);command(context);if(context.operatorType()!=OperatorType.SYSTEM
                    ||!"ASYNC_TASK".equals(context.source())||!("TASK:REFUND_APPLICATION_CREATE:"+application).equals(context.requestId()))throw bad();IDS.fromApi(commandId);
            var q=query(context);var f=normal(order,store,q,false);
            requireDecisionRecorded(application,decision,order,store,q,source);
            var a=applications.get().requireApproved(application,decision,store,q);same(a.application(),f);
            if(!commandId.equals(a.commandId())||!application.equals(f.currentApplicationId())||!Set.of("APPROVED","AUTO_APPROVED").contains(f.currentApplicationStatus())
                    ||db.committed(IDS.fromApi(order))!=null||new OrderVerificationStore(source).mapper().active(IDS.fromApi(order))!=0)throw bad();
            Permit p=new Permit(UUID.randomUUID().toString(),f,application,decision,commandId,context);
            Entry e=new Entry(p,TransactionSynchronizationManager.getResource(source));tokens.put(p.token(),e);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                public void beforeCommit(boolean readOnly){scope(store,source);if(readOnly||!e.committed)throw bad();}
                public void afterCompletion(int status){tokens.remove(p.token(),e);}
            });
            one(db.acquire(values("id",next(),"order",IDS.fromApi(order),"token",p.token())));return p;
        });
    }

    public void commitCreated(String token,String order,String store,String refund,OffsetDateTime at,DataSource txSource) {
        safe(()->{
            scope(store,txSource);IDS.fromApi(refund);PublicContractChecks.requireMillisecondPrecision(at);Entry e=tokens.get(token);
            if(e==null||e.committed||e.transaction!=TransactionSynchronizationManager.getResource(source)
                    ||!order.equals(e.permit.fact().location().orderId())||!store.equals(e.permit.fact().location().storeId()))throw bad();
            var p=e.permit;var q=query(p.context());var current=db.current(IDS.fromApi(order));
            if(current==null||current.refundOrderId!=null||!p.fact().orderVersion().equals(str(current.version)))throw bad();
            var created=applications.get().requireCreated(p.applicationId(),p.decisionId(),refund,store,q);same(created.approval().application(),p.fact());
            if(!at.isEqual(created.createdAt())||!refund.equals(created.refundOrderId()))throw bad();
            var v=values("order",IDS.fromApi(order),"store",IDS.fromApi(store),"application",IDS.fromApi(p.applicationId()),"decision",IDS.fromApi(p.decisionId()),
                "refund",IDS.fromApi(refund),"oldVersion",current.version,"version",Math.addExact(current.version,1),"at",utc(at),"token",token);
            one(db.createOrder(v));one(db.insertCommit(v));one(db.commitGuard(v));e.committed=true;
            audit(order,current.refundApplicationStatus,"CREATED","REFUND_ORDER_CREATED",refund,at,p.context());
            before(()->requireCreated(order,store,refund,source));return null;
        });
    }

    public void requireCreated(String order,String store,String refund,DataSource txSource) {
        safe(()->{
            scope(store,txSource);var q=new QueryContext("order-refund-commit",OperatorType.SYSTEM,null);
            var c=db.committed(IDS.fromApi(order));var r=orders.lock(IDS.fromApi(order));var state=db.current(IDS.fromApi(order));
            if(c==null||r==null||state==null||!store.equals(str(c.storeId))||!store.equals(str(r.storeId))||!refund.equals(str(c.refundOrderId))
                    ||!refund.equals(str(r.refundOrderId))||!Objects.equals(state.currentRefundApplicationId,c.applicationId)
                    ||r.version<c.orderVersion||!Set.of("PENDING_SERVICE","COMPLETED").contains(r.orderStage))throw bad();
            requireDecisionRecorded(str(c.applicationId),str(c.decisionId),order,store,q,source);
            var f=applications.get().requireCreated(str(c.applicationId),str(c.decisionId),refund,store,q);
            if(c.createdAt==null||!f.createdAt().isEqual(offset(c.createdAt)))throw bad();
            var actual=refunds.findByOrder(order,store,q);
            if(actual==null||actual.refunds().size()!=1||!refund.equals(actual.refunds().getFirst().refundOrderId()))throw bad();return null;
        });
    }

    public OrderRefundOriginFact requireApprovedRefund(String order,String payment,String store,QueryContext q) {
        return safe(()->{
            scope(store,source);system(q);var c=db.committed(IDS.fromApi(order));if(c==null)throw bad();
            requireCreated(order,store,str(c.refundOrderId),source);var p=proof(str(c.applicationId),order,store);
            var a=applications.get().requireApproved(str(c.applicationId),str(c.decisionId),store,q);
            if(!payment.equals(str(p.paymentId)))throw bad();
            // This is the immutable normal-payment source; refunds do not rewrite its paidAt or verification history.
            var original=paid.lockResult(IDS.fromApi(order));var row=orders.lock(IDS.fromApi(order));
            if(original==null||!"NORMAL".equals(original.resultType())||original.paymentId()!=p.paymentId
                    ||original.sourceEventId()!=p.paymentSuccessEventId||!Objects.equals(original.channelTradeNo(),p.channelTradeNo)
                    ||original.paidAmount().compareTo(p.paidAmount)!=0||!original.paidAt().isEqual(offset(p.paidAt))
                    ||row.payAmount.compareTo(p.paidAmount)!=0||!str(row.userId).equals(str(p.userId))||!str(row.merchantId).equals(str(p.merchantId))
                    ||!store.equals(str(row.storeId))||!Objects.equals(row.reservationId,p.reservationId)
                    ||row.paidAt==null||!row.paidAt.equals(p.paidAt)||!"PAID".equals(row.paymentStatus)
                    ||row.canceledAt!=null||row.cancelReason!=null)throw bad();
            return new OrderRefundOriginFact(order,store,str(p.merchantId),str(p.userId),str(p.reservationId),payment,str(p.paymentSuccessEventId),
                p.channelTradeNo,p.paidAmount,offset(p.paidAt),a.sourceType(),null,str(c.refundOrderId),str(c.applicationId),str(c.decisionId));
        });
    }

    private Fact normal(String order,String store,QueryContext q,boolean releasedAllowed) {
        return normal(order,store,q,releasedAllowed,false);
    }
    /** Shared ORDER-owned validation; AFS owns its window/current-workflow admission. */
    Fact requireNormalForAfterSale(String order,String store,QueryContext q,DataSource txSource) {
        return safe(()->{scope(store,txSource);system(q);return normal(order,store,q,false,true);});
    }
    private Fact normal(String order,String store,QueryContext q,boolean releasedAllowed,boolean forAftersale) {
        var r=orders.lock(IDS.fromApi(order));var state=db.current(IDS.fromApi(order));
        if(r==null||state==null||!store.equals(str(r.storeId)))throw bad();
        var refund=refunds.findByOrder(order,store,q);if(refund==null)throw bad();
        if(r.refundOrderId!=null||refund.exists())throw error("REFUND_ORDER_ALREADY_EXISTS");
        boolean verified="VERIFIED".equals(r.verificationStatus)&&"COMPLETED".equals(r.orderStage);
        boolean pending="UNVERIFIED".equals(r.verificationStatus)&&"PENDING_SERVICE".equals(r.orderStage);
        if(!verified&&!pending)throw error("REFUND_NOT_ELIGIBLE");
        if(!"PAID".equals(r.paymentStatus)||r.canceledAt!=null||r.cancelReason!=null||r.payAmount==null||r.payAmount.signum()<=0
                ||r.refundedAmount==null||r.refundedAmount.signum()!=0||r.appointmentStartAt==null||r.appointmentEndAt==null||r.confirmedAt==null)throw bad();
        if(!verified&&paid.databaseNow().isBefore(offset(r.appointmentStartAt)))throw error("REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED");
        if(verified){
            var v=new OrderVerificationStore(source).mapper().committed(r.id);
            if(v==null||!Objects.equals(v.storeId,r.storeId)||v.orderVersion==null||v.orderVersion>r.version||v.verifiedAt==null
                    ||!v.verifiedAt.equals(state.verifiedAt)||!v.verifiedAt.equals(state.completedAt))throw bad();
        }else if(state.verifiedAt!=null||state.completedAt!=null)throw bad();
        var afsProvider=aftersales==null?null:aftersales.get();
        if(afsProvider!=null&&!forAftersale){
            var af=afsProvider.requireCurrent(order,store,nullable(r.currentAftersaleId),q,source);
            if(af==null||!order.equals(af.orderId())||!store.equals(af.storeId())
                    ||!Objects.equals(nullable(r.currentAftersaleId),af.afterSaleId())
                    ||af.afterSaleId()!=null&&(!str(r.userId).equals(af.userId())||!str(r.merchantId).equals(af.merchantId())||!Objects.equals(state.aftersaleStatus,af.status()))
                    ||af.afterSaleId()==null&&state.aftersaleStatus!=null&&!"NONE".equals(state.aftersaleStatus))throw bad();
            if(!forAftersale&&af.active())throw error("REFUND_APPLICATION_BLOCKED_BY_AFTERSALE");
        }else if(!forAftersale&&r.currentAftersaleId!=null){
            var historical=new OrderVerificationStore(source).mapper().committed(r.id);
            if(!verified||historical==null||!Objects.equals(historical.aftersaleId,r.currentAftersaleId)
                    ||!Objects.equals(historical.aftersaleStatus,state.aftersaleStatus)
                    ||!"INVALIDATED".equals(state.aftersaleStatus))throw bad();
        }
        if((state.currentRefundApplicationId==null)!=(state.refundApplicationStatus==null)
                ||state.refundApplicationStatus!=null&&!Set.of("PENDING_MERCHANT","APPROVED","AUTO_APPROVED","REJECTED").contains(state.refundApplicationStatus))throw bad();
        var original=paid.lockResult(r.id);if(original==null||!"NORMAL".equals(original.resultType()))throw bad();
        var p=payments.requireSucceeded(Long.toString(original.paymentId()),order,store,q);
        if(p==null||!order.equals(p.orderId())||!store.equals(p.storeId())||!str(r.userId).equals(p.userId())||!str(r.merchantId).equals(p.merchantId())
                ||!Long.toString(original.paymentId()).equals(p.paymentId())||!Long.toString(original.sourceEventId()).equals(p.successEventId())
                ||!Objects.equals(original.channelTradeNo(),p.channelTradeNo())||p.channelTradeNo()==null||p.channelTradeNo().isBlank()
                ||p.paidAmount()==null||original.paidAmount()==null||r.payAmount.compareTo(p.paidAmount())!=0||original.paidAmount().compareTo(p.paidAmount())!=0
                ||p.paidAt()==null||original.paidAt()==null||r.paidAt==null||!original.paidAt().isEqual(p.paidAt())||!offset(r.paidAt).isEqual(p.paidAt())||!"CNY".equals(p.currency()))throw bad();
        OrderConfirmEpoch.requireSchedule(source,r,reservations,releasedAllowed,q);confirmation(r);
        reservations.assertConfirmed(order,str(r.reservationId),store,q);
        var sf=schedule.readStore(store,q);if(sf==null||!sf.complete())throw bad();
        var matches=sf.reservations().stream().filter(x->str(r.reservationId).equals(x.reservationId())).toList();
        if(matches.size()!=1)throw bad();var reservation=matches.getFirst();
        if(!order.equals(reservation.orderId())||!str(r.userId).equals(reservation.userId())||!str(r.merchantId).equals(reservation.merchantId())
                ||!str(r.serviceId).equals(reservation.serviceId())||!r.fulfillmentType.equals(reservation.fulfillmentType())
                ||!offset(r.appointmentStartAt).isEqual(reservation.startAt())||!offset(r.appointmentEndAt).isEqual(reservation.endAt()))throw bad();
        return new Fact(new Location(order,str(r.userId),str(r.merchantId),store,str(r.reservationId)),str(r.version),offset(r.appointmentStartAt),r.verificationStatus,
            p.paymentId(),p.successEventId(),p.channelTradeNo(),p.paidAmount(),p.paidAt(),nullable(state.currentRefundApplicationId),state.refundApplicationStatus);
    }

    private void confirmation(Row r) {
        if("MERCHANT".equals(r.confirmMode)){
            var d=new OrderMerchantStore(source).mapper().decision(r.id,r.rescheduleCount);
            if(d==null||!"CONFIRM".equals(d.action)||!Objects.equals(d.orderId,r.id)||!Objects.equals(d.storeId,r.storeId)
                    ||!Objects.equals(d.decidedAt,r.confirmedAt)||d.eventId==null||d.eventId<=0||d.commandId==null||d.commandId<=0||d.refundOrderId!=null)throw bad();
        }else if("AUTO".equals(r.confirmMode)){
            var records=orders.proofs(r.id,"TASK:"+OrderAutoConfirmTaskSpec.key(str(r.id),r.rescheduleCount));if(records.size()!=1)throw bad();
            try{var p=new ObjectMapper().readTree(records.getFirst());
                if(!p.isObject()||p.size()!=8||!p.path("confirmRound").isIntegralNumber()||p.path("confirmRound").asInt(-1)!=r.rescheduleCount
                        ||!str(r.id).equals(p.path("orderId").asText())||!str(r.reservationId).equals(p.path("reservationId").asText())
                        ||!str(r.storeId).equals(p.path("storeId").asText())||!"AUTO".equals(p.path("confirmMode").asText())
                        ||!offset(r.confirmedAt).isEqual(OffsetDateTime.parse(p.path("confirmedAt").asText()))
                        ||!offset(r.confirmDeadline).isEqual(OffsetDateTime.parse(p.path("confirmDeadline").asText())))throw bad();IDS.fromApi(p.path("eventId").asText());
            }catch(Exception invalid){throw bad();}
        }else throw bad();
    }

    private Proof proof(String application,String order,String store){
        var p=db.application(IDS.fromApi(application));
        if(p==null||!order.equals(str(p.orderId))||!store.equals(str(p.storeId)))throw bad();return p;
    }
    private static void same(ApplicationFact a,Fact f) {
        var l=f.location();
        if(a==null||!l.orderId().equals(a.orderId())||!l.storeId().equals(a.storeId())||!l.merchantId().equals(a.merchantId())
                ||!l.userId().equals(a.userId())||!l.reservationId().equals(a.reservationId())||!f.paymentId().equals(a.paymentId())
                ||!f.paymentSuccessEventId().equals(a.paymentSuccessEventId())||!f.channelTradeNo().equals(a.channelTradeNo())
                ||a.paidAmount()==null||f.paidAmount().compareTo(a.paidAmount())!=0||a.paidAt()==null||!f.paidAt().isEqual(a.paidAt()))throw bad();
    }
    private static void sameProof(Proof p,ApplicationFact a) {
        if(a==null||!str(p.applicationId).equals(a.applicationId())||!str(p.orderId).equals(a.orderId())||!str(p.storeId).equals(a.storeId())
                ||!str(p.merchantId).equals(a.merchantId())||!str(p.userId).equals(a.userId())||!str(p.reservationId).equals(a.reservationId())
                ||!str(p.paymentId).equals(a.paymentId())||!str(p.paymentSuccessEventId).equals(a.paymentSuccessEventId())
                ||!Objects.equals(p.channelTradeNo,a.channelTradeNo())||a.paidAmount()==null||p.paidAmount.compareTo(a.paidAmount())!=0
                ||!offset(p.paidAt).isEqual(a.paidAt())||!offset(p.createdAt).isEqual(a.createdAt())||!offset(p.merchantDeadline).isEqual(a.merchantDeadline())
                ||!Objects.equals(p.applicationStatus,a.status())||p.applicationVersion==null||p.applicationVersion!=a.version()
                ||!Objects.equals(nullable(p.decisionId),a.decisionId()))throw bad();
    }
    private static Map<String,Object> sourceValues(ApplicationFact a){
        return values("application",IDS.fromApi(a.applicationId()),"order",IDS.fromApi(a.orderId()),"store",IDS.fromApi(a.storeId()),
            "merchant",IDS.fromApi(a.merchantId()),"user",IDS.fromApi(a.userId()),"reservation",IDS.fromApi(a.reservationId()),
            "payment",IDS.fromApi(a.paymentId()),"paymentEvent",IDS.fromApi(a.paymentSuccessEventId()),"trade",a.channelTradeNo(),
            "amount",a.paidAmount(),"paidAt",utc(a.paidAt()),"at",utc(a.createdAt()),"deadline",utc(a.merchantDeadline()));
    }
    private void audit(String order,String from,String status,String type,String reference,OffsetDateTime at,CommandContext c){
        one(db.log(values("log",next(),"order",IDS.fromApi(order),"from",from,"status",status,"eventType",type,
            "actorType",c.operatorType().name(),"actor",c.operatorId()==null?null:IDS.fromApi(c.operatorId()),"requestId",c.requestId(),"remark",reference,"at",utc(at))));
    }
    private void before(Runnable work){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly)throw bad();work.run();}});}
    private void scope(String store,DataSource txSource){
        if(source!=txSource||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)
                ||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);
    }
    private <T>T safe(Supplier<T> action){try{return action.get();}catch(RuntimeException ex){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(ex instanceof ApiException a)throw a;var failure=bad();failure.initCause(ex);throw failure;}}
    private long next(){long n=ids.nextId();if(n<=0)throw bad();return n;}
    private static QueryContext query(CommandContext c){return new QueryContext(c.traceId(),OperatorType.SYSTEM,null);}
    private static void system(QueryContext c){if(c==null||c.operatorType()!=OperatorType.SYSTEM||c.operatorId()!=null)throw error(CommonApiCodes.FORBIDDEN);}
    private static void user(CommandContext c){command(c);if(c.operatorType()!=OperatorType.USER)throw error(CommonApiCodes.FORBIDDEN);}
    private static void command(CommandContext c){if(c==null||c.operatorType()==null)throw bad();if(c.operatorType()==OperatorType.USER){IDS.fromApi(c.operatorId());PublicContractChecks.requireTerminalRequestId(c.requestId());}else if(c.operatorType()!=OperatorType.SYSTEM||c.operatorId()!=null||c.requestId()==null||c.requestId().isBlank())throw bad();}
    private static void one(int n){if(n!=1)throw bad();}
    private static String str(Long n){if(n==null||n<=0)throw bad();return Long.toString(n);}
    private static String nullable(Long n){return n==null?null:str(n);}
    private static OffsetDateTime offset(LocalDateTime t){if(t==null)throw bad();return t.atOffset(ZoneOffset.UTC);}
    private static LocalDateTime utc(OffsetDateTime t){PublicContractChecks.requireMillisecondPrecision(t);return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
    private static Map<String,Object> values(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}
    private static ApiException error(String code){return new ApiException(code,"Ordinary refund order facts unavailable");}
}
