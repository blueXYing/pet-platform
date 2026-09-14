package com.petplatform.event.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** W2-OUTBOX-003: duplicate dispatch replays nothing that already succeeded; partial failure redoes only failures. */
class OutboxConsumeIdempotencyMySqlTest {
    private final AtomicLong ids = new AtomicLong(400_000);
    private final OffsetDateTime occurredAt = OffsetDateTime.of(2026, 9, 14, 12, 0, 0, 0, ZoneOffset.UTC);

    /** Consumer whose transaction is claim + side effect, exactly like a real module consumer. */
    private static final class RecordingConsumer implements IntegrationEventConsumer {
        final String name;
        private final JdbcOutboxConsumeGuard guard;
        private final TransactionTemplate tx;
        private final JdbcTemplate jdbc;
        private final AtomicLong ids;
        final AtomicInteger executions = new AtomicInteger();
        volatile int failFirstExecutions;

        RecordingConsumer(String name, javax.sql.DataSource dataSource, AtomicLong ids) {
            this.name = name;
            this.ids = ids;
            this.guard = new JdbcOutboxConsumeGuard(dataSource, ids::incrementAndGet);
            this.jdbc = new JdbcTemplate(dataSource);
            this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }

        @Override public String consumerName() { return name; }
        @Override public Set<String> eventTypes() { return Set.of("OrderPlaced.v1"); }

        @Override public void consume(DispatchedEvent event) {
            tx.executeWithoutResult(status -> {
                if (!guard.tryClaim(name, event)) return; // already succeeded: idempotent skip
                if (executions.incrementAndGet() <= failFirstExecutions) {
                    throw new RuntimeException("planned consumer failure");
                }
                jdbc.update("INSERT INTO outbox_test_fact (id,consumer,event_id,created_at) VALUES (?,?,?,NOW(3))",
                        ids.incrementAndGet(), name, event.eventId());
            });
        }
    }

    private String seed(MySqlEventTestDatabase db, long aggregateId) {
        var publisher = new TransactionalOutboxPublisher(db.dataSource(), ids::incrementAndGet, new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        tx.executeWithoutResult(status -> publisher.publish(new com.petplatform.event.api.IntegrationEvent<>(
                null, "OrderPlaced.v1", 1, occurredAt, "ORDER", Long.toUnsignedString(aggregateId),
                null, Map.of("total", "66.00"))));
        return db.jdbc().queryForObject(
                "SELECT event_id FROM integration_event_outbox WHERE aggregate_id=?", String.class, aggregateId);
    }

    private OutboxDispatcher dispatcher(MySqlEventTestDatabase db, RecordingConsumer alpha, RecordingConsumer beta) {
        return new OutboxDispatcher(new JdbcOutboxRepository(db.dataSource()), "dispatch-1",
                new OutboxDispatchSettings(Duration.ofMillis(600), Duration.ofMillis(100), Duration.ofMillis(20), 4),
                new OutboxRetryDelays(java.util.List.of(Duration.ofMillis(150))), java.util.List.of(alpha, beta));
    }

    @Test void partialFailureRedoesOnlyTheFailedConsumerAndThenPublishes() throws Exception {
        RecordingConsumer alpha;
        RecordingConsumer beta;
        try (var db = new MySqlEventTestDatabase();
             var dispatcher = dispatcher(db,
                     alpha = new RecordingConsumer("alpha", db.dataSource(), ids),
                     beta = new RecordingConsumer("beta", db.dataSource(), ids))) {
            String eventId = seed(db, 900_001);
            beta.failFirstExecutions = 1;

            assertEquals(OutboxDispatcher.Outcome.FAILED, dispatcher.dispatchOne());
            assertEquals(1, alpha.executions.get());
            assertEquals(1, beta.executions.get());
            assertEquals(1, factCount(db, "alpha", eventId));
            assertEquals(0, factCount(db, "beta", eventId));
            assertEquals("FAILED", status(db, eventId));

            Thread.sleep(300); // backoff 150ms becomes due

            assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
            // alpha committed in round one: the consume_log claim makes the replay a no-op.
            assertEquals(1, alpha.executions.get());
            assertEquals(2, beta.executions.get());
            assertEquals(1, factCount(db, "alpha", eventId));
            assertEquals(1, factCount(db, "beta", eventId));
            assertEquals("PUBLISHED", status(db, eventId));
            assertNotNull(db.jdbc().queryForObject(
                    "SELECT published_at FROM integration_event_outbox WHERE event_id=?", java.sql.Timestamp.class, eventId));
        }
    }

    @Test void replayedEventIsFullySkippedWhenEveryConsumerAlreadySucceeded() throws Exception {
        RecordingConsumer alpha, beta;
        try (var db = new MySqlEventTestDatabase();
             var dispatcher = dispatcher(db,
                     alpha = new RecordingConsumer("alpha", db.dataSource(), ids),
                     beta = new RecordingConsumer("beta", db.dataSource(), ids))) {
            String eventId = seed(db, 900_002);
            assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
            assertEquals("PUBLISHED", status(db, eventId));

            // Simulate a post-PUBLISHED duplicate delivery (e.g. late takeover): both skip via consume_log.
            var event = new DispatchedEvent(eventId, "OrderPlaced.v1", 1, occurredAt, "ORDER", 900_002, null, "{}");
            alpha.consume(event);
            beta.consume(event);
            assertEquals(1, alpha.executions.get());
            assertEquals(1, beta.executions.get());
            assertEquals(1, factCount(db, "alpha", eventId));
            assertEquals(1, factCount(db, "beta", eventId));
        }
    }

    @Test void consumeGuardClaimsOncePerConsumerAndEventAcrossTransactions() throws Exception {
        try (var db = new MySqlEventTestDatabase()) {
            var guard = new JdbcOutboxConsumeGuard(db.dataSource(), ids::incrementAndGet);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
            var event = new DispatchedEvent("evt-dup-1", "OrderPlaced.v1", 1, occurredAt, "ORDER", 1, null, "{}");
            var claimed = new java.util.concurrent.atomic.AtomicBoolean();
            tx.executeWithoutResult(status -> claimed.set(guard.tryClaim("who", event)));
            assertTrue(claimed.get());
            claimed.set(true);
            tx.executeWithoutResult(status -> claimed.set(guard.tryClaim("who", event)));
            assertFalse(claimed.get(), "duplicate claim is an idempotent skip");
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_consume_log WHERE event_id='evt-dup-1'", Integer.class));

            // A rolled back claim leaves no fact: the next attempt claims again.
            assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                if (guard.tryClaim("rolled", event)) throw new RuntimeException("planned failure after claim");
            }));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='rolled'", Integer.class));
            claimed.set(false);
            tx.executeWithoutResult(status -> claimed.set(guard.tryClaim("rolled", event)));
            assertTrue(claimed.get());
        }
    }

    private static String status(MySqlEventTestDatabase db, String eventId) {
        return db.jdbc().queryForObject(
                "SELECT status FROM integration_event_outbox WHERE event_id=?", String.class, eventId);
    }

    private static int factCount(MySqlEventTestDatabase db, String consumer, String eventId) {
        return db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM outbox_test_fact WHERE consumer=? AND event_id=?",
                Integer.class, consumer, eventId);
    }
}
