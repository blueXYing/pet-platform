package com.petplatform.id.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * One manually owned daemon thread; one admitted operation, no work queue or replacement worker.
 * The immutable CAS cell arbitrates model + outcome together. No caller/controller ever waits
 * for a lock held by the blocking action. close/timeout does NOT interrupt or stop that action.
 */
public final class SingleFlightLane<S> implements AutoCloseable {
    private static final long BUDGET_NANOS = SnowflakeProviderSettings.BUDGET_MILLIS * 1_000_000L;
    private static final long OBSERVE_NANOS = 250_000L;
    private final AtomicReference<Cell<S>> cell;
    private final AtomicReference<Work<S>> dispatch = new AtomicReference<>();
    private final Thread worker;
    private final Thread deadlines;

    public record Proposal<S, T>(S state, T result) {
        public Proposal { Objects.requireNonNull(state); }
    }

    public static final class Context<S> {
        private final SingleFlightLane<S> lane;
        private final Cell<S> admitted;
        private Context(SingleFlightLane<S> lane, Cell<S> admitted) {
            this.lane = lane;
            this.admitted = admitted;
        }
        public S state() { return admitted.model; }
        public void checkActive() {
            if (lane.cell.get() != admitted || expired(admitted.operation.started)) {
                throw new IllegalStateException("Operation is no longer pending within its budget");
            }
        }
    }

    private record Operation(Thread caller, long started) {}
    private record Outcome(Object value, RuntimeException failure) {}
    private record Cell<S>(S model, Operation operation, Outcome outcome, boolean closed, String reason) {}
    private record Work<S>(Cell<S> admitted, Function<Context<S>, Proposal<S, ?>> action) {}

    public SingleFlightLane(S initialState) {
        cell = new AtomicReference<>(new Cell<>(Objects.requireNonNull(initialState), null, null, false, null));
        deadlines = Thread.ofPlatform().daemon().name("snowflake-deadline-controller").unstarted(this::watchDeadline);
        worker = Thread.ofPlatform().daemon().name("snowflake-single-flight").unstarted(this::run);
        worker.start();
        deadlines.start();
    }

    public S state() { return cell.get().model; }
    public boolean isClosed() { return cell.get().closed; }
    public String failureReason() { return cell.get().reason; }
    public long workerThreadId() { return worker.threadId(); }
    public boolean workerAlive() { return worker.isAlive(); }

    public <T> T invoke(Function<Context<S>, Proposal<S, T>> action) {
        Objects.requireNonNull(action);
        if (Thread.currentThread() == worker) throw new IllegalStateException("Recursive lane invocation");
        long started = System.nanoTime();
        Operation operation = new Operation(Thread.currentThread(), started);
        Cell<S> admitted;
        for (;;) {
            Cell<S> current = cell.get();
            if (current.closed) throw new IllegalStateException("Provider closed: " + current.reason);
            if (current.operation != null && current.outcome == null && expired(current.operation.started)) {
                failPending(current.operation, "OPERATION_TIMEOUT", null);
                continue;
            }
            if (expired(started)) throw new IllegalStateException("Execution slot unavailable within budget");
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted before admission");
            if (current.operation == null) {
                admitted = new Cell<>(current.model, operation, null, false, null);
                if (cell.compareAndSet(current, admitted)) break;
            } else {
                LockSupport.parkNanos(OBSERVE_NANOS);
            }
        }
        LockSupport.unpark(deadlines);
        Work<S> work = new Work<>(admitted, context -> action.apply(context));
        if (cell.get() == admitted && !dispatch.compareAndSet(null, work)) {
            failPending(operation, "DISPATCH_INVARIANT", null);
        }
        if (cell.get() != admitted) dispatch.compareAndSet(work, null);
        LockSupport.unpark(worker);
        for (;;) {
            Cell<S> current = cell.get();
            if (current.operation != operation) throw new IllegalStateException("Operation receipt was lost");
            if (current.outcome != null) {
                Outcome result = current.outcome;
                acknowledge(operation);
                if (result.failure != null) throw result.failure;
                @SuppressWarnings("unchecked") T value = (T) result.value;
                return value;
            }
            if (expired(started)) failPending(operation, "OPERATION_TIMEOUT", null);
            else if (Thread.currentThread().isInterrupted()) failPending(operation, "CALLER_INTERRUPTED", null);
            else LockSupport.parkNanos(OBSERVE_NANOS);
        }
    }

    private void run() {
        while (!cell.get().closed) {
            Work<S> work = dispatch.getAndSet(null);
            if (work == null) {
                LockSupport.park();
                continue;
            }
            try {
                Context<S> context = new Context<>(this, work.admitted);
                context.checkActive();
                Proposal<S, ?> proposal = Objects.requireNonNull(work.action.apply(context));
                context.checkActive();
                // Last published ID/grant/model and success receipt have a SINGLE linearization point.
                cell.compareAndSet(work.admitted, new Cell<>(proposal.state, work.admitted.operation,
                        new Outcome(proposal.result, null), false, null));
            } catch (Throwable failure) {
                failPending(work.admitted.operation, "ACTION_FAILED", failure);
                if (failure instanceof VirtualMachineError error) throw error;
                if (failure instanceof ThreadDeath death) throw death;
            } finally {
                LockSupport.unpark(work.admitted.operation.caller);
            }
        }
        dispatch.set(null);
    }

    private void acknowledge(Operation operation) {
        for (;;) {
            Cell<S> current = cell.get();
            if (current.operation != operation) return;
            if (cell.compareAndSet(current, new Cell<>(current.model, null, null, current.closed, current.reason))) return;
        }
    }

    private void failPending(Operation operation, String reason, Throwable cause) {
        for (;;) {
            Cell<S> current = cell.get();
            if (current.operation != operation || current.outcome != null || current.closed) return;
            var failure = new IllegalStateException("Provider unavailable: " + reason, cause);
            if (cell.compareAndSet(current, new Cell<>(current.model, operation,
                    new Outcome(null, failure), true, reason))) {
                LockSupport.unpark(operation.caller);
                LockSupport.unpark(worker);
                LockSupport.unpark(deadlines);
                return;
            }
        }
    }

    public void close(String reason) {
        Objects.requireNonNull(reason);
        for (;;) {
            Cell<S> current = cell.get();
            if (current.closed) return;
            // Preserve an already won success even if close happens before its caller resumes.
            Outcome outcome = current.outcome;
            if (current.operation != null && outcome == null) {
                outcome = new Outcome(null, new IllegalStateException("Provider closed: " + reason));
            }
            if (cell.compareAndSet(current, new Cell<>(current.model, current.operation, outcome, true, reason))) {
                if (current.operation != null) LockSupport.unpark(current.operation.caller);
                LockSupport.unpark(worker);
                LockSupport.unpark(deadlines);
                return;
            }
        }
    }

    @Override public void close() { close("CLOSED"); }

    private void watchDeadline() {
        // One controller, no timer-task queue, cancel lock, shutdown lock, or worker replacement.
        while (!cell.get().closed) {
            Cell<S> current = cell.get();
            if (current.operation != null && current.outcome == null) {
                long elapsed = System.nanoTime() - current.operation.started;
                if (elapsed < 0 || elapsed >= BUDGET_NANOS) {
                    failPending(current.operation, "OPERATION_TIMEOUT", null);
                } else {
                    LockSupport.parkNanos(BUDGET_NANOS - elapsed);
                }
            } else {
                LockSupport.park();
            }
        }
    }

    private static boolean expired(long started) {
        long elapsed = System.nanoTime() - started;
        return elapsed < 0 || elapsed >= BUDGET_NANOS;
    }
}
