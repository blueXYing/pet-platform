package com.petplatform.event.core;

import com.petplatform.event.core.mapper.OutboxMapper;
import com.petplatform.event.core.mapper.OutboxRowEntity;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
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
    private final SqlSessionTemplate template;
    private final TransactionTemplate transaction;

    public JdbcOutboxRepository(DataSource dataSource) {
        this.template = EventMybatis.template(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A claimed row serializes its own state changes; READ_COMMITTED avoids
        // gap-lock deadlocks between concurrent claimers (see JdbcAsyncTaskRepository).
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> {
            outbox().setTimeZoneUtc();
            return action.get();
        });
    }

    private OutboxMapper outbox() {
        return template.getMapper(OutboxMapper.class);
    }

    /** NEW rows, due FAILED retries and expired PUBLISHING leases are all claimable (Scheduler §22). */
    public Optional<OutboxLease> claim(String owner, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("owner must contain 1..128 characters");
        }
        long micros = positiveMillis(leaseDuration) * 1000;
        return inTransaction(() -> {
            OutboxRowEntity candidate = outbox().selectClaimCandidate();
            if (candidate == null) return Optional.empty();
            int changed = outbox().updateClaim(owner, micros, candidate.getId());
            if (changed != 1) throw new IllegalStateException("Outbox claim pairing violated; transaction rolled back");
            return Optional.of(new OutboxLease(candidate.getId(), candidate.getEventId(),
                    candidate.getEventType(), candidate.getEventVersion(), candidate.getOccurredAt(),
                    candidate.getAggregateType(), candidate.getAggregateId(), candidate.getTraceId(),
                    candidate.getPayload(), owner, candidate.getRetryCount()));
        });
    }

    /** False means the lease was lost (expired and taken over, or fenced). */
    public boolean heartbeat(OutboxLease lease, Duration duration) {
        long micros = positiveMillis(duration) * 1000;
        return inTransaction(() -> {
            lockRow(lease.id());
            return outbox().updateHeartbeat(micros, lease.id(), lease.owner()) == 1;
        });
    }

    /** Consumers that already committed a consume_log row for this event (idx_consume_log_event). */
    public Set<String> succeededConsumers(String eventId) {
        return template.getMapper(com.petplatform.event.core.mapper.ConsumeLogMapper.class)
                .selectConsumerNames(eventId).stream().collect(Collectors.toUnmodifiableSet());
    }

    /** Terminal completion: every registered necessary consumer succeeded (Scheduler §21). */
    public boolean markPublished(OutboxLease lease) {
        return inTransaction(() -> {
            lockRow(lease.id());
            return outbox().updateMarkPublished(lease.id(), lease.owner()) == 1;
        });
    }

    /** Consumer failure: back off and stay FAILED; rows are never deleted (Scheduler §22). */
    public boolean markFailed(OutboxLease lease, Duration backoff) {
        long micros = positiveMillis(backoff) * 1000;
        return inTransaction(() -> {
            lockRow(lease.id());
            return outbox().updateMarkFailed(micros, lease.id(), lease.owner()) == 1;
        });
    }

    /** Return a claimed row to NEW: no consumer is registered on this node (configuration gap, not failure). */
    public boolean release(OutboxLease lease) {
        return inTransaction(() -> {
            lockRow(lease.id());
            return outbox().updateRelease(lease.id(), lease.owner()) == 1;
        });
    }

    private void lockRow(long id) {
        // NOW is fixed at statement start in MySQL. Acquire a contended lock first,
        // then evaluate lease validity in a fresh UPDATE after any wait.
        outbox().selectIdForUpdate(id);
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
