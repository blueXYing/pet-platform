package com.petplatform.order.biz.apiimpl;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.*;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** Refund completion, ORDER progress, consumption claim and reservation release share one commit. */
public final class OrderMerchantRefundProjectionConsumer implements IntegrationEventConsumer {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    private final DataSource source;private final ScheduleCapacityGuardApi guard;
    private final OrderMerchantRejectFactsApi orders;private final RefundExecutionFactsApi refunds;
    private final ReservationRefundReleaseApi release;private final OrderMerchantStore store;
    private final OrderAutoConfirmStore current;private final JdbcOutboxConsumeGuard claims;private final TransactionTemplate tx;
    public OrderMerchantRefundProjectionConsumer(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
        OrderMerchantRejectFactsApi orders,RefundExecutionFactsApi refunds,ReservationRefundReleaseApi release){
        this.source=source;this.guard=guard;this.orders=orders;this.refunds=refunds;this.release=release;
        store=new OrderMerchantStore(source);current=new OrderAutoConfirmStore(source);claims=new JdbcOutboxConsumeGuard(source,ids);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    public String consumerName(){return "ORDER_MERCHANT_REFUND";}
    public Set<String> eventTypes(){return Set.of("RefundSucceededEvent.v1");}
    public void consume(DispatchedEvent event){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        try{
            if(event==null||event.eventVersion()!=1||!eventTypes().contains(event.eventType())||!"REFUND".equals(event.aggregateType()))throw unavailable();
            JsonNode n=JSON.readTree(event.payloadJson());Set<String> keys=new HashSet<>();n.fieldNames().forEachRemaining(keys::add);
            if(!n.isObject()||!keys.equals(Set.of("refundOrderId","refundNo","orderId","refundType","refundSource","refundAmount","originalPaidAmount","channelRefundNo","succeededAt")))throw unavailable();
            String refund=text(n,"refundOrderId"),order=text(n,"orderId");IDS.fromApi(order);IDS.fromApi(text(n,"refundNo"));IDS.fromApi(event.eventId());
            if("AFTERSALE_DECISION".equals(text(n,"refundSource")))return;
            if(IDS.fromApi(refund)!=event.aggregateId()||!"FULL".equals(text(n,"refundType"))||!n.path("refundAmount").isNumber()||!n.path("originalPaidAmount").isNumber())throw unavailable();
            OffsetDateTime at=OffsetDateTime.parse(text(n,"succeededAt"));PublicContractChecks.requireMillisecondPrecision(at);
            if(event.occurredAt()==null||!at.isEqual(event.occurredAt()))throw unavailable();
            String sourceType=text(n,"refundSource");if(Set.of("LATE_PAYMENT_TIMEOUT","MERCHANT_APPROVED","MERCHANT_TIMEOUT_AUTO","PRESTART_AUTO").contains(sourceType))return;
            if(!"MERCHANT_REJECT_ORDER".equals(sourceType))throw unavailable();
            QueryContext ctx=new QueryContext(event.traceId(),OperatorType.SYSTEM,null);String storeId=orders.locateStore(order,ctx);
            tx.executeWithoutResult(s -> {
                guard.acquire(List.of(storeId),ctx);guard.requireHeld(storeId,source);
                var binding=refunds.requireForChannel(refund,storeId,ctx);var origin=orders.requireRejected(order,binding.paymentId(),storeId,ctx);
                var success=refunds.requireSucceeded(refund,order,storeId,ctx);
                if(!sourceType.equals(binding.sourceType())||!sourceType.equals(success.refundSource())||!origin.sourceEventId().equals(binding.sourceEventId())
                  ||!refund.equals(origin.refundOrderId())||!origin.merchantId().equals(binding.merchantId())||!origin.userId().equals(binding.userId())
                  ||!origin.paymentSuccessEventId().equals(binding.paymentSuccessEventId())||!origin.channelTradeNo().equals(binding.channelTradeNo())
                  ||!origin.channelPaidAt().isEqual(binding.paidAt())||origin.channelPaidAmount().compareTo(binding.originalPaidAmount())!=0
                  ||!event.eventId().equals(success.successEventId())||!text(n,"refundNo").equals(success.refundNo())
                  ||!text(n,"channelRefundNo").equals(success.channelRefundNo())||!at.isEqual(success.succeededAt())
                  ||n.path("refundAmount").decimalValue().compareTo(success.refundAmount())!=0
                  ||n.path("originalPaidAmount").decimalValue().compareTo(success.originalPaidAmount())!=0
                  ||origin.channelPaidAmount().compareTo(success.refundAmount())!=0)throw unavailable();
                boolean first=claims.tryClaim(consumerName(),event);
                var row=current.lock(IDS.fromApi(order));if(row==null)throw unavailable();
                if(row.refundedAmount.signum()==0){if(!first||store.mapper().project(IDS.fromApi(order),IDS.fromApi(refund),success.refundAmount())!=1)throw unavailable();}
                else if(row.refundedAmount.compareTo(success.refundAmount())!=0)throw unavailable();
                release.release(order,origin.reservationId(),storeId,refund,ctx);
            });
        }catch(ApiException known){throw known;}catch(Exception failure){throw unavailable();}
    }
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual()||n.path(field).asText().isBlank())throw unavailable();return n.path(field).asText();}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Merchant refund projection unavailable");}
}
