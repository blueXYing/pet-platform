package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.query.OrderAdminCloseFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderAdminDispositionStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderAutoConfirmStore;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationRefundReleaseApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/**
 * ADMIN_ABNORMAL_CLOSE refund completion projection: refunded_amount, consumption claim and
 * reservation release share one commit — the exact discipline of the merchant-reject projection
 * (contract-63 v0.2, doc 33 ruling). Other refund families stay with their own consumers.
 */
public final class AdminOrderRefundProjectionConsumer implements IntegrationEventConsumer {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DecimalPublicIdCodec IDS=new DecimalPublicIdCodec();
    /** Known refund sources owned by other consumers; this consumer ignores them silently. */
    private static final Set<String> OTHER_FAMILIES=Set.of("LATE_PAYMENT_TIMEOUT","MERCHANT_REJECT_ORDER",
        "MERCHANT_APPROVED","MERCHANT_TIMEOUT_AUTO","PRESTART_AUTO","AFTERSALE_DECISION");
    private final DataSource source;private final ScheduleCapacityGuardApi guard;
    private final OrderAdminCloseFactsApi orders;private final RefundExecutionFactsApi refunds;
    private final ReservationRefundReleaseApi release;private final OrderAdminDispositionStore store;
    private final OrderAutoConfirmStore current;private final JdbcOutboxConsumeGuard claims;private final TransactionTemplate tx;

    public AdminOrderRefundProjectionConsumer(DataSource source,SnowflakeIdGenerator ids,ScheduleCapacityGuardApi guard,
        OrderAdminCloseFactsApi orders,RefundExecutionFactsApi refunds,ReservationRefundReleaseApi release){
        this.source=source;this.guard=guard;this.orders=orders;this.refunds=refunds;this.release=release;
        store=new OrderAdminDispositionStore(source);current=new OrderAutoConfirmStore(source);claims=new JdbcOutboxConsumeGuard(source,ids);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);
    }
    public String consumerName(){return "ORDER_ADMIN_REFUND";}
    public Set<String> eventTypes(){return Set.of("RefundSucceededEvent.v1");}
    public void consume(DispatchedEvent event){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        try{
            if(event==null||event.eventVersion()!=1||!eventTypes().contains(event.eventType())||!"REFUND".equals(event.aggregateType()))throw unavailable();
            JsonNode n=JSON.readTree(event.payloadJson());Set<String> keys=new HashSet<>();n.fieldNames().forEachRemaining(keys::add);
            if(!n.isObject()||!keys.equals(Set.of("refundOrderId","refundNo","orderId","refundType","refundSource","refundAmount","originalPaidAmount","channelRefundNo","succeededAt")))throw unavailable();
            String refund=text(n,"refundOrderId"),order=text(n,"orderId");IDS.fromApi(order);IDS.fromApi(text(n,"refundNo"));IDS.fromApi(event.eventId());
            if(IDS.fromApi(refund)!=event.aggregateId()||!"FULL".equals(text(n,"refundType"))||!n.path("refundAmount").isNumber()||!n.path("originalPaidAmount").isNumber())throw unavailable();
            OffsetDateTime at=OffsetDateTime.parse(text(n,"succeededAt"));PublicContractChecks.requireMillisecondPrecision(at);
            if(event.occurredAt()==null||!at.isEqual(event.occurredAt()))throw unavailable();
            String sourceType=text(n,"refundSource");
            if(OTHER_FAMILIES.contains(sourceType))return;
            if(!"ADMIN_ABNORMAL_CLOSE".equals(sourceType))throw unavailable();
            QueryContext ctx=new QueryContext(event.traceId(),OperatorType.SYSTEM,null);String storeId=orders.locateStore(order,ctx);
            tx.executeWithoutResult(s -> {
                guard.acquire(List.of(storeId),ctx);guard.requireHeld(storeId,source);
                var binding=refunds.requireForChannel(refund,storeId,ctx);var origin=orders.requireClosed(order,binding.paymentId(),storeId,ctx);
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
                if(row.refundedAmount.signum()==0){if(!first||store.mapper().projectRefund(IDS.fromApi(order),IDS.fromApi(refund),success.refundAmount())!=1)throw unavailable();}
                else if(row.refundedAmount.compareTo(success.refundAmount())!=0)throw unavailable();
                release.release(order,origin.reservationId(),storeId,refund,ctx);
            });
        }catch(ApiException known){throw known;}catch(Exception failure){throw unavailable();}
    }
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual()||n.path(field).asText().isBlank())throw unavailable();return n.path(field).asText();}
    private static ApiException unavailable(){return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Admin refund projection unavailable");}
}
