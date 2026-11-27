package com.petplatform.payment.biz.settlement;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.api.query.OrderSettlementFactsApi;
import com.petplatform.payment.api.dto.SettlementTypes.RegisterSettlementCommand;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentCenterStore.CenterRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 78 §6.2 Transactional Outbox consumers driving ORDER settlement lifecycle:
 * <ul>
 *   <li>{@link OnPaymentSucceeded}: {@code PaymentSucceededEvent.v1} -> register {@code PENDING_TRIGGER}
 *       (for {@code settle_type='1'} only; legacy {@code 0}/NULL rows are NOOP);
 *   <li>{@link OnOrderVerified}: {@code OrderVerifiedEvent.v2} -> snapshot {@code d}, set {@code eligible_at},
 *       move to {@code WAITING} and enqueue {@code SETTLEMENT_DUE};
 *   <li>{@link OnRefundCreated}: {@code RefundOrderCreatedEvent.v1} -> pause {@code WAITING} to {@code ON_HOLD};
 *   <li>{@link OnRefundSucceeded}: {@code RefundSucceededEvent.v1} -> cancel or proportionally recalculate.
 * </ul>
 */
public final class SettlementEventConsumers {
    public static final String BOOKING_ORDER_BIZ_TYPE = "BOOKING_ORDER";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private SettlementEventConsumers() {}

    public static final class OnPaymentSucceeded implements IntegrationEventConsumer {
        public static final String CONSUMER = "payment-settlement-on-paid";
        private final JdbcOutboxConsumeGuard claims;
        private final PaymentCenterStore payments;
        private final OrderSettlementFactsApi orderFacts;
        private final SettlementAdminService settlement;
        private final TransactionTemplate tx;

        public OnPaymentSucceeded(DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            this.claims = Objects.requireNonNull(claims, "claims");
            this.payments = new PaymentCenterStore(Objects.requireNonNull(source, "source"));
            this.orderFacts = Objects.requireNonNull(orderFacts, "orderFacts");
            this.settlement = Objects.requireNonNull(settlement, "settlement");
            this.tx = requiresNewTx(source);
        }

        @Override public String consumerName() { return CONSUMER; }
        @Override public Set<String> eventTypes() { return Set.of("PaymentSucceededEvent.v1"); }

        @Override
        public void consume(DispatchedEvent event) {
            noOuterTx();
            JsonNode payload = parseObject(event, "PaymentSucceededEvent.v1", 1);
            String paymentIdStr = requireText(payload, "paymentOrderId");
            String orderIdStr = requireText(payload, "orderId");
            long paymentId = parseId(paymentIdStr);
            parseId(orderIdStr);

            tx.executeWithoutResult(status -> {
                if (!claims.tryClaim(CONSUMER, event)) return;
                CenterRow payRow = payments.center.selectCenterById(paymentId);
                if (payRow == null || !"PAID".equals(payRow.status())) {
                    throw unavailable("payment row not PAID for settlement registration");
                }
                // Legacy/ordinary settlement rows (settle_type=0 or NULL) do not enter split ledger.
                if (!"1".equals(payRow.settleType())) return;
                OrderSettlementFact fact = orderFacts.readForSettlement(
                        orderIdStr, new QueryContext(event.traceId(), OperatorType.SYSTEM, null));
                // Late payment on an already-canceled order is auto-refunded in full; never settled.
                if (fact.orderCanceled()) return;
                BigDecimal paidAmount = payRow.paidAmount() != null ? payRow.paidAmount() : fact.payAmount();
                settlement.registerForPayment(new RegisterSettlementCommand(
                        new CommandContext(event.eventId(), event.traceId(), OperatorType.SYSTEM, null, "SETTLEMENT_EVENT"),
                        paymentIdStr,
                        orderIdStr,
                        BOOKING_ORDER_BIZ_TYPE,
                        orderIdStr,
                        fact.merchantId(),
                        fact.storeId(),
                        fact.originalPrice(),
                        fact.flashDiscount(),
                        // Contract 79 section 6.2 (78 section 3): the merchant-funded part of the
                        // coupon discount leaves the split base; the rest of the discount stays
                        // the platform-funded snapshot. Pre-P3 rows book 0.00 here, as before.
                        fact.merchantCouponDiscount(),
                        fact.discountAmount().subtract(fact.merchantCouponDiscount()).max(BigDecimal.ZERO),
                        paidAmount,
                        event.eventId()));
            });
        }
    }

    public static final class OnOrderVerified implements IntegrationEventConsumer {
        public static final String CONSUMER = "payment-settlement-on-verified";
        private final JdbcOutboxConsumeGuard claims;
        private final OrderSettlementFactsApi orderFacts;
        private final SettlementAdminService settlement;
        private final TransactionTemplate tx;

        public OnOrderVerified(DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            this.claims = Objects.requireNonNull(claims, "claims");
            this.orderFacts = Objects.requireNonNull(orderFacts, "orderFacts");
            this.settlement = Objects.requireNonNull(settlement, "settlement");
            this.tx = requiresNewTx(source);
        }

        @Override public String consumerName() { return CONSUMER; }
        @Override public Set<String> eventTypes() { return Set.of("OrderVerifiedEvent.v2"); }

        @Override
        public void consume(DispatchedEvent event) {
            noOuterTx();
            JsonNode payload = parseObject(event, "OrderVerifiedEvent.v2", 2);
            String orderIdStr = requireText(payload, "orderId");
            long orderId = parseId(orderIdStr);
            OffsetDateTime eventVerifiedAt = parseTime(requireText(payload, "verifiedAt"));
            long eventId = parseId(event.eventId());

            tx.executeWithoutResult(status -> {
                if (!claims.tryClaim(CONSUMER, event)) return;
                OrderSettlementFact fact = orderFacts.readForSettlement(
                        orderIdStr, new QueryContext(event.traceId(), OperatorType.SYSTEM, null));
                if (!fact.verified()) {
                    throw unavailable("ORDER verification fact not committed");
                }
                OffsetDateTime authoritativeTime = fact.verifiedAt() != null ? fact.verifiedAt() : eventVerifiedAt;
                settlement.markVerifiedByOrderId(orderId, authoritativeTime, eventId);
            });
        }
    }

    public static final class OnRefundCreated implements IntegrationEventConsumer {
        public static final String CONSUMER = "payment-settlement-on-refund-created";
        private final JdbcOutboxConsumeGuard claims;
        private final SettlementAdminService settlement;
        private final TransactionTemplate tx;

        public OnRefundCreated(DataSource source, JdbcOutboxConsumeGuard claims,
                SettlementAdminService settlement) {
            this.claims = Objects.requireNonNull(claims, "claims");
            this.settlement = Objects.requireNonNull(settlement, "settlement");
            this.tx = requiresNewTx(source);
        }

        @Override public String consumerName() { return CONSUMER; }
        @Override public Set<String> eventTypes() { return Set.of("RefundOrderCreatedEvent.v1"); }

        @Override
        public void consume(DispatchedEvent event) {
            noOuterTx();
            JsonNode payload = parseObject(event, "RefundOrderCreatedEvent.v1", 1);
            long orderId = parseId(requireText(payload, "orderId"));
            tx.executeWithoutResult(status -> {
                if (!claims.tryClaim(CONSUMER, event)) return;
                settlement.onRefundCreatedForOrder(orderId);
            });
        }
    }

    public static final class OnRefundSucceeded implements IntegrationEventConsumer {
        public static final String CONSUMER = "payment-settlement-on-refund-succeeded";
        private final JdbcOutboxConsumeGuard claims;
        private final OrderSettlementFactsApi orderFacts;
        private final SettlementAdminService settlement;
        private final TransactionTemplate tx;

        public OnRefundSucceeded(DataSource source, JdbcOutboxConsumeGuard claims,
                OrderSettlementFactsApi orderFacts, SettlementAdminService settlement) {
            this.claims = Objects.requireNonNull(claims, "claims");
            this.orderFacts = Objects.requireNonNull(orderFacts, "orderFacts");
            this.settlement = Objects.requireNonNull(settlement, "settlement");
            this.tx = requiresNewTx(source);
        }

        @Override public String consumerName() { return CONSUMER; }
        @Override public Set<String> eventTypes() { return Set.of("RefundSucceededEvent.v1"); }

        @Override
        public void consume(DispatchedEvent event) {
            noOuterTx();
            JsonNode payload = parseObject(event, "RefundSucceededEvent.v1", 1);
            String orderIdStr = requireText(payload, "orderId");
            long orderId = parseId(orderIdStr);
            String refundType = requireText(payload, "refundType");
            JsonNode amtNode = payload.get("refundAmount");
            if (amtNode == null || !amtNode.isNumber()) throw unavailable("missing refundAmount");
            BigDecimal refundAmount = amtNode.decimalValue();
            if (refundAmount.signum() <= 0 || refundAmount.scale() > 2) {
                throw unavailable("invalid refundAmount");
            }

            tx.executeWithoutResult(status -> {
                if (!claims.tryClaim(CONSUMER, event)) return;
                OrderSettlementFact fact = orderFacts.readForSettlement(
                        orderIdStr, new QueryContext(event.traceId(), OperatorType.SYSTEM, null));
                BigDecimal effectiveRefunded = "FULL".equals(refundType)
                        ? fact.payAmount()
                        : fact.refundedAmount().max(refundAmount);
                boolean otherBlocker = fact.currentAfterSaleId() != null;
                settlement.onRefundSucceededForOrder(orderId, effectiveRefunded, otherBlocker);
            });
        }
    }

    private static TransactionTemplate requiresNewTx(DataSource source) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
        return tx;
    }

    private static void noOuterTx() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable("settlement event consumer requires no outer transaction");
        }
    }

    private static JsonNode parseObject(DispatchedEvent event, String expectedType, int expectedVersion) {
        if (event == null || !expectedType.equals(event.eventType())
                || event.eventVersion() != expectedVersion) {
            throw unavailable("unexpected event envelope for " + expectedType);
        }
        try {
            JsonNode node = JSON.readTree(event.payloadJson());
            if (node == null || !node.isObject()) throw unavailable("event payload is not JSON object");
            return node;
        } catch (ApiException known) {
            throw known;
        } catch (Exception bad) {
            throw unavailable("malformed event payload for " + expectedType);
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.textValue().isBlank()) {
            throw unavailable("missing event field: " + field);
        }
        return v.textValue();
    }

    private static long parseId(String raw) {
        try { return IDS.fromApi(raw); }
        catch (IllegalArgumentException bad) { throw unavailable("invalid snowflake id in event"); }
    }

    private static OffsetDateTime parseTime(String raw) {
        try { return OffsetDateTime.parse(raw); }
        catch (Exception bad) { throw unavailable("invalid timestamp in event"); }
    }

    private static ApiException unavailable(String msg) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, msg);
    }
}
