package com.petplatform.payment.biz.center.demo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPaymentSuccessFact;
import com.petplatform.payment.api.query.PaymentCenterFactsApi;
import com.petplatform.payment.biz.center.PaymentCenterBizPolicy;
import com.petplatform.payment.biz.center.PaymentCenterReceiptWriter;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentDemoStore.DemoRow;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 75 §7 DEMO confirmation. The event is only a wake-up: inside the consume-log transaction the
 * authoritative success is re-read through PaymentCenterFactsApi, then the DEMO order moves CREATED → PAID
 * (or AMOUNT_MISMATCH when the real paid amount differs). Other bizTypes are recorded as consumed (NOOP).
 */
public final class DemoPaymentConfirmConsumer implements IntegrationEventConsumer {
    public static final String CONSUMER = "payment-demo-confirm";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final Set<String> FIELDS = Set.of("paymentOrderId", "paymentNo", "bizType", "bizNo",
            "channelTradeNo", "paidAmount", "paidAt");
    private final JdbcOutboxConsumeGuard claims;
    private final PaymentCenterFactsApi facts;
    private final PaymentDemoStore store;
    private final TransactionTemplate tx;

    public DemoPaymentConfirmConsumer(DataSource source, JdbcOutboxConsumeGuard claims, PaymentCenterFactsApi facts) {
        this.claims = Objects.requireNonNull(claims);
        this.facts = Objects.requireNonNull(facts);
        this.store = new PaymentDemoStore(Objects.requireNonNull(source));
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(PaymentCenterReceiptWriter.EVENT_TYPE); }

    @Override public void consume(DispatchedEvent event) {
        Objects.requireNonNull(event);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable("DEMO confirm needs its own transaction");
        Decoded decoded = decode(event);
        try {
            tx.executeWithoutResult(status -> apply(event, decoded));
        } catch (ApiException known) { throw known; }
        catch (RuntimeException failure) { throw unavailable("DEMO confirm unavailable"); }
    }

    private void apply(DispatchedEvent event, Decoded decoded) {
        if (!claims.tryClaim(CONSUMER, event)) return; // an earlier delivery already succeeded
        if (!PaymentCenterBizPolicy.DEMO.equals(decoded.bizType())) return; // not this owner's business type
        CenterPaymentSuccessFact fact = facts.requireSucceeded(PaymentCenterBizPolicy.DEMO, decoded.bizNo(),
                new QueryContext(event.traceId(), OperatorType.SYSTEM, null));
        if (!fact.paymentId().equals(Long.toString(event.aggregateId())) || !fact.successEventId().equals(event.eventId())
                || fact.paidAmount().compareTo(decoded.paidAmount()) != 0)
            throw unavailable("DEMO event does not match the authoritative payment");
        DemoRow demo = store.mapper.selectDemoByNoForUpdate(decoded.demoNo());
        if (demo == null) throw unavailable("DEMO order is missing");
        if (!"CREATED".equals(demo.status())) return; // already decided; never rewrite
        if (!Long.toString(demo.userId()).equals(fact.userId())) throw unavailable("DEMO payer does not match");
        String status = fact.paidAmount().compareTo(demo.amount()) == 0 ? "PAID" : "AMOUNT_MISMATCH";
        if (store.mapper.markDemoResult(PaymentFoundationStore.values("status", status,
                "paymentId", IDS.fromApi(fact.paymentId()), "paidAmount", fact.paidAmount(),
                "paidAt", PaymentFoundationStore.utc(fact.paidAt()), "eventId", IDS.fromApi(event.eventId()),
                "id", demo.id())) != 1) throw unavailable("DEMO confirm compare-and-set failed");
    }

    Decoded decode(DispatchedEvent event) {
        if (!PaymentCenterReceiptWriter.EVENT_TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || !"PAYMENT".equals(event.aggregateType()) || event.aggregateId() <= 0)
            throw unavailable("unexpected payment center event");
        try {
            JsonNode node = JSON.readTree(event.payloadJson());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(FIELDS)) throw new IllegalArgumentException();
            for (String field : Set.of("paymentOrderId", "paymentNo", "bizType", "bizNo", "channelTradeNo", "paidAt"))
                if (!node.path(field).isTextual() || node.path(field).textValue().isBlank())
                    throw new IllegalArgumentException();
            if (IDS.fromApi(node.path("paymentOrderId").textValue()) != event.aggregateId())
                throw new IllegalArgumentException();
            IDS.fromApi(node.path("paymentNo").textValue());
            IDS.fromApi(event.eventId());
            if (!node.path("paidAmount").isNumber()) throw new IllegalArgumentException();
            BigDecimal paid = node.path("paidAmount").decimalValue();
            if (paid.signum() <= 0 || paid.scale() > 2) throw new IllegalArgumentException();
            OffsetDateTime.parse(node.path("paidAt").textValue());
            String bizType = node.path("bizType").textValue();
            String bizNo = node.path("bizNo").textValue();
            long demoNo = PaymentCenterBizPolicy.DEMO.equals(bizType) ? IDS.fromApi(bizNo) : 0L;
            return new Decoded(bizType, bizNo, demoNo, paid);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw unavailable("PaymentCenterSucceeded payload is invalid");
        }
    }

    record Decoded(String bizType, String bizNo, long demoNo, BigDecimal paidAmount) {}

    private static ApiException unavailable(String detail) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, detail);
    }
}
