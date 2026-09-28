package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.dto.OrderLatePaymentFact;
import com.petplatform.order.biz.infrastructure.persistence.OrderLateRefundStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderLateRefundStore.OrderProjection;
import com.petplatform.order.biz.infrastructure.persistence.OrderLateRefundStore.RefundProjection;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Projects only the approved late-payment FULL refund into ORDER-owned state. */
public final class OrderLateRefundProjectionConsumer implements IntegrationEventConsumer {
    private static final String CREATED = "RefundOrderCreatedEvent.v1";
    private static final String SUCCEEDED = "RefundSucceededEvent.v1";
    private static final String CONSUMER = "ORDER_LATE_REFUND";
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final RefundExecutionFactsApi refunds;
    private final OrderLatePaymentFactsApiImpl lateOrders;
    private final OrderLateRefundStore orders;
    private final JdbcOutboxConsumeGuard claims;
    private final TransactionTemplate transaction;

    public OrderLateRefundProjectionConsumer(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, ReservationExpiryApi expiry,
            RefundExecutionFactsApi refunds) {
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.refunds = Objects.requireNonNull(refunds);
        this.lateOrders = new OrderLatePaymentFactsApiImpl(source, guard, expiry);
        this.orders = new OrderLateRefundStore(source);
        this.claims = new JdbcOutboxConsumeGuard(source, ids);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(CREATED, SUCCEEDED); }

    @Override public void consume(DispatchedEvent event) {
        Objects.requireNonNull(event);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        if (event.eventVersion() != 1 || !"REFUND".equals(event.aggregateType())
                || !eventTypes().contains(event.eventType())) throw unavailable();
        long eventId = positive(event.eventId());
        EventPayload payload = decode(event);
        if (event.aggregateId() != positive(payload.refundOrderId())
                || event.occurredAt() == null
                || !event.occurredAt().isEqual(payload.occurredAt())) throw unavailable();
        try {
            transaction.executeWithoutResult(status -> project(event, eventId, payload));
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private void project(DispatchedEvent event, long eventId, EventPayload payload) {
        QueryContext context = new QueryContext(event.traceId(), OperatorType.SYSTEM, null);
        String storeId = lateOrders.locateStore(payload.orderId(), context);
        guard.acquire(List.of(storeId), context);
        guard.requireHeld(storeId, source);
        if (CREATED.equals(event.eventType())) projectCreated(event, eventId, payload, storeId, context);
        else projectSucceeded(event, eventId, payload, storeId, context);
    }

    private void projectCreated(DispatchedEvent event, long eventId, EventPayload payload,
            String storeId, QueryContext context) {
        RefundExecutionFact refund = refunds.requireForChannel(payload.refundOrderId(), storeId, context);
        OrderLatePaymentFact late = lateOrders.requireLatePayment(payload.orderId(),
                refund.paymentId(), storeId, context);
        verifyCreated(event, payload, refund, late, storeId);
        long orderId = positive(payload.orderId()), refundId = positive(payload.refundOrderId());
        OrderProjection order = requireOrder(orders.lockOrder(orderId), refundId);
        RefundProjection prior = orders.lockResult(orderId);
        if (prior != null) requireSame(prior, refundId, positive(refund.paymentId()), refund.refundAmount());
        if (prior != null && prior.createdEventId() != null) {
            if (prior.createdEventId() != eventId || claims.tryClaim(CONSUMER, event)) throw unavailable();
            return;
        }
        if (!claims.tryClaim(CONSUMER, event)) throw unavailable();
        if (prior == null) {
            if (order.refundOrderId() != null || order.refundedAmount().signum() != 0
                    || orders.bindOrder(orderId, refundId, null) != 1) throw unavailable();
            orders.insertCreated(orderId, refundId, positive(refund.paymentId()),
                    refund.refundAmount(), eventId);
            orders.statusLog(nextId(), orderId, null, "CREATED", "REFUND_ORDER_CREATED",
                    "EVENT:REFUND_ORDER_CREATED:" + event.eventId());
        } else if ("SUCCESS".equals(prior.refundStatus())
                && order.refundOrderId() != null && order.refundOrderId() == refundId
                && order.refundedAmount().compareTo(refund.refundAmount()) == 0) {
            // Event delivery may be reversed. Record the immutable created-event binding
            // without moving a successful ORDER projection back to CREATED.
            orders.noteCreatedAfterSuccess(orderId, eventId);
        } else throw unavailable();
    }

    private void projectSucceeded(DispatchedEvent event, long eventId, EventPayload payload,
            String storeId, QueryContext context) {
        RefundSuccessFact refund = refunds.requireSucceeded(payload.refundOrderId(),
                payload.orderId(), storeId, context);
        OrderLatePaymentFact late = lateOrders.requireLatePayment(payload.orderId(),
                refund.paymentId(), storeId, context);
        verifySucceeded(event, payload, refund, late, storeId);
        long orderId = positive(payload.orderId()), refundId = positive(payload.refundOrderId());
        OrderProjection order = requireOrder(orders.lockOrder(orderId), refundId);
        RefundProjection prior = orders.lockResult(orderId);
        if (prior != null) requireSame(prior, refundId, positive(refund.paymentId()), refund.refundAmount());
        if (prior != null && "SUCCESS".equals(prior.refundStatus())) {
            if (prior.successEventId() == null || prior.successEventId() != eventId
                    || prior.succeededAt() == null
                    || !prior.succeededAt().isEqual(refund.succeededAt())
                    || order.refundedAmount().compareTo(refund.refundAmount()) != 0
                    || claims.tryClaim(CONSUMER, event)) throw unavailable();
            return;
        }
        if (!claims.tryClaim(CONSUMER, event)) throw unavailable();
        if (order.refundedAmount().signum() != 0
                || orders.bindOrder(orderId, refundId, refund.refundAmount()) != 1) throw unavailable();
        if (prior == null) {
            orders.insertSucceeded(orderId, refundId, positive(refund.paymentId()),
                    refund.refundAmount(), eventId, refund.succeededAt());
        } else if ("CREATED".equals(prior.refundStatus())
                && prior.createdEventId() != null) {
            orders.markSucceeded(orderId, eventId, refund.succeededAt());
        } else throw unavailable();
        orders.statusLog(nextId(), orderId, prior == null ? null : "CREATED", "SUCCESS",
                "REFUND_SUCCEEDED", "EVENT:REFUND_SUCCEEDED:" + event.eventId());
    }

    private static OrderProjection requireOrder(OrderProjection row, long refundId) {
        if (row == null || !"CANCELED".equals(row.stage())
                || !"PAYMENT_TIMEOUT".equals(row.cancelReason())
                || !"PAID".equals(row.paymentStatus())
                || !"UNVERIFIED".equals(row.verificationStatus())
                || row.refundedAmount() == null || row.refundedAmount().signum() < 0
                || row.refundOrderId() != null && row.refundOrderId() != refundId) throw unavailable();
        return row;
    }

    private static void requireSame(RefundProjection prior, long refundId, long paymentId,
            BigDecimal amount) {
        if (prior.refundOrderId() != refundId || prior.paymentId() != paymentId
                || !"FULL".equals(prior.refundType())
                || !"LATE_PAYMENT_TIMEOUT".equals(prior.refundSource())
                || prior.refundAmount() == null || prior.refundAmount().compareTo(amount) != 0)
            throw unavailable();
    }

    private static void verifyCreated(DispatchedEvent event, EventPayload payload,
            RefundExecutionFact refund, OrderLatePaymentFact late, String storeId) {
        if (refund == null || late == null || !"FULL".equals(payload.refundType())
                || !"LATE_PAYMENT_TIMEOUT".equals(payload.source())
                || !payload.refundOrderId().equals(refund.refundOrderId())
                || !payload.refundNo().equals(refund.refundNo())
                || !payload.orderId().equals(refund.orderId())
                || !storeId.equals(refund.storeId())
                || !late.merchantId().equals(refund.merchantId())
                || !late.userId().equals(refund.userId())
                || !late.paymentId().equals(refund.paymentId())
                || !late.paymentSuccessEventId().equals(refund.paymentSuccessEventId())
                || !late.channelTradeNo().equals(refund.channelTradeNo())
                || !"CNY".equals(refund.currency())
                || refund.originalPaidAmount() == null
                || refund.originalPaidAmount().compareTo(late.channelPaidAmount()) != 0
                || refund.refundAmount() == null
                || refund.refundAmount().compareTo(late.channelPaidAmount()) != 0
                || payload.refundAmount().compareTo(refund.refundAmount()) != 0
                || refund.paidAt() == null || !refund.paidAt().isEqual(late.channelPaidAt())
                || !event.eventId().equals(refund.createdEventId())
                || refund.createdAt() == null || !refund.createdAt().isEqual(payload.occurredAt())
                || !Set.of("CREATED", "PROCESSING", "UNKNOWN", "SUCCESS").contains(refund.status())
                || refund.bindingVersion() < 0) throw unavailable();
        positive(refund.paymentNo()); positive(refund.lateEventId());
    }

    private static void verifySucceeded(DispatchedEvent event, EventPayload payload,
            RefundSuccessFact refund, OrderLatePaymentFact late, String storeId) {
        if (refund == null || late == null || !"FULL".equals(payload.refundType())
                || !"LATE_PAYMENT_TIMEOUT".equals(payload.source())
                || !payload.refundOrderId().equals(refund.refundOrderId())
                || !payload.refundNo().equals(refund.refundNo())
                || !payload.orderId().equals(refund.orderId())
                || !storeId.equals(refund.storeId())
                || !late.paymentId().equals(refund.paymentId())
                || refund.refundAmount() == null
                || refund.refundAmount().compareTo(late.channelPaidAmount()) != 0
                || refund.originalPaidAmount() == null
                || refund.originalPaidAmount().compareTo(late.channelPaidAmount()) != 0
                || payload.refundAmount().compareTo(refund.refundAmount()) != 0
                || payload.originalPaidAmount().compareTo(refund.originalPaidAmount()) != 0
                || !Objects.equals(payload.channelRefundNo(), refund.channelRefundNo())
                || refund.channelRefundNo() == null || refund.channelRefundNo().isBlank()
                || refund.succeededAt() == null
                || !refund.succeededAt().isEqual(payload.occurredAt())
                || !event.eventId().equals(refund.successEventId())
                || !"LATE_PAYMENT_TIMEOUT".equals(refund.refundSource())) throw unavailable();
    }

    private static EventPayload decode(DispatchedEvent event) {
        try {
            JsonNode node = JSON.readTree(event.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> keys = new HashSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            Set<String> expected = CREATED.equals(event.eventType())
                    ? Set.of("refundOrderId", "refundNo", "orderId", "refundType",
                            "refundAmount", "source", "createdAt")
                    : Set.of("refundOrderId", "refundNo", "orderId", "refundType",
                            "refundSource", "refundAmount", "originalPaidAmount",
                            "channelRefundNo", "succeededAt");
            if (!keys.equals(expected)) throw new IllegalArgumentException();
            String refundId = string(node, "refundOrderId"), refundNo = string(node, "refundNo");
            String orderId = string(node, "orderId"), refundType = string(node, "refundType");
            String source = string(node, CREATED.equals(event.eventType()) ? "source" : "refundSource");
            String channelRefundNo = CREATED.equals(event.eventType()) ? null : string(node, "channelRefundNo");
            OffsetDateTime occurredAt = OffsetDateTime.parse(string(node,
                    CREATED.equals(event.eventType()) ? "createdAt" : "succeededAt"));
            BigDecimal amount = amount(node, "refundAmount");
            BigDecimal original = CREATED.equals(event.eventType()) ? null : amount(node, "originalPaidAmount");
            positive(refundId); positive(refundNo); positive(orderId);
            PublicContractChecks.requireMillisecondPrecision(occurredAt);
            return new EventPayload(refundId, refundNo, orderId, refundType, source, amount,
                    original, channelRefundNo, occurredAt);
        } catch (Exception invalid) { throw unavailable(); }
    }

    private static String string(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException();
        String value = node.path(field).asText();
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
        return value;
    }
    private static BigDecimal amount(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw new IllegalArgumentException();
        BigDecimal value = node.path(field).decimalValue();
        if (value.signum() <= 0 || value.scale() > 2) throw new IllegalArgumentException();
        return value;
    }
    private static long positive(String id) {
        try { return IDS.fromApi(id); }
        catch (RuntimeException invalid) { throw unavailable(); }
    }
    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable();
        return id;
    }
    private static ApiException unavailable() {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                "ORDER late refund projection unavailable");
    }
    private record EventPayload(String refundOrderId, String refundNo, String orderId,
            String refundType, String source, BigDecimal refundAmount,
            BigDecimal originalPaidAmount, String channelRefundNo, OffsetDateTime occurredAt) {}
}
