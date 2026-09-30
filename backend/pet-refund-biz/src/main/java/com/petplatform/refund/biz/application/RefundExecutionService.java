package com.petplatform.refund.biz.application;

import com.petplatform.common.*;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.payment.api.dto.PaymentRefundResultFact;
import com.petplatform.payment.api.dto.PaymentRefundTypes.*;
import com.petplatform.payment.api.query.PaymentRefundResultFactsApi;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.task.core.JdbcAsyncTaskStatusReader;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.petplatform.refund.biz.application.LateRefundService.*;

/** Durable task coordinator. PAYMENT proves channel facts; REFUND owns business completion. */
public final class RefundExecutionService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RefundExecutionService.class);
    private final LateRefundService refunds;
    private final PaymentRefundApi channel;
    private final PaymentRefundResultFactsApi channelFacts;
    private final JdbcAsyncTaskStatusReader taskStates;
    private final AtomicLong scanAfter = new AtomicLong();
    public record ExecutionResult(boolean done, OffsetDateTime nextQueryAt) {}

    public RefundExecutionService(LateRefundService refunds, PaymentRefundApi channel,
            PaymentRefundResultFactsApi channelFacts) {
        this.refunds=Objects.requireNonNull(refunds);this.channel=Objects.requireNonNull(channel);
        this.channelFacts=Objects.requireNonNull(channelFacts);
        taskStates=new JdbcAsyncTaskStatusReader(refunds.source);
    }

    public ExecutionResult execute(String refundId,String storeId,boolean query,String traceId) {
        return execute(refundId,storeId,query,traceId,null);
    }
    /** A worker must bind its registered task generation to the authorized refund source. */
    public ExecutionResult execute(String refundId,String storeId,boolean query,String traceId,String expectedSource) {
        noOuter(); id(refundId);id(storeId);
        QueryContext ctx=new QueryContext(traceId,OperatorType.SYSTEM,null);
        RefundExecutionFact f=refunds.tx.execute(s -> {
            refunds.session();refunds.guard.acquire(List.of(storeId),ctx);
            return refunds.requireForChannel(refundId,storeId,ctx);
        });
        if(f==null||applicationSource(f.sourceType())&&expectedSource==null
            ||(expectedSource!=null&&!("APPLICATION".equals(expectedSource)?applicationSource(f.sourceType()):expectedSource.equals(f.sourceType()))))throw unavailable();
        if("SUCCESS".equals(f.status())) {
            refunds.tx.executeWithoutResult(s -> {
                refunds.guard.acquire(List.of(storeId),ctx);
                refunds.requireSucceeded(refundId,f.orderId(),storeId,ctx);
            });
            return new ExecutionResult(true,null);
        }
        String type=query?"REFUND_CHANNEL_QUERY":"REFUND_SUBMIT";
        CommandContext command=new CommandContext("TASK:"+type+":"+refundId+":0",traceId,
            OperatorType.SYSTEM,null,"ASYNC_TASK");
        // On an exception the worker retries this command. PAYMENT's persisted boundary decides
        // whether it can submit or must query, including after a lost database commit ACK.
        ChannelRefundProgress result=query
            ? channel.queryRefund(new ChannelRefundQuery(command,refundId,f.refundNo(),f.paymentId(),storeId,0))
            : channel.submitRefund(new ChannelRefundSubmitCommand(command,refundId,f.refundNo(),f.paymentId(),storeId,0));
        if(result==null||!refundId.equals(result.refundOrderId())||!f.refundNo().equals(result.refundNo())
                ||result.state()==null)throw unavailable();
        if(result.state()==CoordinationState.VERIFIED_SUCCESS) {
            finish(f,ctx); return new ExecutionResult(true,null);
        }
        if(result.state()==CoordinationState.RECONCILIATION_REQUIRED
                ||result.state()==CoordinationState.VERIFIED_TERMINAL_FAILURE) {
            issue(f,issueCode(f),ctx);
            // Preserve UNKNOWN. No generic hint can prove a final financial failure.
            return new ExecutionResult(true,null);
        }
        OffsetDateTime next=refunds.tx.execute(s -> {
            refunds.session();refunds.guard.acquire(List.of(storeId),ctx);
            RefundExecutionFact current=refunds.requireForChannel(refundId,storeId,ctx);
            same(f,current);
            if("SUCCESS".equals(current.status()))return null;
            OffsetDateTime now=refunds.now();
            OffsetDateTime due=result.queryNotBefore()==null?now.plusSeconds(30):time(result.queryNotBefore());
            if(due.isBefore(now))due=now.plusSeconds(1);
            refunds.store.markUnknown(id(refundId));
            refunds.store.setNextQuery(id(refundId),utc(due));
            if(!query) {
                // The schedule of the first query is immutable across submit task replays.
                OffsetDateTime first=offset(refunds.store.firstQueryAt(id(refundId),utc(due)));
                refunds.store.setFirstQuery(id(refundId),utc(first));
                refunds.tasks.enqueueAt(taskType(f,true)+":"+refundId+":0","REFUND",taskType(f,true),
                    "REFUND",id(refundId),0L,payload(id(refundId),storeId),8,"REFUND_CHANNEL",first);
            }
            return due;
        });
        return new ExecutionResult(!query||next==null,next);
    }

    private void finish(RefundExecutionFact expected,QueryContext ctx) {
        refunds.tx.executeWithoutResult(s -> {
            refunds.session();refunds.guard.acquire(List.of(expected.storeId()),ctx);
            var late=refunds.origin(expected,ctx);
            PaymentRefundResultFact p=channelFacts.requireVerified(expected.refundOrderId(),expected.refundNo(),
                expected.paymentId(),expected.storeId(),ctx);
            RefundExecutionFact f=refunds.requireForChannel(expected.refundOrderId(),expected.storeId(),ctx);
            same(expected,f);
            if(p==null||!"SUCCESS".equals(p.channelStatus())||!f.refundOrderId().equals(p.refundOrderId())
                    ||!f.refundNo().equals(p.refundNo())||!f.paymentId().equals(p.paymentId())
                    ||!f.orderId().equals(p.orderId())||!f.storeId().equals(p.storeId())
                    ||!f.channelTradeNo().equals(p.originalChannelTradeNo())||!f.currency().equals(p.currency())
                    ||p.refundAmount()==null||p.refundAmount().compareTo(f.refundAmount())!=0
                    ||p.channelRefundNo()==null||p.channelRefundNo().isBlank()||p.resultAt()==null
                    ||p.receiptSha256()==null||!p.receiptSha256().matches("[a-f0-9]{64}")
                    ||!Set.of("SUBMIT","QUERY","CALLBACK").contains(p.receiptSource())
                    ||!f.channelTradeNo().equals(late.channelTradeNo())
                    ||f.originalPaidAmount().compareTo(late.channelPaidAmount())!=0
                    ||!f.paymentSuccessEventId().equals(late.paymentSuccessEventId()))throw unavailable();
            time(p.resultAt());
            if("SUCCESS".equals(f.status())) {
                var success=refunds.requireSucceeded(f.refundOrderId(),f.orderId(),f.storeId(),ctx);
                if(!success.channelRefundNo().equals(p.channelRefundNo())||!success.succeededAt().isEqual(p.resultAt()))throw unavailable();
                return;
            }
            if(!Set.of("CREATED","PROCESSING","UNKNOWN").contains(f.status()))throw unavailable();
            long event=refunds.next();
            int changed=refunds.store.markRefundSucceeded(id(f.refundOrderId()),
                p.channelRefundNo(),utc(p.resultAt()));
            if(changed!=1)throw unavailable();
            int bound=refunds.store.markExecutionSucceeded(id(f.refundOrderId()),event,p.receiptSha256());
            if(bound!=1)throw unavailable();
            refunds.store.insertSuccessTransaction(refunds.next(),id(f.refundOrderId()),
                p.receiptSha256(),"SUBMIT".equals(p.receiptSource())?"REFUND":p.receiptSource(),f.refundNo());
            refunds.publisher.publish(new IntegrationEvent<>(Long.toString(event),"RefundSucceededEvent.v1",1,
                p.resultAt(),"REFUND",f.refundOrderId(),ctx.traceId(),Map.of(
                  "refundOrderId",f.refundOrderId(),"refundNo",f.refundNo(),"orderId",f.orderId(),
                  "refundType","FULL","refundSource",f.sourceType(),"refundAmount",f.refundAmount(),
                  "originalPaidAmount",f.originalPaidAmount(),"channelRefundNo",p.channelRefundNo(),
                  "succeededAt",p.resultAt().toString())));
            refunds.store.resolveIssue(id(f.refundOrderId()));
        });
    }

    private void issue(RefundExecutionFact expected,String code,QueryContext ctx) {
        refunds.tx.executeWithoutResult(s -> {
            refunds.session();refunds.guard.acquire(List.of(expected.storeId()),ctx);
            var f=refunds.requireForChannel(expected.refundOrderId(),expected.storeId(),ctx);same(expected,f);
            if("SUCCESS".equals(f.status()))return;
            refunds.store.markUnknown(id(f.refundOrderId()));
            int created=refunds.store.insertIssue(id(f.refundOrderId()),code);
            if(created==1) TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    LOG.error("Late refund reconciliation required refundOrderId={} issueCode={}",f.refundOrderId(),code);
                }
            });
        });
    }

    /** Cyclic bounded scan. A worker crash or exhausted retry cannot silently lose a refund. */
    public synchronized int reconcileDeadTasks() {
        noOuter();
        var candidates=refunds.store.scanOpen(scanAfter.get(),refunds.lateEnabled(),refunds.merchantEnabled(),refunds.applicationEnabled());
        if(candidates.isEmpty()) {scanAfter.set(0);return 0;}
        int count=0;
        for(var c:candidates) {
            String refundId=Long.toString(c.refundOrderId);
            String storeId=Long.toString(c.storeId);
            try {
            String submit=taskStates.status("REFUND_SUBMIT:"+refundId+":0");
            if(submit==null)submit=taskStates.status("MERCHANT_REFUND_SUBMIT:"+refundId+":0");
            if(submit==null)submit=taskStates.status("APPLICATION_REFUND_SUBMIT:"+refundId+":0");
            String query=taskStates.status("REFUND_CHANNEL_QUERY:"+refundId+":0");
            if(query==null)query=taskStates.status("MERCHANT_REFUND_CHANNEL_QUERY:"+refundId+":0");
            if(query==null)query=taskStates.status("APPLICATION_REFUND_CHANNEL_QUERY:"+refundId+":0");
            if("DEAD".equals(submit)||"CANCELED".equals(submit)||"DEAD".equals(query)||"CANCELED".equals(query)) {
                QueryContext ctx=new QueryContext("late-refund-reconciliation",OperatorType.SYSTEM,null);
                var fact=refunds.tx.execute(s -> {refunds.guard.acquire(List.of(storeId),ctx);return refunds.requireForChannel(refundId,storeId,ctx);});
                issue(fact,issueCode(fact),ctx);count++;
            }
            } catch(ApiException failure) {
                if(RefundApplicationService.infrastructure(failure)||!CommonApiCodes.DEPENDENCY_UNAVAILABLE.equals(failure.code()))throw failure;
                // A broken source proves neither UNKNOWN nor a financial outcome. Only record the
                // diagnostic after rollback, leaving all monetary state unchanged. Storage failures
                // here propagate and keep the cursor at this row for the next scan.
                refunds.tx.executeWithoutResult(s->{
                    refunds.session();int created=refunds.store.insertIssue(id(refundId),"REFUND_SOURCE_PROOF_INVALID");
                    if(created==1)TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void afterCommit(){LOG.error("Refund reconciliation required refundOrderId={} issueCode=REFUND_SOURCE_PROOF_INVALID",refundId);}
                    });
                });count++;
            }
            scanAfter.set(id(refundId));
        }
        return count;
    }

    public static String taskType(RefundExecutionFact f,boolean query) {
        if(applicationSource(f.sourceType()))return "APPLICATION_"+(query?"REFUND_CHANNEL_QUERY":"REFUND_SUBMIT");
        return ("MERCHANT_REJECT_ORDER".equals(f.sourceType())?"MERCHANT_":"")+(query?"REFUND_CHANNEL_QUERY":"REFUND_SUBMIT");
    }
    private static String issueCode(RefundExecutionFact f) {
        return SOURCE.equals(f.sourceType())?"LATE_PAYMENT_AUTO_REFUND_FAILED":applicationSource(f.sourceType())?"APPLICATION_REFUND_FAILED":"MERCHANT_REJECT_REFUND_FAILED";
    }
    private static void same(RefundExecutionFact a,RefundExecutionFact b) {
        if(!Objects.equals(a.sourceType(),b.sourceType())||!Objects.equals(a.sourceEventId(),b.sourceEventId())
            ||!Objects.equals(a.sourceBizId(),b.sourceBizId())||!Objects.equals(a.sourceDecisionId(),b.sourceDecisionId())
            ||!a.refundOrderId().equals(b.refundOrderId())||!a.refundNo().equals(b.refundNo())
            ||!a.orderId().equals(b.orderId())||!a.paymentId().equals(b.paymentId())||!a.storeId().equals(b.storeId())
            ||!a.paymentNo().equals(b.paymentNo())||a.refundAmount().compareTo(b.refundAmount())!=0
            ||!a.channelTradeNo().equals(b.channelTradeNo())||a.bindingVersion()!=b.bindingVersion())throw unavailable();
    }
}
