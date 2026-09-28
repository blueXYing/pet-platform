package com.petplatform.refund.biz.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.refund.api.dto.*;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.refund.biz.infrastructure.persistence.RefundExecutionStore;
import com.petplatform.refund.biz.infrastructure.persistence.mapper.RefundMapperRows;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** REFUND owns business authorization. An event alone never authorizes a channel refund. */
public final class LateRefundService implements IntegrationEventConsumer, RefundExecutionFactsApi {
    public static final String SOURCE = "LATE_PAYMENT_TIMEOUT";
    public static final String EVENT = "LatePaymentSucceededAfterTimeoutEvent.v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    final DataSource source;
    final RefundExecutionStore store;
    final SnowflakeIdGenerator ids;
    final ScheduleCapacityGuardApi guard;
    final OrderLatePaymentFactsApi orders;
    final IntegrationEventPublisher publisher;
    final JdbcAsyncTaskSubmitter tasks;
    final TransactionTemplate tx;
    private final PaymentSuccessFactsApi payments;
    private final JdbcOutboxConsumeGuard claims;

    public LateRefundService(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, OrderLatePaymentFactsApi orders,
            PaymentSuccessFactsApi payments, IntegrationEventPublisher publisher) {
        this.source=Objects.requireNonNull(source); this.ids=Objects.requireNonNull(ids);
        this.guard=Objects.requireNonNull(guard); this.orders=Objects.requireNonNull(orders);
        this.payments=Objects.requireNonNull(payments); this.publisher=Objects.requireNonNull(publisher);
        store=new RefundExecutionStore(source); tasks=new JdbcAsyncTaskSubmitter(source,ids);
        claims=new JdbcOutboxConsumeGuard(source,ids);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15);
    }

    @Override public String consumerName() { return "REFUND_LATE_PAYMENT"; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT); }

    @Override public void consume(DispatchedEvent event) {
        noOuter();
        try {
            if(event==null || !EVENT.equals(event.eventType()) || event.eventVersion()!=1
                    || !"ORDER".equals(event.aggregateType())) throw unavailable();
            id(event.eventId()); time(event.occurredAt());
            JsonNode n=JSON.readTree(event.payloadJson());
            Set<String> fields=new HashSet<>(); n.fieldNames().forEachRemaining(fields::add);
            if(!n.isObject() || !fields.equals(Set.of("orderId","paymentOrderId","paymentNo",
                    "channelPaidAmount","channelPaidAt","detectedAt"))) throw unavailable();
            String order=identifier(n,"orderId"),payment=identifier(n,"paymentOrderId"),number=identifier(n,"paymentNo");
            BigDecimal amount=money(n.get("channelPaidAmount"));
            OffsetDateTime paid=time(OffsetDateTime.parse(n.path("channelPaidAt").textValue()));
            OffsetDateTime detected=time(OffsetDateTime.parse(n.path("detectedAt").textValue()));
            if(id(order)!=event.aggregateId() || !detected.isEqual(event.occurredAt())) throw unavailable();
            QueryContext ctx=new QueryContext(event.traceId(),OperatorType.SYSTEM,null);
            String store=orders.locateStore(order,ctx);
            tx.executeWithoutResult(status -> {
                session(); guard.acquire(List.of(store),ctx); guard.requireHeld(store,source);
                var late=orders.requireLatePayment(order,payment,store,ctx);
                Row old=byOrder(order);
                // After our own successful refund, PAYMENT may correctly observe REFUND. Replays
                // must validate the immutable original binding without demanding it is still PAID.
                if(old!=null) {
                    verifyRow(old);
                    var f=old.fact();
                    if(!f.orderId().equals(order)||!f.lateEventId().equals(event.eventId())
                            || !f.paymentId().equals(payment)||!f.paymentNo().equals(number)
                            || f.refundAmount().compareTo(amount)!=0 || !f.paidAt().isEqual(paid)
                            || !f.storeId().equals(store)||!f.channelTradeNo().equals(late.channelTradeNo())
                            || !f.merchantId().equals(late.merchantId())||!f.userId().equals(late.userId())
                            || !f.paymentSuccessEventId().equals(late.paymentSuccessEventId())
                            || f.originalPaidAmount().compareTo(late.channelPaidAmount())!=0
                            || !f.paidAt().isEqual(late.channelPaidAt())) throw unavailable();
                    claims.tryClaim(consumerName(),event);
                    return;
                }
                var p=payments.requireSucceeded(payment,order,store,ctx);
                if(!p.paymentNo().equals(number)||p.paidAmount().compareTo(amount)!=0
                        || !p.paidAt().isEqual(paid)||!p.channelTradeNo().equals(late.channelTradeNo())
                        || !p.successEventId().equals(late.paymentSuccessEventId())
                        || !p.storeId().equals(store)||!p.merchantId().equals(late.merchantId())
                        || !p.userId().equals(late.userId())||!"CNY".equals(p.currency())
                        || p.paidAmount().compareTo(late.channelPaidAmount())!=0
                        || !p.paidAt().isEqual(late.channelPaidAt())) throw unavailable();
                if(!claims.tryClaim(consumerName(),event)) throw unavailable();
                long refund=next(),refundNo=next(),createdEvent=next();
                OffsetDateTime now=now(); String key="EVENT:LATE_PAYMENT_AUTO_REFUND:"+payment+":"+order;
                this.store.insertRefundOrder(refund,refundNo,id(order),amount,utc(now));
                this.store.insertExecution(refund,refundNo,id(order),id(payment),id(number),id(store),
                    id(p.merchantId()),id(p.userId()),id(p.successEventId()),id(event.eventId()),
                    p.channelTradeNo(),amount,utc(paid),key,createdEvent,utc(now));
                publisher.publish(new IntegrationEvent<>(Long.toString(createdEvent),
                    "RefundOrderCreatedEvent.v1",1,now,"REFUND",Long.toString(refund),event.traceId(),
                    Map.of("refundOrderId",Long.toString(refund),"refundNo",Long.toString(refundNo),
                      "orderId",order,"refundType","FULL","refundAmount",amount,
                      "source",SOURCE,"createdAt",now.toString())));
                tasks.enqueue("REFUND_SUBMIT:"+refund+":0","REFUND","REFUND_SUBMIT","REFUND",
                    refund,0L,payload(refund,store),8,"REFUND_CHANNEL");
            });
        } catch(ApiException known) { throw known; }
        catch(Exception failure) { throw unavailable(); }
    }

    @Override public RefundExecutionFact requireForChannel(String refundId,String storeId,QueryContext ctx) {
        try {
            requireGuard(storeId,ctx); Row r=byId(refundId); verifyRow(r);
            if(!r.fact().storeId().equals(storeId)) throw unavailable();
            return r.fact();
        } catch(RuntimeException failure) { rollback(); throw unavailable(); }
    }

    @Override public RefundSuccessFact requireSucceeded(String refundId,String orderId,String storeId,QueryContext ctx) {
        try {
            RefundExecutionFact f=requireForChannel(refundId,storeId,ctx); Row r=byId(refundId);
            if(!f.orderId().equals(orderId)||!"SUCCESS".equals(f.status())||r.successEvent()==null
                    || r.successAt()==null||r.channelRefundNo()==null||r.channelRefundNo().isBlank()
                    || r.receipt()==null||!r.receipt().matches("[a-f0-9]{64}")) throw unavailable();
            Integer proof=store.successProofCount(id(refundId),r.receipt(),f.refundNo());
            if(proof==null||proof!=1) throw unavailable();
            return new RefundSuccessFact(refundId,f.refundNo(),orderId,f.paymentId(),storeId,
                f.refundAmount(),f.originalPaidAmount(),r.channelRefundNo(),r.successAt(),
                Long.toString(r.successEvent()),SOURCE);
        } catch(RuntimeException failure) { rollback(); throw unavailable(); }
    }

    Row byId(String refund) { return read(store.lockById(id(refund))); }
    private Row byOrder(String order) { return read(store.lockByOrder(id(order))); }
    private Row read(List<RefundMapperRows.Binding> rows) {
        if(rows.size()>1) throw unavailable();
        if(rows.isEmpty()) return null;
        var r=rows.getFirst();
        var f=new RefundExecutionFact(Long.toString(value(r.bindingRefundId)),Long.toString(value(r.bindingRefundNo)),
            Long.toString(value(r.bindingOrderId)),Long.toString(value(r.paymentId)),
            Long.toString(value(r.paymentNo)),Long.toString(value(r.storeId)),
            Long.toString(value(r.merchantId)),Long.toString(value(r.userId)),
            Long.toString(value(r.paymentSuccessEventId)),Long.toString(value(r.lateEventId)),
            r.channelTradeNo,r.channelPaidAmount,r.refundAmount,offset(r.channelPaidAt),
            r.currency,r.status,value(r.bindingVersion),Long.toString(value(r.createdEventId)),
            offset(r.bindingCreatedAt));
        return new Row(f,value(r.businessRefundId),value(r.businessRefundNo),
            value(r.businessOrderId),offset(r.businessCreatedAt),r.refundType,r.sourceType,
            r.businessAmount,r.refundRatio,r.channel,r.requestId,r.successEventId,
            r.successReceiptSha256,r.channelRefundNo,offset(r.succeededAt));
    }
    private static long value(Long number) { return number == null ? 0L : number; }

    void verifyRow(Row r) {
        if(r==null) throw unavailable(); var f=r.fact();
        for(String v:List.of(f.refundOrderId(),f.refundNo(),f.orderId(),f.paymentId(),f.paymentNo(),
                f.storeId(),f.merchantId(),f.userId(),f.paymentSuccessEventId(),f.lateEventId(),f.createdEventId())) id(v);
        if(id(f.refundOrderId())!=r.businessRefundId()||id(f.refundNo())!=r.businessRefundNo()
                ||id(f.orderId())!=r.businessOrderId()||r.businessCreatedAt()==null
                ||f.createdAt()==null||!f.createdAt().isEqual(r.businessCreatedAt())
                ||!"FULL".equals(r.type())||!SOURCE.equals(r.refundSource())||!"LAKALA".equals(r.channel())
                ||!"CNY".equals(f.currency())||f.bindingVersion()!=0||f.channelTradeNo()==null
                ||f.channelTradeNo().isBlank()||f.refundAmount()==null||f.refundAmount().signum()<=0
                ||f.originalPaidAmount()==null||f.refundAmount().compareTo(f.originalPaidAmount())!=0
                ||r.businessAmount()==null||r.businessAmount().compareTo(f.refundAmount())!=0
                ||r.ratio()==null||r.ratio().compareTo(BigDecimal.ONE)!=0||f.paidAt()==null||f.createdAt()==null
                ||!Set.of("CREATED","PROCESSING","UNKNOWN","SUCCESS","FAILED").contains(f.status())
                ||!("EVENT:LATE_PAYMENT_AUTO_REFUND:"+f.paymentId()+":"+f.orderId()).equals(r.requestId())) throw unavailable();
    }

    record Row(RefundExecutionFact fact,long businessRefundId,long businessRefundNo,long businessOrderId,
        OffsetDateTime businessCreatedAt,String type,String refundSource,BigDecimal businessAmount,
        BigDecimal ratio,String channel,String requestId,Long successEvent,String receipt,
        String channelRefundNo,OffsetDateTime successAt) {}
    void requireGuard(String store,QueryContext ctx) {
        if(ctx==null||ctx.operatorType()!=OperatorType.SYSTEM) throw unavailable();
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        guard.requireHeld(store,source);
        Object resource=TransactionSynchronizationManager.getResource(source);
        if(!(resource instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction()) throw unavailable();
    }
    void rollback() { Object r=TransactionSynchronizationManager.getResource(source);
        if(r instanceof ConnectionHolder h) h.setRollbackOnly(); }
    void session() { store.sessionDefaults(); }
    OffsetDateTime now() { return offset(store.databaseNow()); }
    long next() { long value=ids.nextId(); if(value<=0)throw unavailable();return value; }
    static long id(String value) { return IDS.fromApi(value); }
    static String payload(long refund,String store) { return "{\"refundOrderId\":\""+refund+"\",\"storeId\":\""+store+"\",\"bindingVersion\":0}"; }
    static OffsetDateTime offset(LocalDateTime time) { return time==null?null:time.atOffset(ZoneOffset.UTC); }
    static LocalDateTime utc(OffsetDateTime time) { return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    static OffsetDateTime time(OffsetDateTime time) { PublicContractChecks.requireMillisecondPrecision(time);return time; }
    static String identifier(JsonNode n,String field) { if(!n.path(field).isTextual())throw unavailable();String s=n.path(field).textValue();id(s);return s; }
    static BigDecimal money(JsonNode n) { if(n==null||!n.asText().matches("[0-9]{1,16}(\\.[0-9]{1,2})?"))throw unavailable();
        var v=new BigDecimal(n.asText());if(v.signum()<=0)throw unavailable();return v; }
    static void noOuter() { if(TransactionSynchronizationManager.isActualTransactionActive()
        ||TransactionSynchronizationManager.isSynchronizationActive())throw unavailable(); }
    static ApiException unavailable() { return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"late refund facts unavailable"); }
}
