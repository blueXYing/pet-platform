package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.command.OrderRefundApplicationApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.order.biz.infrastructure.persistence.mapper.OrderRefundApplicationMapper;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Final refund success, ORDER projection, consume claim and resource release commit atomically. */
public final class OrderApplicationRefundProjectionConsumer implements IntegrationEventConsumer {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final OrderRefundApplicationApi locations;
    private final OrderRefundApplicationFactsApi origins;
    private final RefundExecutionFactsApi refunds;
    private final ReservationRefundReleaseApi release;
    private final OrderRefundApplicationMapper db;
    private final OrderAutoConfirmStore orders;
    private final JdbcOutboxConsumeGuard claims;
    private final TransactionTemplate tx;

    public OrderApplicationRefundProjectionConsumer(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
            OrderRefundApplicationApi locations,OrderRefundApplicationFactsApi origins,RefundExecutionFactsApi refunds,
            ReservationRefundReleaseApi release) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);this.locations=Objects.requireNonNull(locations);
        this.origins=Objects.requireNonNull(origins);this.refunds=Objects.requireNonNull(refunds);this.release=Objects.requireNonNull(release);
        db=new OrderRefundApplicationStore(source).mapper();orders=new OrderAutoConfirmStore(source);claims=new JdbcOutboxConsumeGuard(source,ids);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    public String consumerName(){return "ORDER_APPLICATION_REFUND";}
    public Set<String> eventTypes(){return Set.of("RefundSucceededEvent.v1");}
    public void consume(DispatchedEvent event) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        try{
            if(event==null||event.eventVersion()!=1||!eventTypes().contains(event.eventType())||!"REFUND".equals(event.aggregateType()))throw unavailable();
            JsonNode n=JSON.readTree(event.payloadJson());if(n==null||!n.isObject())throw unavailable();
            Set<String> keys=new HashSet<>();n.fieldNames().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("refundOrderId","refundNo","orderId","refundType","refundSource","refundAmount","originalPaidAmount","channelRefundNo","succeededAt")))throw unavailable();
            String refund=text(n,"refundOrderId"),order=text(n,"orderId"),sourceType=text(n,"refundSource");
            IDS.fromApi(order);IDS.fromApi(text(n,"refundNo"));IDS.fromApi(event.eventId());
            if(IDS.fromApi(refund)!=event.aggregateId()||!"FULL".equals(text(n,"refundType"))||!n.path("refundAmount").isNumber()||!n.path("originalPaidAmount").isNumber()
                    ||n.path("refundAmount").decimalValue().signum()<=0||n.path("refundAmount").decimalValue().scale()>2
                    ||n.path("originalPaidAmount").decimalValue().signum()<=0||n.path("originalPaidAmount").decimalValue().scale()>2)throw unavailable();
            OffsetDateTime at=OffsetDateTime.parse(text(n,"succeededAt"));PublicContractChecks.requireMillisecondPrecision(at);
            if(event.occurredAt()==null||!event.occurredAt().isEqual(at))throw unavailable();
            if(Set.of("LATE_PAYMENT_TIMEOUT","MERCHANT_REJECT_ORDER").contains(sourceType))return;
            if(!Set.of("MERCHANT_APPROVED","MERCHANT_TIMEOUT_AUTO").contains(sourceType))throw unavailable();
            QueryContext q=new QueryContext(event.traceId(),OperatorType.SYSTEM,null);String store=locations.locate(order,q).storeId();
            tx.executeWithoutResult(status->{
                guard.acquire(List.of(store),q);guard.requireHeld(store,source);
                var binding=refunds.requireForChannel(refund,store,q);var origin=origins.requireApprovedRefund(order,binding.paymentId(),store,q);
                var success=refunds.requireSucceeded(refund,order,store,q);
                if(!sourceType.equals(binding.sourceType())||!sourceType.equals(origin.sourceType())||!sourceType.equals(success.refundSource())
                        ||binding.sourceEventId()!=null||origin.sourceEventId()!=null||!origin.sourceBizId().equals(binding.sourceBizId())
                        ||!origin.sourceDecisionId().equals(binding.sourceDecisionId())||!refund.equals(origin.refundOrderId())
                        ||!origin.merchantId().equals(binding.merchantId())||!origin.userId().equals(binding.userId())
                        ||!origin.paymentSuccessEventId().equals(binding.paymentSuccessEventId())||!origin.channelTradeNo().equals(binding.channelTradeNo())
                        ||!origin.channelPaidAt().isEqual(binding.paidAt())||origin.channelPaidAmount().compareTo(binding.originalPaidAmount())!=0
                        ||origin.channelPaidAmount().compareTo(binding.refundAmount())!=0||!"CNY".equals(binding.currency())
                        ||!event.eventId().equals(success.successEventId())||!text(n,"refundNo").equals(success.refundNo())
                        ||!text(n,"channelRefundNo").equals(success.channelRefundNo())||!at.isEqual(success.succeededAt())
                        ||!order.equals(success.orderId())||!store.equals(success.storeId())||!binding.paymentId().equals(success.paymentId())
                        ||n.path("refundAmount").decimalValue().compareTo(success.refundAmount())!=0
                        ||n.path("originalPaidAmount").decimalValue().compareTo(success.originalPaidAmount())!=0
                        ||origin.channelPaidAmount().compareTo(success.refundAmount())!=0)throw unavailable();
                var row=orders.lock(IDS.fromApi(order));var commit=db.committed(IDS.fromApi(order));
                if(row==null||commit==null||!refund.equals(Long.toString(commit.refundOrderId)))throw unavailable();
                boolean first=claims.tryClaim(consumerName(),event);
                if(commit.successEventId==null){
                    if(!first||commit.succeededAt!=null||row.refundedAmount.signum()!=0)throw unavailable();
                    Map<String,Object> v=values("order",IDS.fromApi(order),"store",IDS.fromApi(store),"refund",IDS.fromApi(refund),
                        "amount",success.refundAmount(),"event",IDS.fromApi(event.eventId()),"at",at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
                    if(db.projectSuccess(v)!=1||db.recordSuccess(v)!=1)throw unavailable();
                }else if(first||commit.successEventId!=IDS.fromApi(event.eventId())||commit.succeededAt==null
                        ||!at.isEqual(commit.succeededAt.atOffset(ZoneOffset.UTC))||row.refundedAmount.compareTo(success.refundAmount())!=0)throw unavailable();
                release.release(order,origin.reservationId(),store,refund,q);
            });
        }catch(ApiException known){throw known;}catch(Exception failure){throw unavailable();}
    }
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual()||n.path(field).asText().isBlank()||n.path(field).asText().codePoints().anyMatch(Character::isISOControl))throw unavailable();return n.path(field).asText();}
    private static Map<String,Object> values(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Ordinary refund projection unavailable");}
}
