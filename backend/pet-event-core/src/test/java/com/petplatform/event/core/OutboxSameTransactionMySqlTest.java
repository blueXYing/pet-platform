package com.petplatform.event.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** W2-OUTBOX-001: business fact and outbox row commit or roll back together; no orphan events. */
class OutboxSameTransactionMySqlTest {
    private final AtomicLong ids = new AtomicLong(200_000);
    private final OffsetDateTime occurredAt = OffsetDateTime.of(2026, 9, 14, 10, 15, 30, 123_000_000, ZoneOffset.UTC);

    private TransactionalOutboxPublisher publisher(MySqlEventTestDatabase db) {
        return new TransactionalOutboxPublisher(db.dataSource(), ids::incrementAndGet, new ObjectMapper());
    }

    private IntegrationEvent<Map<String, Object>> event(String eventType, String aggregateId) {
        return new IntegrationEvent<>(null, eventType, 1, occurredAt, "ORDER", aggregateId,
                "trace-1", Map.of("amount", "88.50"));
    }

    @Test void commitPublishesBusinessFactAndEventTogether() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            var publisher = publisher(db);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            SnowflakeIdGenerator idGen = ids::incrementAndGet;
            tx.executeWithoutResult(status -> {
                db.jdbc().update("INSERT INTO outbox_test_fact (id,consumer,event_id,created_at) VALUES (?,?,?,NOW(3))",
                        idGen.nextId(), "TEST", "evt-commit-1");
                publisher.publish(event("OrderPlaced.v1", "700001"));
            });
            Map<String, Object> row = db.jdbc().queryForMap(
                    "SELECT event_id,event_type,event_version,occurred_at,aggregate_type,aggregate_id,trace_id,payload,status FROM integration_event_outbox WHERE aggregate_id=700001");
            assertEquals("OrderPlaced.v1", row.get("event_type"));
            assertEquals(1, ((Number) row.get("event_version")).intValue());
            assertEquals("ORDER", row.get("aggregate_type"));
            assertEquals("NEW", row.get("status"));
            assertEquals("trace-1", row.get("trace_id"));
            // DATETIME(3) keeps millisecond precision; the session and fixture both run UTC.
            assertEquals(occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toInstant(),
                    ((java.time.LocalDateTime) row.get("occurred_at")).toInstant(ZoneOffset.UTC));
            assertTrue(row.get("event_id").toString().matches("\\d+"), "eventId is the generated Snowflake String");
            assertTrue(row.get("payload").toString().contains("88.50"));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM outbox_test_fact WHERE event_id='evt-commit-1'", Integer.class));
        }
    }

    @Test void rollbackLeavesNoPublishableEvent() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            var publisher = publisher(db);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                db.jdbc().update("INSERT INTO outbox_test_fact (id,consumer,event_id,created_at) VALUES (?,?,?,NOW(3))",
                        ids.incrementAndGet(), "TEST", "evt-rollback-1");
                publisher.publish(event("OrderPlaced.v1", "700002"));
                throw new RuntimeException("planned business failure");
            }));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM outbox_test_fact", Integer.class));
        }
    }

    @Test void publishingOutsideATransactionIsRejected() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            var publisher = publisher(db);
            assertThrows(IllegalStateException.class,
                    () -> publisher.publish(event("OrderPlaced.v1", "700003")));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
        }
    }

    @Test void envelopeValidationRejectsInvalidFacts() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            var publisher = publisher(db);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status ->
                    publisher.publish(new IntegrationEvent<>(null, "OrderPlaced.v1", 0,
                            occurredAt, "ORDER", "700004", null, Map.of()))));
            assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status ->
                    publisher.publish(new IntegrationEvent<>(null, "OrderPlaced.v1", 1,
                            null, "ORDER", "700005", null, Map.of()))));
            assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status ->
                    publisher.publish(new IntegrationEvent<>(null, "OrderPlaced.v1", 1,
                            occurredAt, "ORDER", "not-numeric", null, Map.of()))));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_outbox", Integer.class));
        }
    }
}
