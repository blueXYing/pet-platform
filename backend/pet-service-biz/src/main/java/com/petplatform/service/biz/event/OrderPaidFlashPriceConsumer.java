package com.petplatform.service.biz.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.service.biz.infrastructure.persistence.FlashPriceStore;
import com.petplatform.service.biz.infrastructure.persistence.entity.FlashPriceEntities.ReservationRow;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import javax.sql.DataSource;

/**
 * Strict consumer of OrderPaidEvent.v1 (contract 77 §6.2): the RESERVED ledger row of the paid
 * order becomes CONSUMED. Idempotent by design — an already-CONSUMED row, a missing row or a
 * RELEASED row (late payment after the 5-minute tolerance) are all NOOPs, and a released quota
 * is never given back (77 §6.4). The consume-log claim and the CAS commit together in the
 * consumer's own transaction.
 */
public final class OrderPaidFlashPriceConsumer implements IntegrationEventConsumer {

    public static final String TYPE = "OrderPaidEvent.v1";
    private static final Set<String> FIELDS = Set.of(
            "orderId", "reservationId", "couponInstanceId", "paidAt", "confirmDeadline");
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final FlashPriceStore store;
    private final BiPredicate<String, DispatchedEvent> consumeGuard;
    private final Clock clock;

    public OrderPaidFlashPriceConsumer(
            FlashPriceStore store,
            BiPredicate<String, DispatchedEvent> consumeGuard,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.consumeGuard = Objects.requireNonNull(consumeGuard, "consumeGuard is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Composition entry for the boot assembler; no persistence type crosses the module edge. */
    public OrderPaidFlashPriceConsumer(
            DataSource dataSource,
            SnowflakeIdGenerator ids,
            BiPredicate<String, DispatchedEvent> consumeGuard,
            Clock clock) {
        this(new FlashPriceStore(dataSource, ids), consumeGuard, clock);
    }

    @Override
    public String consumerName() {
        return "service.flash-price.order-paid.v1";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void consume(DispatchedEvent event) {
        if (event == null || !TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || !"ORDER".equals(event.aggregateType()) || event.occurredAt() == null) {
            throw invalid();
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(event.payloadJson());
        } catch (Exception failure) {
            throw invalid(); // never retain parser exceptions carrying raw payloads
        }
        if (payload == null || !payload.isObject()) throw invalid();
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(FIELDS)) throw invalid();
        long orderId = publicId(text(payload, "orderId"));
        publicId(text(payload, "reservationId")); // shape-checked; schedule domain owns it
        JsonNode coupon = payload.get("couponInstanceId");
        if (coupon != null && !coupon.isNull()) publicId(text(payload, "couponInstanceId"));
        if (event.aggregateId() != orderId) throw invalid();
        time(payload, "paidAt");
        time(payload, "confirmDeadline");
        store.execute(mapper -> {
            // Claim inside this transaction: consume_log and the CAS commit or roll back together.
            if (!consumeGuard.test(consumerName(), event)) return null;
            ReservationRow row = mapper.selectReservationByOrderId(orderId);
            if (row == null || !"RESERVED".equals(row.status)) {
                // Missing or already settled (CONSUMED / RELEASED incl. late payment): NOOP.
                return null;
            }
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (mapper.markConsumedByOrderId(orderId, now) != 1) {
                // Lost a race with a concurrent delivery: the other writer settles it — NOOP.
                return null;
            }
            return null;
        });
    }

    private static OffsetDateTime time(JsonNode payload, String field) {
        try {
            String value = text(payload, field);
            if (!value.matches(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")) {
                throw invalid();
            }
            return OffsetDateTime.parse(value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static long publicId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw invalid();
            return id;
        } catch (NumberFormatException tooLarge) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid OrderPaidEvent for the flash-price ledger");
    }
}
