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
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
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
    final JdbcTemplate jdbc;
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
        jdbc=new JdbcTemplate(source); tasks=new JdbcAsyncTaskSubmitter(source,ids);
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
                    if(!f.paymentId().equals(payment)||!f.paymentNo().equals(number)
                            || f.refundAmount().compareTo(amount)!=0 || !f.paidAt().isEqual(paid)
                            || !f.storeId().equals(store)||!f.channelTradeNo().equals(late.channelTradeNo())
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
                jdbc.update("""
                    INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,
                      refund_amount,refund_ratio,status,initiator_type,channel,created_at,updated_at)
                    VALUES (?,?,?,'FULL','LATE_PAYMENT_TIMEOUT',?,1.000000,'CREATED','SYSTEM','LAKALA',?,?)
                    """,refund,refundNo,id(order),amount,utc(now),utc(now));
                jdbc.update("""
                    INSERT INTO refund_execution(refund_order_id,refund_no,order_id,payment_id,payment_no,
                      store_id,merchant_id,user_id,payment_success_event_id,late_event_id,channel_trade_no,
                      channel_paid_amount,refund_amount,channel_paid_at,currency,request_id,
                      created_event_id,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CNY',?,?,?,?)
                    """,refund,refundNo,id(order),id(payment),id(number),id(store),id(p.merchantId()),
                    id(p.userId()),id(p.successEventId()),id(event.eventId()),p.channelTradeNo(),amount,
                    amount,utc(paid),key,createdEvent,utc(now),utc(now));
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
            Integer proof=jdbc.queryForObject("SELECT COUNT(*) FROM refund_transaction WHERE refund_id=? "
                +"AND BINARY request_id=BINARY ? AND action='QUERY' AND channel_status='SUCCESS' "
                +"AND BINARY channel_request_no=BINARY ?",Integer.class,id(refundId),r.receipt(),r.channelRefundNo());
            if(proof==null||proof!=1) throw unavailable();
            return new RefundSuccessFact(refundId,f.refundNo(),orderId,f.paymentId(),storeId,
                f.refundAmount(),f.originalPaidAmount(),r.channelRefundNo(),r.successAt(),
                Long.toString(r.successEvent()),SOURCE);
        } catch(RuntimeException failure) { rollback(); throw unavailable(); }
    }

    Row byId(String refund) { return read("r.id",id(refund)); }
    private Row byOrder(String order) { return read("r.order_id",id(order)); }
    private Row read(String column,long value) {
        var rows=jdbc.query("""
            SELECT r.id,r.refund_no,r.order_id,r.refund_type,r.source_type,r.refund_amount AS business_amount,
              r.refund_ratio,r.status,r.channel,r.channel_refund_no,r.succeeded_at,r.created_at AS business_created_at,
              e.* FROM refund_order r LEFT JOIN refund_execution e ON e.refund_order_id=r.id
            WHERE %s=? FOR UPDATE
            """.formatted(column),(rs,n)-> {
                var f=new RefundExecutionFact(Long.toString(rs.getLong("id")),Long.toString(rs.getLong("refund_no")),
                    Long.toString(rs.getLong("order_id")),Long.toString(rs.getLong("payment_id")),
                    Long.toString(rs.getLong("payment_no")),Long.toString(rs.getLong("store_id")),
                    Long.toString(rs.getLong("merchant_id")),Long.toString(rs.getLong("user_id")),
                    Long.toString(rs.getLong("payment_success_event_id")),Long.toString(rs.getLong("late_event_id")),
                    rs.getString("channel_trade_no"),rs.getBigDecimal("channel_paid_amount"),rs.getBigDecimal("refund_amount"),
                    offset(rs.getObject("channel_paid_at",LocalDateTime.class)),rs.getString("currency"),rs.getString("status"),
                    rs.getLong("binding_version"),Long.toString(rs.getLong("created_event_id")),
                    offset(rs.getObject("business_created_at",LocalDateTime.class)));
                return new Row(f,rs.getString("refund_type"),rs.getString("source_type"),
                    rs.getBigDecimal("business_amount"),rs.getBigDecimal("refund_ratio"),rs.getString("channel"),
                    rs.getString("request_id"),rs.getObject("success_event_id",Long.class),
                    rs.getString("success_receipt_sha256"),rs.getString("channel_refund_no"),
                    offset(rs.getObject("succeeded_at",LocalDateTime.class)));
            },value);
        if(rows.size()>1) throw unavailable(); return rows.isEmpty()?null:rows.getFirst();
    }

    void verifyRow(Row r) {
        if(r==null) throw unavailable(); var f=r.fact();
        for(String v:List.of(f.refundOrderId(),f.refundNo(),f.orderId(),f.paymentId(),f.paymentNo(),
                f.storeId(),f.merchantId(),f.userId(),f.paymentSuccessEventId(),f.lateEventId(),f.createdEventId())) id(v);
        if(!"FULL".equals(r.type())||!SOURCE.equals(r.refundSource())||!"LAKALA".equals(r.channel())
                ||!"CNY".equals(f.currency())||f.bindingVersion()!=0||f.channelTradeNo()==null
                ||f.channelTradeNo().isBlank()||f.refundAmount()==null||f.refundAmount().signum()<=0
                ||f.originalPaidAmount()==null||f.refundAmount().compareTo(f.originalPaidAmount())!=0
                ||r.businessAmount()==null||r.businessAmount().compareTo(f.refundAmount())!=0
                ||r.ratio()==null||r.ratio().compareTo(BigDecimal.ONE)!=0||f.paidAt()==null||f.createdAt()==null
                ||!Set.of("CREATED","PROCESSING","UNKNOWN","SUCCESS","FAILED").contains(f.status())
                ||!("EVENT:LATE_PAYMENT_AUTO_REFUND:"+f.paymentId()+":"+f.orderId()).equals(r.requestId())) throw unavailable();
    }

    record Row(RefundExecutionFact fact,String type,String refundSource,BigDecimal businessAmount,
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
    void session() { jdbc.execute("SET SESSION time_zone='+00:00'"); jdbc.execute("SET SESSION innodb_lock_wait_timeout=2"); }
    OffsetDateTime now() { return offset(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class)); }
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
