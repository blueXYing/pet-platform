package com.petplatform.event.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** W2-OUTBOX-002: crash, lease expiry, takeover and FAILED retry recover with original version/time/payload. */
class OutboxRecoveryMySqlTest {
    private final AtomicLong ids = new AtomicLong(300_000);
    private final OffsetDateTime occurredAt = OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 250_000_000, ZoneOffset.UTC);

    private String seed(MySqlEventTestDatabase db, long aggregateId) {
        var publisher = new TransactionalOutboxPublisher(db.dataSource(), ids::incrementAndGet, new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        tx.executeWithoutResult(status -> publisher.publish(new IntegrationEventProxy(
                "evt-" + aggregateId, "OrderPlaced.v1", 2, occurredAt, "ORDER", aggregateId, "trace-9",
                Map.of("total", "12.30")).self()));
        return "evt-" + aggregateId;
    }

    // Small local alias to keep event construction readable.
    private record IntegrationEventProxy(String eventId, String eventType, int version,
                                         OffsetDateTime occurredAt, String aggregateType, long aggregateId,
                                         String traceId, Map<String, Object> payload) {
        com.petplatform.event.api.IntegrationEvent<Map<String, Object>> self() {
            return new com.petplatform.event.api.IntegrationEvent<>(eventId, eventType, version, occurredAt,
                    aggregateType, Long.toUnsignedString(aggregateId), traceId, payload);
        }
    }

    @Test void expiredPublishingLeaseIsTakenOverWithOriginalFactsAndOldWriterIsFenced() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            String eventId = seed(db, 800_001);
            var repository = new JdbcOutboxRepository(db.dataSource());
            var claimA = repository.claim("worker-A", Duration.ofMillis(300));
            assertTrue(claimA.isPresent());
            assertEquals("PUBLISHING", db.jdbc().queryForObject(
                    "SELECT status FROM integration_event_outbox WHERE event_id=?", String.class, eventId));

            Thread.sleep(450); // lease expires; worker-A "crashed" without completing

            var claimB = repository.claim("worker-B", Duration.ofSeconds(30));
            assertTrue(claimB.isPresent());
            var leaseB = claimB.orElseThrow();
            assertEquals(eventId, leaseB.eventId());
            assertEquals(2, leaseB.eventVersion());
            assertEquals(occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), leaseB.occurredAt());
            assertEquals("worker-B", leaseB.owner());
            assertTrue(leaseB.payloadJson().contains("12.30"));

            // The stale worker-A cannot complete, fail or renew after the takeover.
            assertFalse(repository.markPublished(claimA.orElseThrow()));
            assertFalse(repository.markFailed(claimA.orElseThrow(), Duration.ofSeconds(1)));
            assertFalse(repository.heartbeat(claimA.orElseThrow(), Duration.ofSeconds(30)));

            assertTrue(repository.markPublished(leaseB));
            Map<String, Object> row = db.jdbc().queryForMap(
                    "SELECT status,published_at,lease_owner FROM integration_event_outbox WHERE event_id=?", eventId);
            assertEquals("PUBLISHED", row.get("status"));
            assertNull(row.get("lease_owner"));
            assertNotNull(row.get("published_at"));
        }
    }

    @Test void failedEventRetriesOnlyAfterBackoffAndKeepsRetryCount() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            String eventId = seed(db, 800_002);
            var repository = new JdbcOutboxRepository(db.dataSource());
            var lease = repository.claim("worker-A", Duration.ofSeconds(30)).orElseThrow();
            assertTrue(repository.markFailed(lease, Duration.ofMillis(150)));
            Map<String, Object> failed = db.jdbc().queryForMap(
                    "SELECT status,retry_count,next_retry_at FROM integration_event_outbox WHERE event_id=?", eventId);
            assertEquals("FAILED", failed.get("status"));
            assertEquals(1, ((Number) failed.get("retry_count")).intValue());
            assertNotNull(failed.get("next_retry_at"));

            assertTrue(repository.claim("worker-B", Duration.ofSeconds(30)).isEmpty(), "backoff not due yet");

            Thread.sleep(250);
            var retry = repository.claim("worker-B", Duration.ofSeconds(30));
            assertTrue(retry.isPresent());
            assertEquals(1, retry.orElseThrow().retryCount(), "takeover of a retry keeps retry_count");
            assertEquals(eventId, retry.orElseThrow().eventId());
        }
    }

    @Test void eventWithoutRegisteredConsumerIsReleasedDurablyNotFailed() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            String eventId = seed(db, 800_003);
            var repository = new JdbcOutboxRepository(db.dataSource());
            try (var dispatcher = new OutboxDispatcher(repository, "solo",
                    new OutboxDispatchSettings(Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofMillis(20), 4),
                    new OutboxRetryDelays(java.util.List.of(Duration.ofMillis(120))), java.util.List.of())) {
                assertEquals(OutboxDispatcher.Outcome.EMPTY, dispatcher.dispatchOne(),
                        "empty registry must not claim rows at all");
            }
            assertEquals("NEW", db.jdbc().queryForObject(
                    "SELECT status FROM integration_event_outbox WHERE event_id=?", String.class, eventId));
        }
    }
}
