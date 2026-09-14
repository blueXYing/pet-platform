package com.petplatform.event.core;

import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local-monolith outbox dispatcher (Scheduler §21/§22): claims events, executes
 * registered necessary consumers that have not succeeded yet, and marks an event
 * PUBLISHED only when every registered necessary consumer committed its
 * consume_log row. Duplicate delivery after a crash or takeover is normal;
 * correctness comes from consumer idempotency, not from exactly-once dispatch.
 * Opt-in component: no Spring bean or scheduler is silently registered.
 */
public final class OutboxDispatcher implements AutoCloseable {
    public enum Outcome { EMPTY, COMPLETED, FAILED, LEASE_LOST, NO_CONSUMERS, ABANDONED }
    private static final System.Logger LOG = System.getLogger(OutboxDispatcher.class.getName());
    private final JdbcOutboxRepository repository;
    private final String owner;
    private final OutboxDispatchSettings settings;
    private final OutboxRetryDelays retryDelays;
    private final Map<String, List<IntegrationEventConsumer>> registry;
    private final Object lifecycle = new Object();
    private final List<Execution> active = new ArrayList<>();
    private final ScheduledExecutorService heartbeats;
    private final ScheduledExecutorService poller;
    private boolean closed;
    private boolean started;

    /** Convenience wiring for hosts that only need the dispatcher; keeps the repository an event-core internal. */
    public OutboxDispatcher(javax.sql.DataSource dataSource, String owner,
                            OutboxDispatchSettings settings, OutboxRetryDelays retryDelays,
                            Collection<IntegrationEventConsumer> consumers) {
        this(new JdbcOutboxRepository(dataSource), owner, settings, retryDelays, consumers);
    }

    public OutboxDispatcher(JdbcOutboxRepository repository, String owner,
                            OutboxDispatchSettings settings, OutboxRetryDelays retryDelays,
                            Collection<IntegrationEventConsumer> consumers) {
        this.repository = Objects.requireNonNull(repository);
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("Invalid owner");
        this.owner = owner;
        this.settings = Objects.requireNonNull(settings);
        this.retryDelays = Objects.requireNonNull(retryDelays);
        Map<String, List<IntegrationEventConsumer>> indexed = new LinkedHashMap<>();
        for (IntegrationEventConsumer consumer : Objects.requireNonNull(consumers)) {
            Objects.requireNonNull(consumer, "Consumer is required");
            String name = consumer.consumerName() == null ? null : consumer.consumerName().strip();
            if (name == null || name.isEmpty() || name.length() > 128 || consumer.eventTypes().isEmpty()) {
                throw new IllegalArgumentException("Consumer needs a consumerName and at least one eventType");
            }
            for (String eventType : consumer.eventTypes()) {
                if (eventType == null || eventType.isBlank() || eventType.length() > 128) {
                    throw new IllegalArgumentException("Invalid registered eventType");
                }
                List<IntegrationEventConsumer> list = indexed.computeIfAbsent(eventType, key -> new ArrayList<>());
                if (list.stream().noneMatch(existing -> existing.consumerName().equals(name))) {
                    list.add(consumer);
                } else {
                    throw new IllegalArgumentException("Duplicate consumerName registration for " + eventType);
                }
            }
        }
        this.registry = indexed;
        heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("outbox-heartbeat-" + owner).factory());
        poller = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("outbox-poll-" + owner).factory());
    }

    public void start() {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Dispatcher is closed");
            if (started) return;
            started = true;
            if (registry.isEmpty()) {
                // No consumer registration anywhere: claiming would only loop rows uselessly.
                return;
            }
            poller.scheduleWithFixedDelay(() -> {
                try {
                    pollBatch();
                } catch (RuntimeException error) {
                    // Do not log raw exception messages: they may contain SQL parameters or payloads.
                    LOG.log(System.Logger.Level.WARNING, "Outbox poll failed: {0}", error.getClass().getSimpleName());
                }
            }, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public int pollBatch() {
        int claimed = 0;
        for (int i = 0; i < settings.batchSize(); i++) {
            Outcome outcome = dispatchOne();
            if (outcome == Outcome.EMPTY || outcome == Outcome.ABANDONED) break;
            claimed++;
        }
        return claimed;
    }

    public Outcome dispatchOne() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dispatcher cannot execute inside a caller transaction");
        }
        if (registry.isEmpty()) return Outcome.EMPTY;
        Execution execution;
        synchronized (lifecycle) {
            if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
            var claim = repository.claim(owner, settings.lease());
            if (claim.isEmpty()) return Outcome.EMPTY;
            execution = new Execution(claim.orElseThrow());
            active.add(execution);
            execution.heartbeat = heartbeats.scheduleWithFixedDelay(() -> renew(execution),
                    settings.heartbeat().toMillis(), settings.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            List<IntegrationEventConsumer> necessary = registry.get(execution.lease.eventType());
            if (necessary == null) {
                // Deployment gap on this node, not a business failure: release durably.
                synchronized (lifecycle) {
                    if (!execution.valid.get()) return Outcome.LEASE_LOST;
                    return repository.release(execution.lease) ? Outcome.NO_CONSUMERS : Outcome.LEASE_LOST;
                }
            }
            var succeeded = repository.succeededConsumers(execution.lease.eventId());
            List<IntegrationEventConsumer> pending = necessary.stream()
                    .filter(consumer -> !succeeded.contains(consumer.consumerName())).toList();
            if (pending.isEmpty()) {
                synchronized (lifecycle) {
                    if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
                    if (!execution.valid.get()) return Outcome.LEASE_LOST;
                    return repository.markPublished(execution.lease) ? Outcome.COMPLETED : Outcome.LEASE_LOST;
                }
            }
            var dispatched = new DispatchedEvent(execution.lease.eventId(), execution.lease.eventType(),
                    execution.lease.eventVersion(), execution.lease.occurredAt(), execution.lease.aggregateType(),
                    execution.lease.aggregateId(), execution.lease.traceId(), execution.lease.payloadJson());
            for (IntegrationEventConsumer consumer : pending) {
                synchronized (lifecycle) {
                    if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
                    if (!execution.valid.get()) return Outcome.LEASE_LOST;
                }
                try {
                    consumer.consume(dispatched);
                } catch (RuntimeException consumerFailure) {
                    synchronized (lifecycle) {
                        if (!execution.valid.get()) return Outcome.LEASE_LOST;
                        return repository.markFailed(execution.lease,
                                retryDelays.delay(execution.lease.retryCount())) ? Outcome.FAILED : Outcome.LEASE_LOST;
                    }
                }
            }
            synchronized (lifecycle) {
                if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
                if (!execution.valid.get()) return Outcome.LEASE_LOST;
                return repository.markPublished(execution.lease) ? Outcome.COMPLETED : Outcome.LEASE_LOST;
            }
        } finally {
            execution.valid.set(false);
            execution.heartbeat.cancel(false);
            synchronized (lifecycle) { active.remove(execution); }
        }
    }

    private void renew(Execution execution) {
        if (!execution.valid.get()) return;
        try {
            if (!repository.heartbeat(execution.lease, settings.lease())) execution.valid.set(false);
        } catch (RuntimeException error) {
            // Ownership is uncertain after a DB failure. Never permit this invocation to finish.
            execution.valid.set(false);
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            for (Execution execution : active) {
                execution.valid.set(false);
                execution.heartbeat.cancel(false);
            }
            // No claim is released early: an executing consumer may still commit effects.
            heartbeats.shutdown();
            poller.shutdown();
        }
    }

    private static final class Execution {
        final OutboxLease lease;
        final AtomicBoolean valid = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat;
        Execution(OutboxLease lease) { this.lease = lease; }
    }
}
