package com.petplatform.refund.biz.application;

import com.petplatform.common.*;
import com.petplatform.refund.api.command.AdminRefundDispositionApi;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionCommand;
import com.petplatform.refund.api.command.AdminRefundDispositionApi.DispositionReceipt;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Check;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Phase;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Resource;
import com.petplatform.refund.biz.infrastructure.persistence.AdminRefundDispositionStore;
import com.petplatform.task.core.JdbcAsyncTaskRecoverer;
import com.petplatform.task.core.JdbcAsyncTaskStatusReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Contract-63 v0.2 disposal kernel (HTTP10 §5.2 draft; user ruling 2026-10-15, doc 33):
 * channel-query and retry over ONE existing business refund. Both actions re-arm the refund's
 * OWN durable task (the {@code <prefix>REFUND_CHANNEL_QUERY} / {@code <prefix>REFUND_SUBMIT}
 * family, resolved per source type exactly like the worker registrations) — never a second
 * business refund, never a hand-written channel fact (SSOT §22: 渠道查询/重试不算第二次业务退款).
 * Every call joins one store-guarded transaction that (1) re-proves the ADMIN_WEB session,
 * action code and resource scope through the authorization port, (2) proves the refund facts
 * via {@link LateRefundService#requireForChannel}, (3) re-arms the exact durable task row
 * (enqueue fresh, owner-recover a terminal row, or leave an in-flight row untouched), and
 * (4) writes the append-only {@code refund_admin_action} audit row bound to the terminal
 * requestId — replay returns the stored receipt without touching the task again; a different
 * payload under the same requestId conflicts. PAYMENT's persisted boundary still decides every
 * channel outcome afterwards; this kernel only re-arms the existing coordination.
 */
public final class AdminRefundDispositionService implements AdminRefundDispositionApi {

    private static final Set<String> LIVE_STATES = Set.of("READY", "RUNNING", "RETRY_WAIT");
    private final SnowflakeIdGenerator ids;
    private final LateRefundService refunds;
    private final AdminTradeAuthorizationPort.RefundAuthorizationPort authorization;
    private final AdminRefundDispositionStore store;
    private final JdbcAsyncTaskStatusReader taskStates;
    private final JdbcAsyncTaskRecoverer recovery;

    public AdminRefundDispositionService(DataSource source, SnowflakeIdGenerator ids,
            LateRefundService refunds, AdminTradeAuthorizationPort.RefundAuthorizationPort authorization) {
        Objects.requireNonNull(source);
        this.ids=Objects.requireNonNull(ids);
        this.refunds=Objects.requireNonNull(refunds);
        this.authorization=Objects.requireNonNull(authorization);
        this.store=new AdminRefundDispositionStore(source);
        this.taskStates=new JdbcAsyncTaskStatusReader(source);
        this.recovery=new JdbcAsyncTaskRecoverer(source, ids);
    }

    @Override public DispositionReceipt channelQuery(DispositionCommand command) {
        return dispose(command, "CHANNEL_QUERY", "refund.channel.query", "REFUND_CHANNEL_QUERY", true);
    }

    @Override public DispositionReceipt retry(DispositionCommand command) {
        return dispose(command, "RETRY", "refund.retry", "REFUND_SUBMIT", false);
    }

    private DispositionReceipt dispose(DispositionCommand command, String action, String actionCode,
            String family, boolean query) {
        noOuter();
        validate(command);
        String refundId=command.refundOrderId();
        String requestId=command.context().requestId();
        // A committed action replays verbatim without re-triggering anything.
        AdminRefundDispositionStore.ActionRow replayed=store.byRequest(requestId);
        if (replayed!=null) {
            if(!action.equals(replayed.actionType())||!refundId.equals(Long.toString(replayed.refundOrderId())))
                throw conflict("IDEMPOTENCY_KEY_CONFLICT");
            return new DispositionReceipt(refundId, action, replayed.taskKey(), replayed.taskState(),
                replayed.refundStatus(), replayed.actedAt().atOffset(ZoneOffset.UTC));
        }
        String storeId=locateStore(refundId);
        QueryContext ctx=new QueryContext(command.context().traceId(),OperatorType.SYSTEM,null);
        refunds.tx.execute(s -> {
            refunds.session();refunds.guard.acquire(List.of(storeId),ctx);
            RefundExecutionFact f=refunds.requireForChannel(refundId,storeId,ctx);
            Decision decision=authorization.check(new Check(command.authorization().sessionId(),
                command.authorization().sessionGeneration(),command.context().operatorId(),actionCode,
                new Resource("REFUND_ORDER",refundId,null,null,Long.toString(f.bindingVersion())),
                "REFUND_DISPOSITION",Phase.EXECUTE));
            requireDecision(decision);
            if("SUCCESS".equals(f.status()))throw conflict("COMMON_CONFLICT");
            // A never-submitted refund has no channel fact to query; retry owns that state.
            if(query&&"CREATED".equals(f.status()))throw conflict("COMMON_CONFLICT");
            String type=RefundExecutionService.taskType(f,query);
            if(!type.endsWith(family))throw unavailable();
            String taskKey=type+":"+refundId+":0";
            String after=trigger(taskKey,type,refundId,storeId,taskStates.status(taskKey),query);
            OffsetDateTime now=refunds.now();
            store.insert(ids.nextId(),Long.parseLong(refundId),Long.parseLong(f.orderId()),action,taskKey,after,
                f.status(),Long.parseLong(command.context().operatorId()),LateRefundService.utc(now),decision.authzVersion(),
                requestId,command.context().traceId());
            return null;
        });
        AdminRefundDispositionStore.ActionRow row=store.byRequest(requestId);
        if(row==null)throw unavailable();
        return new DispositionReceipt(refundId,action,row.taskKey(),row.taskState(),row.refundStatus(),
            row.actedAt().atOffset(ZoneOffset.UTC));
    }

    /** Enqueue fresh, revive a terminal task, or leave an in-flight task untouched. */
    private String trigger(String taskKey,String type,String refundId,String storeId,String state,boolean query) {
        if(state!=null&&LIVE_STATES.contains(state))return state;
        if(state==null) {
            if(query) {
                java.time.LocalDateTime due=refunds.store.persistedFirstQueryAt(Long.parseLong(refundId));
                OffsetDateTime at=due==null?refunds.now().plusSeconds(30):LateRefundService.offset(due);
                refunds.tasks.enqueueAt(taskKey,"REFUND",type,"REFUND",Long.parseLong(refundId),0L,
                    LateRefundService.payload(Long.parseLong(refundId),storeId),8,"REFUND_CHANNEL",at);
            } else {
                refunds.tasks.enqueue(taskKey,"REFUND",type,"REFUND",Long.parseLong(refundId),0L,
                    LateRefundService.payload(Long.parseLong(refundId),storeId),8,"REFUND_CHANNEL");
            }
            return "READY";
        }
        // DEAD/CANCELED/SUCCEEDED: owner recovery resets the exact row to READY without taking a
        // lease; the original submitted schedule must match (query tasks keep their immutable
        // first-query schedule, submit tasks were never scheduled).
        java.time.LocalDateTime original=query?refunds.store.persistedFirstQueryAt(Long.parseLong(refundId)):null;
        recovery.recover(taskKey,"REFUND",type,"REFUND",Long.parseLong(refundId),0L,
            LateRefundService.payload(Long.parseLong(refundId),storeId),8,"REFUND_CHANNEL",
            original==null?null:LateRefundService.offset(original));
        return "READY";
    }

    private String locateStore(String refundId) {
        String storeId=store.locateStore(Long.parseLong(refundId));
        if(storeId==null)throw notFound();
        return storeId;
    }

    private static void validate(DispositionCommand c) {
        if(c==null||c.context()==null||c.authorization()==null)throw invalid();
        if(c.context().operatorType()!=OperatorType.PLATFORM_OPERATOR
            ||c.context().operatorId()==null||c.context().operatorId().isBlank())throw invalid();
        try { PublicContractChecks.requireTerminalRequestId(c.context().requestId()); }
        catch(IllegalArgumentException bad) { throw invalid(); }
        if(c.context().traceId()==null||c.context().traceId().isBlank())throw invalid();
        try {
            if(!c.refundOrderId().matches("[1-9][0-9]{0,18}")||Long.parseLong(c.refundOrderId())<=0)throw invalid();
            Long.parseLong(c.context().operatorId());
        } catch(NumberFormatException bad) { throw invalid(); }
        if(c.authorization().sessionId()==null||c.authorization().sessionId().isBlank()
            ||c.authorization().sessionGeneration()<0)throw new ApiException(CommonApiCodes.UNAUTHORIZED,"admin authorization reference is required");
    }

    private static void requireDecision(Decision decision) {
        if(decision==null||decision.checkedAt()==null||decision.authzVersion()==null||decision.authzVersion().isBlank())
            throw unavailable();
        if(!decision.allowed()) {
            if("RESOURCE_SCOPE_DENIED".equals(decision.reasonCode()))throw notFound();
            throw new ApiException(CommonApiCodes.FORBIDDEN,"无权执行该操作");
        }
    }

    private static String taskKey(RefundExecutionFact f,boolean query) {
        return RefundExecutionService.taskType(f,query)+":"+f.refundOrderId()+":0";
    }

    private static void noOuter() {
        if(TransactionSynchronizationManager.isActualTransactionActive()
            ||TransactionSynchronizationManager.isSynchronizationActive())throw unavailable();
    }
    private static ApiException invalid() { return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid refund disposition input"); }
    private static ApiException conflict(String code) { return new ApiException(code,"退款处置与当前状态或幂等绑定冲突"); }
    private static ApiException notFound() { return new ApiException(CommonApiCodes.NOT_FOUND,"退款单不存在"); }
    private static ApiException unavailable() { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"refund disposition unavailable"); }
}
