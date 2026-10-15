package com.petplatform.order.biz.application;

import com.petplatform.common.*;
import com.petplatform.order.api.command.AdminOrderDispositionApi;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseCommand;
import com.petplatform.order.api.command.AdminOrderDispositionApi.AbnormalCloseReceipt;
import com.petplatform.order.biz.infrastructure.persistence.OrderAdminDispositionStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderAutoConfirmStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAdminDispositionMapper.ActionRow;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderAutoConfirmMapper;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.command.AdminAbnormalCloseRefundApi;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract-63 v0.2 operator abnormal close (HTTP10 §5.5 draft; user ruling 2026-10-15, doc 33).
 * Fixed capability semantics: 异常关闭 + 全额退款 — never a substitute merchant confirm. The close
 * walks the EXISTING state machine (order_stage → CANCELED with the ADMIN_ABNORMAL_CLOSE fact;
 * no new order status) under the same unfulfilled-order guards as the merchant-reject close
 * (PAID + UNVERIFIED, no open aftersale or refund application, no existing refund — so
 * "refund_order 创建后禁止核销" and the 未履约售后核销窗口 are untouched: this action refuses rather
 * than invalidates). The full refund is created through the refund kernel (source
 * ADMIN_ABNORMAL_CLOSE) inside the same store-guarded commit that writes the append-only
 * order_admin_action audit row bound to the terminal requestId — replay returns the stored
 * receipt; a different payload under the same requestId conflicts. The ADMIN_WEB session, the
 * order.abnormal.close action and the resource scope are re-proved inside that commit through
 * the authorization port. No completion event is published by design: C/M faces derive from
 * ORDER facts and the refund chain already publishes its existing events (disclosed in
 * contract-63 v0.2).
 */
public final class AdminOrderDispositionService implements AdminOrderDispositionApi {

    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private static final Set<String> REASONS =
            Set.of("STORE_OFFLINE", "QUALIFICATION_INVALID", "MERCHANT_UNREACHABLE", "OTHER");
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final AdminTradeAuthorizationPort.OrderAuthorizationPort authorization;
    private final PaymentSuccessFactsApi payments;
    private final RefundOrderFactsApi refundPresence;
    private final AdminAbnormalCloseRefundApi createRefund;
    private final OrderPaymentStore paidStore;
    private final OrderAutoConfirmStore orders;
    private final OrderAdminDispositionStore dispositions;
    private final TransactionTemplate tx;

    public AdminOrderDispositionService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, AdminTradeAuthorizationPort.OrderAuthorizationPort authorization,
            PaymentSuccessFactsApi payments, RefundOrderFactsApi refundPresence,
            AdminAbnormalCloseRefundApi createRefund) {
        this.source=Objects.requireNonNull(source);
        this.ids=Objects.requireNonNull(ids);
        this.guard=Objects.requireNonNull(guard);
        this.authorization=Objects.requireNonNull(authorization);
        this.payments=Objects.requireNonNull(payments);
        this.refundPresence=Objects.requireNonNull(refundPresence);
        this.createRefund=Objects.requireNonNull(createRefund);
        this.paidStore=new OrderPaymentStore(source);
        this.orders=new OrderAutoConfirmStore(source);
        this.dispositions=new OrderAdminDispositionStore(source);
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public AbnormalCloseReceipt abnormalClose(AbnormalCloseCommand c) {
        noOuter();
        validate(c);
        String requestId=c.context().requestId();
        var replayed=dispositions.mapper().selectByRequest(requestId);
        if(!replayed.isEmpty())return receipt(replayed.getFirst());
        var location=paidStore.locate(IDS.fromApi(c.orderId()));
        if(location.size()!=1)throw notFound();
        String storeId=IDS.toApi(location.getFirst().storeId());
        QueryContext system=new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null);
        try {
            return tx.execute(s -> {
                paidStore.sessionDefaults();
                var rows=dispositions.mapper().selectByRequest(requestId);
                if(!rows.isEmpty())return receipt(rows.getFirst());
                OrderAutoConfirmMapper.Row r=orders.lock(IDS.fromApi(c.orderId()));
                if(r==null||r.storeId==null||IDS.toApi(r.storeId).compareTo(storeId)!=0)throw notFound();
                guard.acquire(List.of(storeId),system);
                guard.requireHeld(storeId,source);
                var decision=authorization.check(new AdminTradeAuthorizationPort.Check(
                    c.authorization().sessionId(),c.authorization().sessionGeneration(),
                    c.context().operatorId(),"order.abnormal.close",
                    new AdminTradeAuthorizationPort.Resource("ORDER",c.orderId(),
                        IDS.toApi(r.merchantId),null,Long.toString(r.version)),
                    "ORDER_ABNORMAL_CLOSE",AdminTradeAuthorizationPort.Phase.EXECUTE));
                requireDecision(decision);
                qualify(c,r);
                var presence=refundPresence.findByOrder(c.orderId(),storeId,system);
                if(presence==null)throw unavailable();
                if(r.refundOrderId!=null||presence.exists())throw conflict("ORDER_REFUND_ALREADY_CREATED");
                long refundId=next(),actionId=next(),logId=next();
                OffsetDateTime now=paidStore.databaseNow();
                if(dispositions.mapper().closeAbnormal(r.id,refundId,utc(now),utc(now),r.version)!=1)
                    throw conflict("ORDER_STATE_NOT_ALLOWED");
                createRefund.createForAbnormalClose(c.orderId(),IDS.toApi(proof(r).paymentId()),
                    storeId,IDS.toApi(refundId),system);
                dispositions.mapper().insertAction(actionId,r.id,r.storeId,c.reasonCode(),c.reason(),
                    r.version,refundId,IDS.fromApi(c.context().operatorId()),
                    utc(now),decision.authzVersion(),requestId,c.context().traceId(),utc(now));
                dispositions.mapper().insertLog(logId,r.id,r.orderStage,
                    IDS.fromApi(c.context().operatorId()),requestId,"reasonCode="+c.reasonCode(),utc(now));
                return new AbnormalCloseReceipt(c.orderId(),"CANCELED","ADMIN_ABNORMAL_CLOSE",
                    IDS.toApi(refundId),now);
            });
        } catch(ApiException known) { throw known; }
        catch(CannotAcquireLockException busy) { throw conflict("ORDER_OPERATION_BUSY"); }
        catch(RuntimeException failure) { throw unavailable(); }
    }

    /** Same unfulfilled-order qualification as the merchant-reject close, both pre-fulfillment stages. */
    private void qualify(AbnormalCloseCommand c,OrderAutoConfirmMapper.Row r) {
        if(!Set.of("PENDING_CONFIRM","PENDING_SERVICE").contains(r.orderStage))
            throw conflict("ORDER_STATE_NOT_ALLOWED");
        if(!"PAID".equals(r.paymentStatus)||!"UNVERIFIED".equals(r.verificationStatus)
            ||r.canceledAt!=null||r.cancelReason!=null||r.refundOrderId!=null
            ||r.currentAftersaleId!=null||r.currentRefundApplicationId!=null
            ||r.refundedAmount==null||r.refundedAmount.signum()!=0
            ||r.paidAt==null||r.payAmount==null||r.payAmount.signum()<=0)throw conflict("ORDER_STATE_NOT_ALLOWED");
        var p=proof(r);
        var fact=payments.requireSucceeded(IDS.toApi(p.paymentId()),c.orderId(),
            IDS.toApi(r.storeId),new QueryContext(c.context().traceId(),OperatorType.SYSTEM,null));
        if(fact==null||!c.orderId().equals(fact.orderId())||!IDS.toApi(r.storeId).equals(fact.storeId())
            ||!IDS.toApi(r.userId).equals(fact.userId())
            ||!IDS.toApi(r.merchantId).equals(fact.merchantId())
            ||r.payAmount.compareTo(fact.paidAmount())!=0||p.paidAmount().compareTo(fact.paidAmount())!=0
            ||p.paidAt()==null||!p.paidAt().isEqual(r.paidAt.atOffset(ZoneOffset.UTC))
            ||!"CNY".equals(fact.currency()))throw unavailable();
    }

    private OrderPaymentStore.ResultRow proof(OrderAutoConfirmMapper.Row r) {
        var p=paidStore.lockResult(r.id);
        if(p==null||!"NORMAL".equals(p.resultType()))throw unavailable();
        return p;
    }

    private static AbnormalCloseReceipt receipt(ActionRow row) {
        return new AbnormalCloseReceipt(IDS.toApi(row.orderId),"CANCELED",
            "ADMIN_ABNORMAL_CLOSE",IDS.toApi(row.refundOrderId),
            row.actedAt.atOffset(ZoneOffset.UTC));
    }

    private static void requireDecision(AdminTradeAuthorizationPort.Decision decision) {
        if(decision==null||decision.checkedAt()==null||decision.authzVersion()==null||decision.authzVersion().isBlank())
            throw unavailable();
        if(!decision.allowed()) {
            if("RESOURCE_SCOPE_DENIED".equals(decision.reasonCode()))throw notFound();
            throw new ApiException(CommonApiCodes.FORBIDDEN,"无权执行该操作");
        }
    }

    private static void validate(AbnormalCloseCommand c) {
        try {
            if(c==null||c.context()==null||c.authorization()==null)throw invalid();
            if(c.context().operatorType()!=OperatorType.PLATFORM_OPERATOR
                ||c.context().operatorId()==null||c.context().operatorId().isBlank())throw invalid();
            PublicContractChecks.requireTerminalRequestId(c.context().requestId());
            if(c.context().traceId()==null||c.context().traceId().isBlank())throw invalid();
            if(!c.orderId().matches("[1-9][0-9]{0,18}")||Long.parseLong(c.orderId())<=0)throw invalid();
            Long.parseLong(c.context().operatorId());
            if(!REASONS.contains(c.reasonCode()))throw invalid();
            text(c.reason());
            if(c.authorization().sessionId()==null||c.authorization().sessionId().isBlank()
                ||c.authorization().sessionGeneration()<0)throw new ApiException(CommonApiCodes.UNAUTHORIZED,"admin authorization reference is required");
        } catch(ApiException known) { throw known; }
        catch(RuntimeException bad) { throw invalid(); }
    }

    private static void text(String value) {
        if(value==null||value.isBlank())throw invalid();
        int count=value.codePointCount(0,value.length());
        if(count<10||count>500||value.codePoints().anyMatch(cp -> cp>=0xD800&&cp<=0xDFFF))throw invalid();
    }

    private long next() { long value=ids.nextId(); if(value<=0)throw unavailable(); return value; }
    private static java.time.LocalDateTime utc(OffsetDateTime t) { return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    private static void noOuter() {
        if(TransactionSynchronizationManager.isActualTransactionActive()
            ||TransactionSynchronizationManager.isSynchronizationActive())throw unavailable();
    }
    private static ApiException invalid() { return new ApiException(CommonApiCodes.INVALID_ARGUMENT,"Invalid abnormal-close input"); }
    private static ApiException conflict(String code) { return new ApiException(code,"订单处置与当前状态冲突，请刷新后核对"); }
    private static ApiException notFound() { return new ApiException(CommonApiCodes.NOT_FOUND,"订单不存在"); }
    private static ApiException unavailable() { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"order disposition unavailable"); }
}
