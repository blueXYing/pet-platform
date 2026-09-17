package com.petplatform.event.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.event.core.mapper.OutboxMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends events to integration_event_outbox inside the caller's business
 * transaction (CCR-W0-001 decision 2): commit publishes both, rollback leaves
 * nothing. Publishing without an active transaction is rejected — the two-part
 * "commit business first, insert outbox later" pattern is forbidden.
 */
public final class TransactionalOutboxPublisher implements IntegrationEventPublisher {
    private final SqlSessionTemplate template;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper payloadCodec;

    public TransactionalOutboxPublisher(DataSource dataSource, SnowflakeIdGenerator ids, ObjectMapper payloadCodec) {
        this.template = EventMybatis.template(dataSource);
        this.ids = Objects.requireNonNull(ids, "PLAT-002 ID provider is required");
        this.payloadCodec = Objects.requireNonNull(payloadCodec);
    }

    @Override
    public void publish(IntegrationEvent<?> event) {
        Objects.requireNonNull(event, "event is required");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox append must join the caller's business transaction");
        }
        String eventType = text(event.eventType(), 128, "eventType");
        String aggregateType = text(event.aggregateType(), 64, "aggregateType");
        if (event.eventVersion() < 1) throw new IllegalArgumentException("eventVersion must be >= 1");
        if (event.occurredAt() == null) throw new IllegalArgumentException("occurredAt is required");
        long aggregateId = parseAggregateId(event.aggregateId());
        String traceId = event.traceId() == null ? null : text(event.traceId(), 64, "traceId");
        String eventId = event.eventId() == null || event.eventId().isBlank()
                ? Long.toUnsignedString(ids.nextId()) : text(event.eventId(), 64, "eventId");
        String payloadJson;
        try {
            payloadJson = payloadCodec.writeValueAsString(event.payload());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Event payload is not JSON-serializable", error);
        }
        long rowId = ids.nextId();
        if (rowId <= 0) throw new IllegalStateException("Invalid ID from provider");
        template.getMapper(OutboxMapper.class).insertOutbox(rowId, eventId, aggregateType, aggregateId,
                eventType, event.eventVersion(), payloadJson, event.occurredAt(), traceId);
    }

    private static String text(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maxLength + " characters");
        }
        return value;
    }

    private static long parseAggregateId(String aggregateId) {
        if (aggregateId == null || !aggregateId.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("aggregateId must be the numeric String form of the Snowflake id");
        }
        try {
            long value = Long.parseLong(aggregateId);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("aggregateId must be a positive BIGINT", error);
        }
    }
}
