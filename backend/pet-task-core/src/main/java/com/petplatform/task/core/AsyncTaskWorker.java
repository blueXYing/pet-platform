package com.petplatform.task.core;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opt-in component; no Spring bean or production provider is silently registered.
 * A lost lease only fences infrastructure writes. Business side effects must be idempotent.
 */
public final class AsyncTaskWorker implements AutoCloseable {
  /** Composition entry point; the SQL13 persistence implementation remains owned by task-core. */
  public static AsyncTaskWorker create(
      javax.sql.DataSource source,
      com.petplatform.common.SnowflakeIdGenerator ids,
      String owner,
      Clock clock,
      TaskWorkerSettings settings,
      TaskRetryDelays retryDelays,
      Collection<TaskRegistration<?>> handlers) {
    return new AsyncTaskWorker(
        new JdbcAsyncTaskRepository(source, ids), owner, clock, settings, retryDelays, handlers);
  }

    public enum Outcome { EMPTY, COMPLETED, LEASE_LOST, ABANDONED }
    private static final System.Logger LOG = System.getLogger(AsyncTaskWorker.class.getName());
    private final JdbcAsyncTaskRepository repository;
    private final String owner;
    private final Clock clock;
    private final TaskWorkerSettings settings;
    private final TaskRetryDelays retryDelays;
    private final Map<String, TaskRegistration<?>> handlers;
    private final Object lifecycle = new Object();
    private final Set<Execution> active = new HashSet<>();
    private final ScheduledExecutorService heartbeats;
    private final ScheduledExecutorService poller;
    private boolean closed;
    private boolean started;

    public AsyncTaskWorker(JdbcAsyncTaskRepository repository, String owner, Clock clock,
                           TaskWorkerSettings settings, TaskRetryDelays retryDelays,
                           Collection<TaskRegistration<?>> handlers) {
        this.repository = Objects.requireNonNull(repository);
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("Invalid owner");
        this.owner = owner;
        this.clock = Objects.requireNonNull(clock, "An explicit common Clock is required");
        this.settings = Objects.requireNonNull(settings);
        this.retryDelays = Objects.requireNonNull(retryDelays);
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(r -> r.handler().taskType(), r -> r));
        heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("task-heartbeat-" + owner).factory());
        poller = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("task-poll-" + owner).factory());
    }

    public void start() {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Worker is closed");
            if (started) return;
            started = true;
            poller.scheduleWithFixedDelay(() -> {
                try { pollBatch(); }
                catch (RuntimeException error) {
                    // Do not log raw exception messages: they may contain SQL parameters or payloads.
                    LOG.log(System.Logger.Level.WARNING, "Task poll failed: {0}", error.getClass().getSimpleName());
                } catch (Error error) {
                    LOG.log(System.Logger.Level.ERROR, "Task poll error: {0}", error.getClass().getSimpleName());
                    if (error instanceof VirtualMachineError || error instanceof ThreadDeath) {
                        close();
                        throw error;
                    }
                    // A nonfatal Handler Error abandons its lease; it must not silently
                    // cancel all future scheduled polls. The finally block stops its heartbeat.
                }
            }, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public int pollBatch() {
        int claimed = 0;
        for (int i = 0; i < settings.batchSize(); i++) {
            Outcome outcome = runOne();
            if (outcome == Outcome.EMPTY || outcome == Outcome.ABANDONED) break;
            claimed++;
        }
        return claimed;
    }

    public Outcome runOne() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Worker cannot execute inside a caller transaction");
        }
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
            TaskRegistration<?> registration = handlers.get(execution.lease.taskType());
            if (registration == null) {
                // Configuration gaps are not business DEAD results. Recovery remains durable.
                throw new IllegalStateException("No registered task handler");
            }
            // Validate fallback policy before executing any business effect.
            var fallbackDelay = retryDelays.delay(execution.lease.retryPolicy(), execution.lease.retryCount());
            TaskExecutionResult result;
            try {
                var prepared = registration.prepare(execution.lease, clock);
                synchronized (lifecycle) {
                    // Preparation can run user-supplied decoding. Close/lost ownership
                    // before this dispatch permission must prevent first Handler entry.
                    if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
                    if (!execution.valid.get()) return Outcome.LEASE_LOST;
                }
                result = prepared.get();
            } catch (RuntimeException error) {
                result = new TaskExecutionResult.Retry("HANDLER_EXCEPTION", fallbackDelay);
            }
            synchronized (lifecycle) {
                if (closed || Thread.currentThread().isInterrupted()) return Outcome.ABANDONED;
                if (!execution.valid.get()) return Outcome.LEASE_LOST;
                return repository.complete(execution.lease, result) ? Outcome.COMPLETED : Outcome.LEASE_LOST;
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
            // No claim is released early: an executing handler might still have effects.
            // Canceling a Future is not proof that a business command was interrupted.
            heartbeats.shutdown();
            poller.shutdown();
        }
    }

    private static final class Execution {
        final TaskLease lease;
        final AtomicBoolean valid = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat;
        Execution(TaskLease lease) { this.lease = lease; }
    }
}
