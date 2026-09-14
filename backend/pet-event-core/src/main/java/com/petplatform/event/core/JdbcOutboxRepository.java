package com.petplatform.event.core;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL 8 repository over the authoritative integration_event_outbox and
 * integration_event_consume_log tables (Schema 06 §12, CCR-W0-001).
 * It owns short infrastructure transactions only; the publisher participates in
 * the caller's business transaction, consumers own theirs.
 * Fencing is (status, lease_owner, lease_until): the schema has no version
 * column, so a lease that expired and was taken over silently fails old writers.
 */
public final class JdbcOutboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOutboxRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A claimed row serializes its own state changes; READ_COMMITTED avoids
        // gap-lock deadlocks between concurrent claimers (see JdbcAsyncTaskRepository).
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> {
            jdbc.execute("SET SESSION time_zone = '+00:00'");
            return action.get();
        });
    }

    /** NEW rows, due FAILED retries and expired PUBLISHING leases are all claimable (Scheduler §22). */
    public Optional<OutboxLease> claim(String owner, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1..128 characters");
        }
        long micros = positiveMillis(leaseDuration) * 1000;
        return inTransaction(() -> {
            List<OutboxLease> candidates = jdbc.query("""
                    SELECT id,event_id,event_type,event_version,occurred_at,aggregate_type,
                           aggregate_id,trace_id,payload,retry_count
                    FROM integration_event_outbox
                    WHERE status = 'NEW'
                       OR (status = 'FAILED' AND next_retry_at <= NOW(3))
                       OR (status = 'PUBLISHING' AND lease_until < NOW(3))
                    ORDER BY created_at ASC, id ASC
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new OutboxLease(rs.getLong("id"), rs.getString("event_id"),
                    rs.getString("event_type"), rs.getInt("event_version"),
                    rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("aggregate_type"),
                    rs.getLong("aggregate_id"), rs.getString("trace_id"), rs.getString("payload"),
                    owner, rs.getInt("retry_count")));
            if (candidates.isEmpty()) return Optional.empty();
            OutboxLease candidate = candidates.getFirst();
            int changed = jdbc.update("""
                    UPDATE integration_event_outbox SET status='PUBLISHING',lease_owner=?,
                    lease_until=TIMESTAMPADD(MICROSECOND,?,NOW(3))
                    WHERE id=?
                    """, owner, micros, candidate.id());
            if (changed != 1) throw new IllegalStateException("Outbox claim pairing violated; transaction rolled back");
            return Optional.of(candidate);
        });
    }

    /** False means the lease was lost (expired and taken over, or fenced). */
    public boolean heartbeat(OutboxLease lease, Duration duration) {
        long micros = positiveMillis(duration) * 1000;
        return inTransaction(() -> {
            lockRow(lease.id());
            return jdbc.update("""
                    UPDATE integration_event_outbox SET lease_until=TIMESTAMPADD(MICROSECOND,?,NOW(3))
                    WHERE id=? AND status='PUBLISHING' AND lease_owner=? AND lease_until>=NOW(3)
                    """, micros, lease.id(), lease.owner()) == 1;
        });
    }

    /** Consumers that already committed a consume_log row for this event (idx_consume_log_event). */
    public Set<String> succeededConsumers(String eventId) {
        return jdbc.queryForList(
                "SELECT consumer_name FROM integration_event_consume_log WHERE event_id=?",
                String.class, eventId).stream().collect(Collectors.toUnmodifiableSet());
    }

    /** Terminal completion: every registered necessary consumer succeeded (Scheduler §21). */
    public boolean markPublished(OutboxLease lease) {
        return inTransaction(() -> {
            lockRow(lease.id());
            return jdbc.update("""
                    UPDATE integration_event_outbox SET status='PUBLISHED',published_at=NOW(3),
                    lease_owner=NULL,lease_until=NULL
                    WHERE id=? AND status='PUBLISHING' AND lease_owner=? AND lease_until>=NOW(3)
                    """, lease.id(), lease.owner()) == 1;
        });
    }

    /** Consumer failure: back off and stay FAILED; rows are never deleted (Scheduler §22). */
    public boolean markFailed(OutboxLease lease, Duration backoff) {
        long micros = positiveMillis(backoff) * 1000;
        return inTransaction(() -> {
            lockRow(lease.id());
            return jdbc.update("""
                    UPDATE integration_event_outbox SET status='FAILED',
                    retry_count=retry_count+1,next_retry_at=TIMESTAMPADD(MICROSECOND,?,NOW(3)),
                    lease_owner=NULL,lease_until=NULL
                    WHERE id=? AND status='PUBLISHING' AND lease_owner=? AND lease_until>=NOW(3)
                    """, micros, lease.id(), lease.owner()) == 1;
        });
    }

    /** Return a claimed row to NEW: no consumer is registered on this node (configuration gap, not failure). */
    public boolean release(OutboxLease lease) {
        return inTransaction(() -> {
            lockRow(lease.id());
            return jdbc.update("""
                    UPDATE integration_event_outbox SET status='NEW',lease_owner=NULL,lease_until=NULL
                    WHERE id=? AND status='PUBLISHING' AND lease_owner=? AND lease_until>=NOW(3)
                    """, lease.id(), lease.owner()) == 1;
        });
    }

    private void lockRow(long id) {
        // NOW is fixed at statement start in MySQL. Acquire a contended lock first,
        // then evaluate lease validity in a fresh UPDATE after any wait.
        jdbc.query("SELECT id FROM integration_event_outbox WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), id);
    }

    static long positiveMillis(Duration value) {
        Objects.requireNonNull(value);
        long millis = value.toMillis();
        if (millis <= 0 || millis > Duration.ofDays(365).toMillis()
                || !value.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException("Duration must be whole milliseconds in (0,365 days]");
        }
        return millis;
    }
}
