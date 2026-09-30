package com.petplatform.refund.biz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.order.api.command.OrderRefundApplicationApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.command.*;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
import com.petplatform.refund.biz.infrastructure.persistence.*;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundApplicationMapper;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.petplatform.refund.biz.application.RefundApplicationPorts.*;

/** Ordinary refunds retain approval independently of recoverable business-refund creation. */
public final class RefundApplicationService implements RefundApplicationCommandApi,RefundApplicationTimeoutApi,RefundApplicationApprovalFactsApi,com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi {
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();private static final ObjectMapper JSON=new ObjectMapper();
    private final DataSource source;private final SnowflakeIdGenerator ids;private final ScheduleCapacityGuardApi guard;
    private final OrderRefundApplicationApi orders;private final PaymentSuccessFactsApi payments;private final IntegrationEventPublisher outbox;
    private final SessionAuthority sessions;private final OwnerAuthority owners;private final ReasonPolicy reasons;private final Moderation moderation;
    private final Protection protection;private final TaskRecovery recovery;private final RefundApplicationMapper db;private final RefundExecutionStore execution;
    private final JdbcAsyncTaskSubmitter tasks;private final TransactionTemplate tx;private long scanAfter;
    private final Map<Object,Set<Long>> liveCommands=new java.util.concurrent.ConcurrentHashMap<>();
    public RefundApplicationService(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderRefundApplicationApi orders,PaymentSuccessFactsApi payments,IntegrationEventPublisher outbox,
            SessionAuthority sessions,OwnerAuthority owners,ReasonPolicy reasons,Moderation moderation,Protection protection,TaskRecovery recovery){
        this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);this.guard=Objects.requireNonNull(guard);this.orders=Objects.requireNonNull(orders);
        this.payments=Objects.requireNonNull(payments);this.outbox=Objects.requireNonNull(outbox);this.sessions=Objects.requireNonNull(sessions);this.owners=Objects.requireNonNull(owners);
        this.reasons=Objects.requireNonNull(reasons);this.moderation=Objects.requireNonNull(moderation);this.protection=Objects.requireNonNull(protection);this.recovery=Objects.requireNonNull(recovery);
        db=RefundApplicationStore.mapper(source);execution=new RefundExecutionStore(source);tasks=new JdbcAsyncTaskSubmitter(source,ids);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    @Override public com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Fact readForOrder(String order,String store,QueryContext context,DataSource transactionSource){return owned(()->{
        if(transactionSource!=source)throw bad();scope(store,context);var loc=orders.locate(order,context);if(!store.equals(loc.storeId()))throw bad();
        var rejected=db.latestRejected(id(order));var active=db.active(id(order));
        com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Rejected old=null;
        com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Active live=null;
        if(rejected!=null){var d=requireDecision(str(rejected.id),str(rejected.decisionId),store,context);if(!order.equals(d.application().orderId())||!"REJECTED".equals(d.status()))throw bad();old=new com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Rejected(str(rejected.id),d.decisionId(),d.decidedAt(),d.operatorId(),Long.toString(d.application().version()));}
        if(active!=null){var a=requireApplication(str(active.id),store,context);if(!order.equals(a.orderId())||!loc.userId().equals(a.userId())||!loc.merchantId().equals(a.merchantId()))throw bad();if(a.decisionId()!=null)requireDecision(a.applicationId(),a.decisionId(),store,context);live=new com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Active(a.applicationId(),a.status(),Long.toString(a.version()),a.decisionId());}
        return new com.petplatform.refund.api.query.RefundApplicationHistoryFactsApi.Fact(order,store,loc.userId(),loc.merchantId(),now(),old,live,!execution.lockPresence(id(order)).isEmpty());
    });}
    @Override public Receipt apply(Apply c){return safe(()->{
        validateApply(c);top();var loc=orders.locate(c.orderId(),system(c.context()));authorizeBuyer(c.context(),loc.userId());
        var key=key("refund.application.apply",c.context(),"ORDER:"+c.orderId());byte[] input=json(values("orderId",c.orderId(),"reasonCode",c.reasonCode(),"reasonText",c.reasonText()));String purpose=purpose(key);
        var admitted=admit(key,purpose,input);if(!"SUCCEEDED".equals(admitted.state)){reasons.requireCode(c.reasonCode());moderate(c.reasonText());}
        return tx.execute(s->{defaults();var binding=db.binding(key);same(binding,purpose,input);guard.acquire(List.of(loc.storeId()),system(c.context()));
            var nowLoc=orders.locate(c.orderId(),system(c.context()));if(!loc.equals(nowLoc))throw bad();authorizeBuyer(c.context(),loc.userId());
            if("SUCCEEDED".equals(binding.state))return replay(binding,purpose,c.context(),c.orderId(),null);
            reserved(binding);pending(binding);var f=orders.requireEligible(c.orderId(),loc.storeId(),system(c.context()),source);if(!loc.equals(f.location()))throw bad();
            if(!execution.lockPresence(id(c.orderId())).isEmpty())throw error("REFUND_ALREADY_EXISTS");
            var active=db.active(id(c.orderId()));if(active!=null)throw error("REFUND_APPLICATION_ALREADY_PROCESSED");
            if(f.currentApplicationId()!=null){var old=requireApplication(f.currentApplicationId(),loc.storeId(),system(c.context()));if(!old.orderId().equals(c.orderId())||!"REJECTED".equals(old.status())||!"REJECTED".equals(f.currentApplicationStatus()))throw bad();}
            else if(f.currentApplicationStatus()!=null&&!"NONE".equals(f.currentApplicationStatus()))throw bad();
            var p=payments.requireSucceeded(f.paymentId(),c.orderId(),loc.storeId(),system(c.context()));checkPayment(f,p);
            var now=now();if(now.isBefore(f.appointmentStart())&& !"VERIFIED".equals(f.verificationStatus()))throw error("REFUND_BEFORE_SERVICE_NOT_IMPLEMENTED");
            long application=next(),event=next();var deadline=now.plusHours(24);
            var v=values("id",application,"number",next(),"order",id(c.orderId()),"user",id(loc.userId()),"reasonCode",c.reasonCode(),"reasonCipher",protect("APPLICATION:"+application,c.reasonText()),
                "amount",p.paidAmount(),"deadline",utc(deadline),"requestId",c.context().requestId(),"now",utc(now),"store",id(loc.storeId()),"merchant",id(loc.merchantId()),"reservation",id(loc.reservationId()),
                "payment",id(p.paymentId()),"paymentNo",id(p.paymentNo()),"paymentEvent",id(p.successEventId()),"trade",p.channelTradeNo(),"paidAt",utc(p.paidAt()),"command",binding.id,"event",event);
            one(db.insertApplication(v));String app=str(application);orders.bindApplication(app,c.orderId(),loc.storeId(),c.context(),source);
            enqueue(timeoutSpec(requireApplication(app,loc.storeId(),system(c.context()))));
            publish(event,"RefundApplicationCreatedEvent.v1",app,now,c.context(),values("applicationId",app,"orderId",c.orderId(),"userId",loc.userId(),"merchantId",loc.merchantId(),"storeId",loc.storeId(),"applicationStatus","PENDING_MERCHANT","merchantDeadline",time(deadline),"createdAt",time(now)));
            var receipt=new Receipt(c.orderId(),app,"PENDING_MERCHANT","0",time(deadline),null,null);finish(binding,purpose,receipt);
            before(()->{authorizeBuyer(c.context(),loc.userId());orders.requireApplicationBound(app,c.orderId(),loc.storeId(),system(c.context()),source);});return receipt;
        });
    });}
    @Override public Receipt decide(Decide c){return safe(()->{
        validateDecide(c);top();var hint=hint(c.applicationId());sessions.requireCurrent(c.context().operatorId());
        var key=key("refund.application.decide",c.context(),"REFUND_APPLICATION:"+c.applicationId());String purpose=purpose(key);byte[] input=json(values("applicationId",c.applicationId(),"version",c.expectedApplicationVersion(),"action",c.action(),"reasonText",c.reasonText()));
        var admitted=admit(key,purpose,input);if(!"SUCCEEDED".equals(admitted.state)&&"REJECT".equals(c.action()))moderate(c.reasonText());
        return tx.execute(s->{defaults();var binding=db.binding(key);same(binding,purpose,input);guard.acquire(List.of(str(hint.storeId)),system(c.context()));
            var loc=orders.locate(str(hint.orderId),system(c.context()));if(!str(hint.storeId).equals(loc.storeId()))throw bad();authorizeOwner(c.context(),loc.merchantId(),loc.storeId());
            if("SUCCEEDED".equals(binding.state))return replay(binding,purpose,c.context(),str(hint.orderId),c.applicationId());
            reserved(binding);pending(binding);var f=orders.requireEligible(str(hint.orderId),str(hint.storeId),system(c.context()),source);var a=requireApplication(c.applicationId(),str(hint.storeId),system(c.context()));current(f,a);
            if(!"PENDING_MERCHANT".equals(a.status()))throw error("REFUND_APPLICATION_ALREADY_PROCESSED");if(a.version()!=version(c.expectedApplicationVersion()))throw error(CommonApiCodes.CONFLICT);
            var now=now();if(!now.isBefore(a.merchantDeadline()))throw error("REFUND_MERCHANT_DEADLINE_PASSED");
            var receipt=writeDecision(a,"APPROVE".equals(c.action())?"APPROVED":"REJECTED",c.reasonText(),c.context(),binding,now);finish(binding,purpose,receipt);
            before(()->authorizeOwner(c.context(),a.merchantId(),a.storeId()));return receipt;
        });
    });}
    @Override public TaskResult handle(Timeout c){return safe(()->{
        if(c==null)throw invalid();task(c.context(),"REFUND_MERCHANT_TIMEOUT",c.applicationId());id(c.storeId());precise(c.expectedMerchantDeadline());top();
        var preliminary=tx.execute(s->{defaults();guard.acquire(List.of(c.storeId()),system(c.context()));var a=requireApplication(c.applicationId(),c.storeId(),system(c.context()));
            if(!a.merchantDeadline().isEqual(c.expectedMerchantDeadline()))throw bad();if(!"PENDING_MERCHANT".equals(a.status())){requireDecision(a.applicationId(),a.decisionId(),a.storeId(),system(c.context()));orders.requireDecisionRecorded(a.applicationId(),a.decisionId(),a.orderId(),a.storeId(),system(c.context()),source);return new TaskResult(true,null);}var now=now();return now.isBefore(a.merchantDeadline())?new TaskResult(false,a.merchantDeadline()):null;});
        if(preliminary!=null)return preliminary;
        var key=key("refund.application.timeout",c.context(),"REFUND_APPLICATION:"+c.applicationId());String purpose=purpose(key);byte[] input=json(values("applicationId",c.applicationId(),"storeId",c.storeId(),"merchantDeadline",time(c.expectedMerchantDeadline())));admit(key,purpose,input);
        return tx.execute(s->{defaults();var binding=db.binding(key);same(binding,purpose,input);guard.acquire(List.of(c.storeId()),system(c.context()));var a=requireApplication(c.applicationId(),c.storeId(),system(c.context()));
            if(!a.merchantDeadline().isEqual(c.expectedMerchantDeadline()))throw bad();if("SUCCEEDED".equals(binding.state)){replay(binding,purpose,c.context(),a.orderId(),a.applicationId());return new TaskResult(true,null);}
            if(!"PENDING_MERCHANT".equals(a.status())){requireDecision(a.applicationId(),a.decisionId(),a.storeId(),system(c.context()));orders.requireDecisionRecorded(a.applicationId(),a.decisionId(),a.orderId(),a.storeId(),system(c.context()),source);return new TaskResult(true,null);}var f=orders.requireEligible(a.orderId(),a.storeId(),system(c.context()),source);current(f,a);
            var now=now();if(now.isBefore(a.merchantDeadline()))return new TaskResult(false,a.merchantDeadline());reserved(binding);pending(binding);
            var receipt=writeDecision(a,"AUTO_APPROVED",null,c.context(),binding,now);finish(binding,purpose,receipt);return new TaskResult(true,null);
        });
    });}
    @Override public String createApproved(Create c){return safe(()->{
        if(c==null)throw invalid();task(c.context(),"REFUND_APPLICATION_CREATE",c.applicationId());id(c.decisionId());id(c.storeId());top();
        return tx.execute(s->{defaults();guard.acquire(List.of(c.storeId()),system(c.context()));var approved=requireApproved(c.applicationId(),c.decisionId(),c.storeId(),system(c.context()));var a=approved.application();
            if(a.refundOrderId()!=null){requireCreated(c.applicationId(),c.decisionId(),a.refundOrderId(),c.storeId(),system(c.context()));orders.requireCreated(a.orderId(),a.storeId(),a.refundOrderId(),source);return a.refundOrderId();}
            var permit=orders.acquireCreate(c.applicationId(),c.decisionId(),a.orderId(),a.storeId(),approved.commandId(),c.context(),source);
            if(!execution.lockPresence(id(a.orderId())).isEmpty())throw bad();var p=payments.requireSucceeded(a.paymentId(),a.orderId(),a.storeId(),system(c.context()));checkPayment(permit.fact(),p);samePayment(a,p);
            long refund=next(),number=next(),event=next();var now=now();var v=values("refund",refund,"number",number,"order",id(a.orderId()),"application",id(a.applicationId()),"decision",id(approved.decisionId()),"source",approved.sourceType(),"amount",a.paidAmount(),
                "initiator","USER".equals(approved.operatorType())?"MERCHANT":"SYSTEM","operator",approved.operatorId()==null?null:id(approved.operatorId()),"now",utc(now),"payment",id(a.paymentId()),"paymentNo",id(a.paymentNo()),"store",id(a.storeId()),"merchant",id(a.merchantId()),"user",id(a.userId()),"paymentEvent",id(a.paymentSuccessEventId()),"trade",a.channelTradeNo(),"paidAt",utc(a.paidAt()),"executionKey",executionKey(a.applicationId(),approved.decisionId()),"event",event);
            one(db.insertRefund(v));one(db.insertExecution(v));one(db.bindRefund(v));orders.commitCreated(permit.token(),a.orderId(),a.storeId(),str(refund),now,source);
            outbox.publish(new IntegrationEvent<>(str(event),"RefundOrderCreatedEvent.v1",1,now,"REFUND",str(refund),c.context().traceId(),values("refundOrderId",str(refund),"refundNo",str(number),"orderId",a.orderId(),"refundType","FULL","refundAmount",a.paidAmount(),"source",approved.sourceType(),"createdAt",time(now))));
            tasks.enqueue("APPLICATION_REFUND_SUBMIT:"+refund+":0","REFUND","APPLICATION_REFUND_SUBMIT","REFUND",refund,0L,LateRefundService.payload(refund,a.storeId()),8,"REFUND_CHANNEL");
            before(()->{requireCreated(a.applicationId(),approved.decisionId(),str(refund),a.storeId(),system(c.context()));orders.requireCreated(a.orderId(),a.storeId(),str(refund),source);});return str(refund);
        });
    });}
    private Receipt writeDecision(ApplicationFact a,String status,String reason,CommandContext context,RefundApplicationMapper.Binding binding,OffsetDateTime at){
        long decision=next(),event=next();var v=values("decision",decision,"application",id(a.applicationId()),"command",binding.id,"event",event,"status",status,"operatorType",context.operatorType().name(),"operator",context.operatorId()==null?null:id(context.operatorId()),"requestId",context.requestId(),"reasonCipher",protect("DECISION:"+decision,reason),"now",utc(at),"version",a.version());
        one(db.insertDecision(v));one(db.decide(v));orders.recordDecision(a.applicationId(),str(decision),a.orderId(),a.storeId(),context,source);
        if(!"REJECTED".equals(status))enqueue(createSpec(requireApproved(a.applicationId(),str(decision),a.storeId(),system(context))));
        publish(event,"RefundApplicationDecidedEvent.v1",a.applicationId(),at,context,values("applicationId",a.applicationId(),"decisionId",str(decision),"orderId",a.orderId(),"userId",a.userId(),"merchantId",a.merchantId(),"storeId",a.storeId(),"applicationStatus",status,"decidedAt",time(at)));
        before(()->orders.requireDecisionRecorded(a.applicationId(),str(decision),a.orderId(),a.storeId(),system(context),source));
        return new Receipt(a.orderId(),a.applicationId(),status,Long.toString(a.version()+1),time(a.merchantDeadline()),time(at),str(decision));
    }
    @Override public ApplicationFact requireApplication(String application,String store,QueryContext context){return owned(()->{
        scope(store,context);var r=db.row(id(application));if(r==null||!store.equals(str(r.storeId)))throw bad();
        for(Long value:List.of(r.id,r.applicationNo,r.orderId,r.applicantUserId,r.storeId,r.merchantId,r.reservationId,r.paymentId,r.paymentNo,r.paymentSuccessEventId,r.createdCommandId,r.createdEventId))positive(value);
        if(r.version==null||r.version<0||r.requestedAmount==null||r.requestedAmount.signum()<=0||r.paidAt==null||r.createdAt==null||r.merchantDeadline==null||!r.createdAt.plusHours(24).equals(r.merchantDeadline)||r.channelTradeNo==null||r.channelTradeNo.isBlank()||r.reasonCode==null||r.reasonCode.isBlank())throw bad();
        var command=db.bindingById(r.createdCommandId);if(command==null||!"refund.application.apply".equals(string(command.commandNamespace))||!Objects.equals(command.actorId,r.applicantUserId)||!"USER".equals(string(command.actorType))||!("ORDER:"+r.orderId).equals(string(command.scope))||!r.requestId.equals(string(command.requestId)))throw bad();
        commandProof(command,json(values("orderId",str(r.orderId),"reasonCode",r.reasonCode,"reasonText",r.reasonTextCipher==null?null:string(protection.reveal("APPLICATION:"+application,r.reasonTextCipher)))));
        if("PENDING_MERCHANT".equals(r.status)){if(r.version!=0||r.decisionId!=null||r.decidedAt!=null||r.refundOrderId!=null)throw bad();}
        else if(Set.of("APPROVED","AUTO_APPROVED","REJECTED").contains(r.status)){if(r.version!=1||r.decisionId==null||r.decidedAt==null||"REJECTED".equals(r.status)&&r.refundOrderId!=null)throw bad();}
        else throw bad();
        return new ApplicationFact(application,str(r.orderId),store,str(r.merchantId),str(r.applicantUserId),str(r.reservationId),str(r.paymentId),str(r.paymentNo),str(r.paymentSuccessEventId),r.channelTradeNo,r.requestedAmount,offset(r.paidAt),r.status,r.version,offset(r.createdAt),offset(r.merchantDeadline),str(r.createdCommandId),nullable(r.decisionId),nullable(r.refundOrderId));
    });}
    @Override public DecisionFact requireDecision(String app,String decision,String store,QueryContext context){return owned(()->{
        var a=requireApplication(app,store,context);var d=db.decision(id(decision));if(d==null||!decision.equals(a.decisionId())||!app.equals(str(d.applicationId))||!a.status().equals(d.status)||d.decidedAt==null||d.decidedAt.isBefore(utc(a.createdAt())))throw bad();positive(d.commandId);positive(d.eventId);
        var r=db.row(id(app));if(!d.decidedAt.equals(r.decidedAt))throw bad();var b=db.bindingById(d.commandId);if(b==null||!d.requestId.equals(string(b.requestId))||!("REFUND_APPLICATION:"+app).equals(string(b.scope))||!d.operatorType.equals(string(b.actorType)))throw bad();
        if("AUTO_APPROVED".equals(d.status)){if(!"SYSTEM".equals(d.operatorType)||d.operatorId!=null||b.actorId!=0||!"refund.application.timeout".equals(string(b.commandNamespace))||!("TASK:REFUND_MERCHANT_TIMEOUT:"+app).equals(d.requestId)||d.decidedAt.isBefore(utc(a.merchantDeadline()))||d.reasonCipher!=null)throw bad();}
        else if(Set.of("APPROVED","REJECTED").contains(d.status)){if(!"USER".equals(d.operatorType)||d.operatorId==null||d.operatorId<=0||!d.operatorId.equals(b.actorId)||!"refund.application.decide".equals(string(b.commandNamespace))||!d.decidedAt.isBefore(utc(a.merchantDeadline()))||"REJECTED".equals(d.status)&&(d.reasonCipher==null||d.reasonCipher.length<28)||"APPROVED".equals(d.status)&&d.reasonCipher!=null)throw bad();}
        else throw bad();
        byte[] expected="AUTO_APPROVED".equals(d.status)?json(values("applicationId",app,"storeId",store,"merchantDeadline",time(a.merchantDeadline()))):json(values("applicationId",app,"version","0","action","APPROVED".equals(d.status)?"APPROVE":"REJECT","reasonText",d.reasonCipher==null?null:string(protection.reveal("DECISION:"+decision,d.reasonCipher))));commandProof(b,expected);
        return new DecisionFact(a,decision,d.status,d.operatorType,nullable(d.operatorId),offset(d.decidedAt),str(d.commandId),str(d.eventId));
    });}
    @Override public ApprovalFact requireApproved(String app,String decision,String store,QueryContext context){return owned(()->{var d=requireDecision(app,decision,store,context);if(!Set.of("APPROVED","AUTO_APPROVED").contains(d.status()))throw bad();return new ApprovalFact(d.application(),decision,"APPROVED".equals(d.status())?"MERCHANT_APPROVED":"MERCHANT_TIMEOUT_AUTO",d.operatorType(),d.operatorId(),d.decidedAt(),d.commandId(),d.eventId());});}
    @Override public CreatedFact requireCreated(String app,String decision,String refund,String store,QueryContext context){return owned(()->{
        var approved=requireApproved(app,decision,store,context);var a=approved.application();var c=db.created(id(refund));
        if(c==null||!refund.equals(a.refundOrderId())||!app.equals(str(c.applicationId))||!app.equals(str(c.sourceBizId))||!decision.equals(str(c.sourceDecisionId))||!approved.sourceType().equals(c.sourceType)||!approved.sourceType().equals(c.executionSourceType)
            ||!a.orderId().equals(str(c.orderId))||!a.storeId().equals(str(c.storeId))||!a.merchantId().equals(str(c.merchantId))||!a.userId().equals(str(c.userId))||!a.paymentId().equals(str(c.paymentId))||!a.paymentNo().equals(str(c.paymentNo))||!a.paymentSuccessEventId().equals(str(c.paymentSuccessEventId))
            ||!a.channelTradeNo().equals(c.channelTradeNo)||c.paidAt==null||!a.paidAt().isEqual(offset(c.paidAt))||c.createdAt==null||!c.createdAt.equals(c.businessCreatedAt)||offset(c.createdAt).isBefore(approved.decidedAt())||c.bindingVersion==null||c.bindingVersion!=0||c.lateEventId!=null||c.sourceEventId!=null||!"FULL".equals(c.refundType)||!"LAKALA".equals(c.channel)||!"CNY".equals(c.currency)
            ||!equal(a.paidAmount(),c.refundAmount)||!equal(a.paidAmount(),c.paidAmount)||!equal(a.paidAmount(),c.businessAmount)||!equal(BigDecimal.ONE,c.refundRatio)||!executionKey(app,decision).equals(c.requestId))throw bad();positive(c.createdEventId);positive(c.refundNo);
        return new CreatedFact(approved,refund,str(c.refundNo),str(c.createdEventId),offset(c.createdAt));
    });}
    /** Bounded owner scan; one bad proof cannot starve later applications. Infrastructure failures still fail the scan. */
    public synchronized int reconcileTasks(){top();var rows=db.scan(scanAfter);if(rows.isEmpty()){scanAfter=0;return 0;}int count=0;
        for(var hint:rows){
            try{tx.executeWithoutResult(s->{
                defaults();var ctx=new QueryContext("refund-application-recovery",OperatorType.SYSTEM,null);
                guard.acquire(List.of(str(hint.storeId)),ctx);var a=requireApplication(str(hint.id),str(hint.storeId),ctx);
                if(a.refundOrderId()!=null){requireCreated(a.applicationId(),a.decisionId(),a.refundOrderId(),a.storeId(),ctx);orders.requireCreated(a.orderId(),a.storeId(),a.refundOrderId(),source);}
                else if("PENDING_MERCHANT".equals(a.status())){orders.requireApplicationBound(a.applicationId(),a.orderId(),a.storeId(),ctx,source);if(!now().isBefore(a.merchantDeadline()))recover(timeoutSpec(a));}
                else{orders.requireDecisionRecorded(a.applicationId(),a.decisionId(),a.orderId(),a.storeId(),ctx,source);
                    if(Set.of("APPROVED","AUTO_APPROVED").contains(a.status()))recover(createSpec(requireApproved(a.applicationId(),a.decisionId(),a.storeId(),ctx)));
                    else requireDecision(a.applicationId(),a.decisionId(),a.storeId(),ctx);}
                db.resolveRecoveryIssues(hint.id);
            });}catch(ApiException failure){
                if(infrastructure(failure)||!Set.of(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"APPLICATION_TASK_CONFLICT").contains(failure.code()))throw failure;
                String code="APPLICATION_TASK_CONFLICT".equals(failure.code())?"APPLICATION_TASK_CONFLICT":"APPLICATION_PROOF_INVALID";
                // The failed business transaction has rolled back. A separate commit makes the diagnostic durable.
                // If the database itself is unavailable this write throws; no cursor advancement or false success.
                tx.executeWithoutResult(s->{defaults();db.recordRecoveryIssue(hint.id,hint.orderId,hint.storeId,code);});
            }
            scanAfter=hint.id;count++;
        }return count;
    }
    private void recover(TaskSpec spec){try{recovery.recover(spec);}catch(IllegalArgumentException conflict){throw error("APPLICATION_TASK_CONFLICT");}}
    public static TaskSpec timeoutSpec(ApplicationFact a){return new TaskSpec("REFUND_MERCHANT_TIMEOUT:"+a.applicationId(),"REFUND_MERCHANT_TIMEOUT","REFUND_APPLICATION",id(a.applicationId()),0L,new String(json(values("applicationId",a.applicationId(),"storeId",a.storeId(),"merchantDeadline",time(a.merchantDeadline()))),StandardCharsets.UTF_8),8,"REFUND_APPLICATION",a.merchantDeadline());}
    public static TaskSpec createSpec(ApprovalFact a){return new TaskSpec("REFUND_APPLICATION_CREATE:"+a.application().applicationId(),"REFUND_APPLICATION_CREATE","REFUND_APPLICATION",id(a.application().applicationId()),1L,new String(json(values("applicationId",a.application().applicationId(),"decisionId",a.decisionId(),"storeId",a.application().storeId())),StandardCharsets.UTF_8),8,"REFUND_APPLICATION",a.decidedAt());}
    private void enqueue(TaskSpec t){tasks.enqueueAt(t.taskKey(),"REFUND",t.taskType(),t.bizType(),t.bizId(),t.expectedVersion(),t.payloadJson(),t.maxRetryCount(),t.retryPolicy(),t.availableAt());}
    private RefundApplicationMapper.Binding admit(Map<String,Object> key,String purpose,byte[] input){return tx.execute(s->{defaults();var v=new LinkedHashMap<>(key);v.put("id",next());v.put("hash",sha(input));v.put("canonical",protection.protect(purpose,input));db.reserve(v);var b=db.binding(key);same(b,purpose,input);return b;});}
    private void same(RefundApplicationMapper.Binding b,String purpose,byte[] input){if(b==null||!"canonical-v1".equals(b.canonicalVersion))throw bad();if(!sha(input).equals(b.payloadSha256)||!MessageDigest.isEqual(input,protection.reveal(purpose,b.canonicalBytes)))throw error(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT);}
    private Receipt replay(RefundApplicationMapper.Binding b,String purpose,CommandContext c,String order,String application){try{if(!Objects.equals(b.resultVersion,1)||b.resultBytes==null)throw bad();var r=JSON.readValue(protection.reveal(purpose+":RESULT",b.resultBytes),Receipt.class);if(!order.equals(r.orderId())||application!=null&&!application.equals(r.applicationId()))throw bad();var hint=hint(r.applicationId());var a=requireApplication(r.applicationId(),str(hint.storeId),system(c));
        if("refund.application.apply".equals(string(b.commandNamespace))){if(!str(b.id).equals(a.commandId())||!"PENDING_MERCHANT".equals(r.applicationStatus())||!"0".equals(r.applicationVersion())||r.decisionId()!=null||r.decidedAt()!=null||!time(a.merchantDeadline()).equals(r.merchantDeadline()))throw bad();}
        else{var d=requireDecision(a.applicationId(),r.decisionId(),a.storeId(),system(c));if(!str(b.id).equals(d.commandId())||!d.status().equals(r.applicationStatus())||!Long.toString(a.version()).equals(r.applicationVersion())||!time(d.decidedAt()).equals(r.decidedAt())||!time(a.merchantDeadline()).equals(r.merchantDeadline()))throw bad();}return r;
    }catch(ApiException e){throw e;}catch(Exception e){throw bad();}}
    private void finish(RefundApplicationMapper.Binding b,String purpose,Receipt receipt){one(db.succeed(b.id,protection.protect(purpose+":RESULT",json(receipt))));}
    private void current(OrderRefundApplicationApi.Fact f,ApplicationFact a){if(!a.orderId().equals(f.location().orderId())||!a.applicationId().equals(f.currentApplicationId())||!a.status().equals(f.currentApplicationStatus())||!a.userId().equals(f.location().userId())||!a.merchantId().equals(f.location().merchantId())||!a.storeId().equals(f.location().storeId())||!a.reservationId().equals(f.location().reservationId())||!a.paymentId().equals(f.paymentId())||!a.paymentSuccessEventId().equals(f.paymentSuccessEventId())||!a.channelTradeNo().equals(f.channelTradeNo())||!equal(a.paidAmount(),f.paidAmount())||!a.paidAt().isEqual(f.paidAt()))throw bad();}
    private static void checkPayment(OrderRefundApplicationApi.Fact f,com.petplatform.payment.api.dto.PaymentSuccessFact p){if(p==null||!f.location().orderId().equals(p.orderId())||!f.location().storeId().equals(p.storeId())||!f.location().merchantId().equals(p.merchantId())||!f.location().userId().equals(p.userId())||!f.paymentId().equals(p.paymentId())||!f.paymentSuccessEventId().equals(p.successEventId())||!f.channelTradeNo().equals(p.channelTradeNo())||!equal(f.paidAmount(),p.paidAmount())||!f.paidAt().isEqual(p.paidAt())||!"CNY".equals(p.currency()))throw bad();}
    private static void samePayment(ApplicationFact a,com.petplatform.payment.api.dto.PaymentSuccessFact p){if(!a.paymentNo().equals(p.paymentNo())||!a.paymentId().equals(p.paymentId())||!a.paymentSuccessEventId().equals(p.successEventId())||!equal(a.paidAmount(),p.paidAmount())||!a.paidAt().isEqual(p.paidAt())||!a.channelTradeNo().equals(p.channelTradeNo()))throw bad();}
    private RefundApplicationMapper.Row hint(String app){var r=db.hint(id(app));if(r==null)throw error("REFUND_APPLICATION_NOT_FOUND");positive(r.storeId);positive(r.orderId);return r;}
    private void authorizeBuyer(CommandContext c,String user){sessions.requireCurrent(c.operatorId());if(!user.equals(c.operatorId()))throw error(CommonApiCodes.FORBIDDEN);}
    private void authorizeOwner(CommandContext c,String merchant,String store){sessions.requireCurrent(c.operatorId());owners.requireOwner(c,merchant,store);sessions.requireCurrent(c.operatorId());}
    private void moderate(String text){if(text==null)return;var a=moderation.check(text);if(a==null||a.policyVersion()==null||a.policyVersion().isBlank()||!sha(bytes(text)).equals(a.textSha256()))throw bad();if(!a.allowed())throw invalid();}
    private void scope(String store,QueryContext c){id(store);if(c==null||c.operatorType()!=OperatorType.SYSTEM||c.operatorId()!=null||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),2)||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder))throw bad();guard.requireHeld(store,source);}
    private void before(Runnable check){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void beforeCommit(boolean readOnly){if(readOnly)throw bad();check.run();}});}
    private void pending(RefundApplicationMapper.Binding b){Object resource=TransactionSynchronizationManager.getResource(source);if(resource==null)throw bad();var set=liveCommands.computeIfAbsent(resource,k->java.util.concurrent.ConcurrentHashMap.newKeySet());set.add(b.id);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){liveCommands.remove(resource);}});}
    private void commandProof(RefundApplicationMapper.Binding b,byte[] expected){if(!"SUCCEEDED".equals(b.state)&&(!"RESERVED".equals(b.state)||!liveCommands.getOrDefault(TransactionSynchronizationManager.getResource(source),Set.of()).contains(b.id)))throw bad();var key=values("namespace",b.commandNamespace,"actorType",b.actorType,"actor",b.actorId,"scope",b.scope,"requestId",b.requestId);try{same(b,purpose(key),expected);}catch(ApiException e){if(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT.equals(e.code()))throw bad();throw e;}}
    private <T>T owned(Supplier<T> work){try{return work.get();}catch(RuntimeException e){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();if(infrastructure(e))throw new InfrastructureUnavailable(e);throw e instanceof ApiException a?a:bad();}}
    static boolean infrastructure(Throwable failure){for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof InfrastructureUnavailable||t instanceof org.springframework.dao.DataAccessException||t instanceof org.springframework.transaction.TransactionException||t instanceof java.sql.SQLException)return true;return false;}
    static final class InfrastructureUnavailable extends ApiException {InfrastructureUnavailable(Throwable cause){super(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Refund storage unavailable");initCause(cause);}}
    private static <T>T safe(Supplier<T> work){try{return work.get();}catch(ApiException e){throw e;}catch(org.springframework.dao.CannotAcquireLockException e){throw error("ORDER_OPERATION_BUSY");}catch(RuntimeException e){throw bad();}}
    private void defaults(){db.utc();db.lockWait();}private OffsetDateTime now(){return offset(db.now());}private long next(){long n=ids.nextId();positive(n);return n;}
    private byte[] protect(String purpose,String text){return text==null?null:protection.protect(purpose,bytes(text));}
    private void publish(long event,String type,String app,OffsetDateTime at,CommandContext c,Map<String,Object> payload){outbox.publish(new IntegrationEvent<>(str(event),type,1,at,"REFUND_APPLICATION",app,c.traceId(),payload));}
    private static void top(){if(TransactionSynchronizationManager.isActualTransactionActive())throw bad();}
    private static void validateApply(Apply c){if(c==null)throw invalid();user(c.context());id(c.orderId());text(c.reasonCode(),1,64);if(c.reasonCode().isBlank())throw invalid();if(c.reasonText()!=null)text(c.reasonText(),0,500);}
    private static void validateDecide(Decide c){if(c==null)throw invalid();user(c.context());id(c.applicationId());version(c.expectedApplicationVersion());if("APPROVE".equals(c.action())){if(c.reasonText()!=null)throw invalid();}else if("REJECT".equals(c.action())){text(c.reasonText(),1,500);if(c.reasonText().isBlank())throw invalid();}else throw invalid();}
    private static void user(CommandContext c){if(c==null||c.operatorType()!=OperatorType.USER||c.traceId()==null||c.traceId().isBlank())throw invalid();id(c.operatorId());PublicContractChecks.requireTerminalRequestId(c.requestId());}
    private static void task(CommandContext c,String type,String app){id(app);if(c==null||c.operatorType()!=OperatorType.SYSTEM||c.operatorId()!=null||!"ASYNC_TASK".equals(c.source())||!("TASK:"+type+":"+app).equals(c.requestId())||c.traceId()==null||c.traceId().isBlank())throw invalid();}
    private static long version(String s){if(s==null||!s.matches("0|[1-9][0-9]{0,18}"))throw invalid();try{return Long.parseLong(s);}catch(Exception e){throw invalid();}}
    private static void text(String s,int min,int max){if(s==null)throw invalid();int n=s.codePointCount(0,s.length());if(n<min||n>max||s.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw invalid();}
    private static Map<String,Object> key(String namespace,CommandContext c,String scope){return values("namespace",bytes(namespace),"actorType",bytes(c.operatorType().name()),"actor",c.operatorId()==null?0L:id(c.operatorId()),"scope",bytes(scope),"requestId",bytes(c.requestId()));}
    private static String purpose(Map<String,Object> k){return "REFUND_COMMAND:"+string((byte[])k.get("namespace"))+":"+string((byte[])k.get("actorType"))+":"+k.get("actor")+":"+string((byte[])k.get("scope"))+":"+string((byte[])k.get("requestId"));}
    private static QueryContext system(CommandContext c){return new QueryContext(c.traceId(),OperatorType.SYSTEM,null);}
    public static String executionKey(String app,String decision){return "APPLICATION_REFUND:"+app+":"+decision;}
    public static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw bad();}}
    private static byte[] json(Object o){try{return JSON.writeValueAsBytes(o);}catch(Exception e){throw bad();}}private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}private static String string(byte[] b){return b==null?null:new String(b,StandardCharsets.UTF_8);}
    private static String str(Long n){positive(n);return n.toString();}private static String nullable(Long n){return n==null?null:str(n);}private static long id(String s){return IDS.fromApi(s);}private static void positive(Long n){if(n==null||n<=0)throw bad();}
    private static OffsetDateTime offset(LocalDateTime t){if(t==null)throw bad();return t.atOffset(ZoneOffset.UTC);}private static LocalDateTime utc(OffsetDateTime t){return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}private static String time(OffsetDateTime t){return t.withOffsetSameInstant(ZoneOffset.UTC).toString();}
    private static void precise(OffsetDateTime t){if(t==null)throw invalid();PublicContractChecks.requireMillisecondPrecision(t);}private static boolean equal(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}
    private static void one(int n){if(n!=1)throw bad();}private static void reserved(RefundApplicationMapper.Binding b){if(!"RESERVED".equals(b.state))throw bad();}
    private static Map<String,Object> values(Object...pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static ApiException invalid(){return error(CommonApiCodes.INVALID_ARGUMENT);}private static ApiException bad(){return error(CommonApiCodes.DEPENDENCY_UNAVAILABLE);}private static ApiException error(String code){return new ApiException(code,"Refund application unavailable; retry with the original request ID");}
}
