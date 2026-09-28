package com.petplatform.order.biz.apiimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PublicContractChecks;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.order.api.command.OrderPaymentResultApi;
import com.petplatform.order.api.dto.OrderPaymentResultTypes.ConsumePaymentResult;
import com.petplatform.order.api.dto.OrderPaymentResultTypes.ConsumePaymentSucceededCommand;
import com.petplatform.order.biz.application.OrderPaymentCommitProof;
import com.petplatform.order.biz.infrastructure.persistence.OrderExpiryStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore.OrderRow;
import com.petplatform.order.biz.infrastructure.persistence.OrderPaymentStore.ResultRow;
import com.petplatform.payment.api.dto.PaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentSuccessFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.command.ReservationExpiryApi;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PaymentSucceeded consumes only after a guarded current PAYMENT success proof. */
public final class OrderPaymentResultApiImpl implements OrderPaymentResultApi, IntegrationEventConsumer {
    private static final String SOURCE_TYPE = "PaymentSucceededEvent.v1";
    private static final String CONSUMER = "ORDER_PAYMENT_SUCCEEDED";
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource source;
    private final SnowflakeIdGenerator ids;
    private final ScheduleCapacityGuardApi guard;
    private final PaymentSuccessFactsApi payments;
    private final ReservationConfirmApi confirmation;
    private final ReservationExpiryApi expiry;
    private final IntegrationEventPublisher outbox;
    private final JdbcOutboxConsumeGuard consumeGuard;
    private final OrderPaymentStore orders;
    private final OrderExpiryStore expiredOrders;
    private final TransactionTemplate transaction;

    public OrderPaymentResultApiImpl(DataSource source, SnowflakeIdGenerator ids,
            ScheduleCapacityGuardApi guard, PaymentSuccessFactsApi payments,
            ReservationConfirmApi confirmation, ReservationExpiryApi expiry,
            IntegrationEventPublisher outbox) {
        this.source = Objects.requireNonNull(source);
        this.ids = Objects.requireNonNull(ids);
        this.guard = Objects.requireNonNull(guard);
        this.payments = Objects.requireNonNull(payments);
        this.confirmation = Objects.requireNonNull(confirmation);
        this.expiry = Objects.requireNonNull(expiry);
        this.outbox = Objects.requireNonNull(outbox);
        this.consumeGuard = new JdbcOutboxConsumeGuard(source, ids);
        this.orders = new OrderPaymentStore(source);
        this.expiredOrders = new OrderExpiryStore(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(SOURCE_TYPE); }

    @Override public void consume(DispatchedEvent event) {
        Objects.requireNonNull(event);
        if (!SOURCE_TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || !"PAYMENT".equals(event.aggregateType())) throw unavailable("unexpected payment event");
        ConsumePaymentSucceededCommand command = decode(event);
        if (event.aggregateId() != IDS.fromApi(command.paymentId()))
            throw unavailable("payment event aggregate mismatch");
        apply(command, event);
    }

    @Override public ConsumePaymentResult consumePaymentSucceeded(
            ConsumePaymentSucceededCommand command) {
        return apply(command, null);
    }

    private ConsumePaymentResult apply(ConsumePaymentSucceededCommand command,
            DispatchedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable("ORDER payment result requires its own transaction");
        Input input = validate(command);
        try {
            ConsumePaymentResult result = transaction.execute(status -> process(input, event));
            if (result == null) throw unavailable("ORDER payment result is absent");
            return result;
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("ORDER payment result unavailable"); }
    }

    private ConsumePaymentResult process(Input input, DispatchedEvent event) {
        orders.sessionDefaults();
        List<OrderPaymentStore.Locator> located = orders.locate(input.orderId());
        if (located.size() != 1 || located.getFirst().storeId() <= 0)
            throw unavailable("ORDER payment target is absent");
        String storeId = IDS.toApi(located.getFirst().storeId());
        QueryContext context = new QueryContext(input.command().context().traceId(),
                OperatorType.SYSTEM, input.command().context().operatorId());
        guard.acquire(List.of(storeId), context);
        guard.requireHeld(storeId, source);
        OrderRow order = orders.lock(input.orderId());
        if (order == null || order.storeId() != located.getFirst().storeId()
                || order.userId() <= 0 || order.merchantId() <= 0 || order.reservationId() <= 0
                || order.paymentExpireAt() == null) throw unavailable("ORDER payment binding is invalid");
        PaymentSuccessFact payment = payments.requireSucceeded(input.command().paymentId(),
                input.command().orderId(), storeId, context);
        verifyPayment(input, order, payment, storeId);
        ResultRow previous = orders.lockResult(order.id());
        if (previous != null) {
            verifyPrevious(input, payment, previous);
            verifyPreviousOrder(input, order, previous, storeId, context);
            if (event != null && !consumeGuard.tryClaim(CONSUMER, event))
                return ConsumePaymentResult.NOOP;
            // A direct replay or an event with a new ID still revalidates the authoritative fact.
            return ConsumePaymentResult.NOOP;
        }
        if (event != null && !consumeGuard.tryClaim(CONSUMER, event))
            throw unavailable("payment event was consumed without an ORDER result");
        if ("PENDING_PAYMENT".equals(order.stage()))
            return markNormal(input, order, payment, storeId, context);
        if ("CANCELED".equals(order.stage()))
            return markLate(input, order, payment, storeId, context);
        throw unavailable("ORDER stage cannot consume a new payment success");
    }

    private ConsumePaymentResult markNormal(Input input, OrderRow order,
            PaymentSuccessFact payment, String storeId, QueryContext context) {
        if (!"INIT".equals(order.paymentStatus()) || !"UNVERIFIED".equals(order.verificationStatus())
                || order.cancelReason() != null || order.payAmount() == null
                || order.payAmount().compareTo(payment.paidAmount()) != 0
                || order.discountAmount() == null || order.discountAmount().signum() != 0) {
            throw unavailable("ORDER payable amount or status disagrees with PAYMENT");
        }
        OffsetDateTime confirmDeadline = payment.paidAt().plusMinutes(30);
        if (orders.markPaid(order, payment.paidAt(), confirmDeadline) != 1)
            throw unavailable("ORDER paid compare-and-set failed");
        String orderId = input.command().orderId();
        String reservationId = IDS.toApi(order.reservationId());
        OrderPaymentCommitProof.record(source, orderId, reservationId);
        confirmation.confirm(new ConfirmReservationCommand(
                new CommandContext("EVENT:PAYMENT_SUCCEEDED:" + orderId + ":" + reservationId,
                        input.command().context().traceId(), OperatorType.SYSTEM, null, "OUTBOX"),
                orderId, reservationId, storeId, 0, order.paymentExpireAt()));
        long resultId = nextId();
        orders.insertResult(resultId, order.id(), IDS.fromApi(payment.paymentId()),
                input.sourceEventId(), payment.channelTradeNo(), payment.paidAmount(),
                payment.paidAt(), "NORMAL");
        orders.statusLog(nextId(), order.id(), "PAYMENT", "INIT", "PAID",
                "PAYMENT_SUCCEEDED", input.command().context().requestId());
        orders.statusLog(nextId(), order.id(), "ORDER_STAGE", "PENDING_PAYMENT",
                "PENDING_CONFIRM", "ORDER_PAID", input.command().context().requestId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("reservationId", reservationId);
        payload.put("couponInstanceId", null);
        payload.put("paidAt", payment.paidAt().toString());
        payload.put("confirmDeadline", confirmDeadline.toString());
        outbox.publish(new IntegrationEvent<>(IDS.toApi(nextId()), "OrderPaidEvent.v1", 1,
                ordersNow(), "ORDER", orderId, input.command().context().traceId(), payload));
        return ConsumePaymentResult.NORMAL_PAID;
    }

    private ConsumePaymentResult markLate(Input input, OrderRow order,
            PaymentSuccessFact payment, String storeId, QueryContext context) {
        String reservationId = IDS.toApi(order.reservationId());
        if (!"PAYMENT_TIMEOUT".equals(order.cancelReason())
                || !"INIT".equals(order.paymentStatus())
                || !"UNVERIFIED".equals(order.verificationStatus())
                || !expiredOrders.hasExpiryLog(order.id(),
                        "TASK:RESERVATION_HOLD_EXPIRE:" + reservationId + ":0")) {
            throw unavailable("ORDER cancellation is not a proven payment timeout");
        }
        expiry.assertExpired(input.command().orderId(), reservationId, storeId, context);
        if (orders.recordLatePayment(order, payment.paidAt()) != 1)
            throw unavailable("ORDER late payment compare-and-set failed");
        orders.insertResult(nextId(), order.id(), IDS.fromApi(payment.paymentId()),
                input.sourceEventId(), payment.channelTradeNo(), payment.paidAmount(),
                payment.paidAt(), "LATE");
        orders.statusLog(nextId(), order.id(), "PAYMENT", "INIT", "PAID",
                "LATE_PAYMENT_SUCCEEDED", input.command().context().requestId());
        OffsetDateTime detectedAt = ordersNow();
        outbox.publish(new IntegrationEvent<>(IDS.toApi(nextId()),
                "LatePaymentSucceededAfterTimeoutEvent.v1", 1, detectedAt, "ORDER",
                input.command().orderId(), input.command().context().traceId(),
                Map.of("orderId", input.command().orderId(),
                        "paymentOrderId", payment.paymentId(), "paymentNo", payment.paymentNo(),
                        "channelPaidAmount", payment.paidAmount(),
                        "channelPaidAt", payment.paidAt().toString(),
                        "detectedAt", detectedAt.toString())));
        return ConsumePaymentResult.LATE_PAYMENT;
    }

    private static void verifyPayment(Input input, OrderRow order, PaymentSuccessFact payment,
            String storeId) {
        if (payment == null || !input.command().paymentId().equals(payment.paymentId())
                || !input.command().orderId().equals(payment.orderId())
                || !storeId.equals(payment.storeId())
                || !IDS.toApi(order.userId()).equals(payment.userId())
                || !IDS.toApi(order.merchantId()).equals(payment.merchantId())
                || !input.command().sourceEventId().equals(payment.successEventId())
                || !input.command().channelTradeNo().equals(payment.channelTradeNo())
                || payment.paidAmount() == null
                || input.command().paidAmount().compareTo(payment.paidAmount()) != 0
                || payment.paidAt() == null
                || !input.command().paidAt().isEqual(payment.paidAt())
                || !"CNY".equals(payment.currency())) {
            throw unavailable("PAYMENT success proof disagrees with the event or ORDER binding");
        }
        try {
            IDS.fromApi(payment.paymentNo());
            PublicContractChecks.requireMillisecondPrecision(payment.paidAt());
        } catch (RuntimeException invalid) {
            throw unavailable("PAYMENT success fact is incomplete");
        }
        if (payment.paidAmount().signum() <= 0 || payment.paidAmount().scale() > 2)
            throw unavailable("PAYMENT amount precision is invalid");
    }

    private static void verifyPrevious(Input input, PaymentSuccessFact payment, ResultRow previous) {
        if (previous.paymentId() != IDS.fromApi(payment.paymentId())
                || previous.sourceEventId() != input.sourceEventId()
                || !Objects.equals(previous.channelTradeNo(), payment.channelTradeNo())
                || previous.paidAmount() == null
                || previous.paidAmount().compareTo(payment.paidAmount()) != 0
                || previous.paidAt() == null || !previous.paidAt().isEqual(payment.paidAt())
                || !("NORMAL".equals(previous.resultType())
                    || "LATE".equals(previous.resultType())))
            throw unavailable("ORDER payment result binding is inconsistent");
    }

    private void verifyPreviousOrder(Input input, OrderRow order, ResultRow previous,
            String storeId, QueryContext context) {
        if (!"PAID".equals(order.paymentStatus()))
            throw unavailable("ORDER payment state lost its recorded success");
        String reservationId = IDS.toApi(order.reservationId());
        if ("LATE".equals(previous.resultType())) {
            if (!"CANCELED".equals(order.stage())
                    || !"PAYMENT_TIMEOUT".equals(order.cancelReason())
                    || !"UNVERIFIED".equals(order.verificationStatus()))
                throw unavailable("ORDER late payment state is inconsistent");
            expiry.assertExpired(input.command().orderId(), reservationId, storeId, context);
        } else if ("PENDING_CONFIRM".equals(order.stage())) {
            if (!"UNVERIFIED".equals(order.verificationStatus()))
                throw unavailable("ORDER paid verification state is inconsistent");
            confirmation.assertConfirmed(input.command().orderId(), reservationId, storeId, context);
        } else if (!Set.of("PENDING_SERVICE", "COMPLETED", "CANCELED").contains(order.stage())
                || "PAYMENT_TIMEOUT".equals(order.cancelReason())) {
            throw unavailable("ORDER paid stage is inconsistent");
        }
    }

    private static ConsumePaymentSucceededCommand decode(DispatchedEvent event) {
        try {
            JsonNode node = JSON.readTree(event.payloadJson());
            if (!node.isObject() || !fields(node).equals(Set.of("paymentOrderId", "orderId",
                    "channelTradeNo", "paidAmount", "paidAt"))) throw new IllegalArgumentException();
            JsonNode amount = node.path("paidAmount");
            if (!node.path("paymentOrderId").isTextual() || !node.path("orderId").isTextual()
                    || !node.path("channelTradeNo").isTextual()
                    || !amount.isNumber() || !node.path("paidAt").isTextual())
                throw new IllegalArgumentException();
            return new ConsumePaymentSucceededCommand(
                    new CommandContext("EVENT:PAYMENT_SUCCEEDED:" + event.eventId(),
                            event.traceId(), OperatorType.SYSTEM, null, "OUTBOX"),
                    event.eventId(), node.path("paymentOrderId").asText(),
                    node.path("orderId").asText(), node.path("channelTradeNo").asText(),
                    amount.decimalValue(), OffsetDateTime.parse(node.path("paidAt").asText()));
        } catch (Exception malformed) {
            throw unavailable("PaymentSucceeded payload is invalid");
        }
    }

    private static Set<String> fields(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(names::add);
        return names;
    }

    private static Input validate(ConsumePaymentSucceededCommand command) {
        if (command == null || command.context() == null
                || command.context().operatorType() != OperatorType.SYSTEM)
            throw new ApiException(CommonApiCodes.FORBIDDEN, "only SYSTEM may consume payment results");
        try {
            PublicContractChecks.requireCommandRequestId(command.context());
            long eventId = IDS.fromApi(command.sourceEventId());
            long paymentId = IDS.fromApi(command.paymentId());
            long orderId = IDS.fromApi(command.orderId());
            if (!command.context().requestId().equals(
                    "EVENT:PAYMENT_SUCCEEDED:" + command.sourceEventId())
                    || command.channelTradeNo() == null || command.channelTradeNo().isBlank()
                    || command.channelTradeNo().length() > 128
                    || command.paidAmount() == null || command.paidAmount().signum() <= 0
                    || command.paidAmount().scale() > 2
                    || command.context().traceId() != null
                      && command.context().traceId().length() > 64)
                throw new IllegalArgumentException();
            PublicContractChecks.requireMillisecondPrecision(command.paidAt());
            return new Input(command, eventId, paymentId, orderId);
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT,
                    "invalid payment success command");
        }
    }

    private long nextId() {
        long id = ids.nextId();
        if (id <= 0) throw unavailable("ORDER ID provider unavailable");
        return id;
    }
    private OffsetDateTime ordersNow() {
        return expiredOrders.databaseNow().withOffsetSameInstant(ZoneOffset.UTC);
    }
    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
    private record Input(ConsumePaymentSucceededCommand command, long sourceEventId,
            long paymentId, long orderId) {}
}
