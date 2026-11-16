package com.petplatform.schedule.biz.domain.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Exact, non-persisting slot-capacity feasibility (CCR-SCH-STAFF-OFFLINE-001, 2026-11-16):
 * staff scheduling went offline, so feasibility is only the store-wide window-capacity
 * invariant — the concurrent effective-claim count of every original window may never exceed
 * its configured capacity. No staff dimension, no assignment search.
 */
public final class CapacityFeasibilitySolver {
    public record Interval(Instant start, Instant end) {
        public Interval {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            if (!end.isAfter(start)) throw new IllegalArgumentException("empty interval");
        }

        public boolean overlaps(Interval other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    public record Claim(String windowId, Interval interval) {}
    public record Window(String id, int capacity) {}
    public record Reservation(String id, List<Claim> claims) {
        public Reservation { claims = List.copyOf(claims); }
    }
    public enum Outcome { FEASIBLE, INFEASIBLE, BUDGET_EXHAUSTED }
    public record Result(Outcome outcome, int evaluatedReservations) {}

    private final LongSupplier nanoTime;

    public CapacityFeasibilitySolver(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public Result solve(String candidateId, List<Reservation> all, List<Window> windows,
            long budgetMillis) {
        Timer timer = validated(all, windows, budgetMillis);
        if (all.stream().noneMatch(reservation -> candidateId.equals(reservation.id()))) {
            throw new IllegalArgumentException("candidate missing");
        }
        return search(all, windows, timer);
    }

    /**
     * Whole-store variant for protected-reduction proofs (34号 §2 SCHC-1, simplified by
     * CCR-SCH-STAFF-OFFLINE-001): every active reservation of the store must still fit the
     * configured window capacities together after an already-applied capability reduction.
     */
    public Result solveAll(List<Reservation> all, List<Window> windows, long budgetMillis) {
        Timer timer = validated(all, windows, budgetMillis);
        return search(all, windows, timer);
    }

    private Timer validated(List<Reservation> all, List<Window> windows, long budgetMillis) {
        if (budgetMillis <= 0 || budgetMillis > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("invalid calculation budget");
        }
        Timer timer = new Timer(nanoTime, budgetMillis * 1_000_000L);
        Map<String, Integer> windowCapacity = new HashMap<>();
        for (Window window : windows) {
            if (timer.expired()) return timer;
            if (window.id() == null || window.capacity() < 0
                    || windowCapacity.putIfAbsent(window.id(), window.capacity()) != null) {
                throw new IllegalArgumentException("invalid window");
            }
        }
        for (Reservation reservation : all) {
            if (timer.expired()) return timer;
            if (reservation.id() == null || reservation.claims().isEmpty()) {
                throw new IllegalArgumentException("invalid reservation");
            }
            for (Claim claim : reservation.claims()) {
                if (timer.expired()) return timer;
                if (claim == null || claim.interval() == null
                        || !windowCapacity.containsKey(claim.windowId())) {
                    throw new IllegalArgumentException("claim window missing");
                }
            }
        }
        return timer;
    }

    /** Sweep the half-open claim endpoints of every original window: the concurrent effective
     * claim count is a store-wide invariant and may never exceed the configured capacity. */
    private Result search(List<Reservation> all, List<Window> windows, Timer timer) {
        int count = all.size();
        Map<String, TreeMap<Instant, Integer>> occupancy = new HashMap<>();
        for (Reservation reservation : all) {
            for (Claim claim : reservation.claims()) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                TreeMap<Instant, Integer> endpoints = occupancy.computeIfAbsent(
                        claim.windowId(), ignored -> new TreeMap<>());
                endpoints.merge(claim.interval().start(), 1, Integer::sum);
                endpoints.merge(claim.interval().end(), -1, Integer::sum);
            }
        }
        Map<String, Integer> capacity = new HashMap<>();
        for (Window window : windows) capacity.put(window.id(), window.capacity());
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : occupancy.entrySet()) {
            int concurrent = 0;
            for (int delta : entry.getValue().values()) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                concurrent += delta;
                if (concurrent > capacity.get(entry.getKey())) {
                    return new Result(Outcome.INFEASIBLE, count);
                }
            }
        }
        return new Result(Outcome.FEASIBLE, count);
    }

    private static final class Timer {
        private final LongSupplier ticker;
        private final long started;
        private final long budget;

        private Timer(LongSupplier ticker, long budget) {
            this.ticker = ticker;
            this.started = ticker.getAsLong();
            this.budget = budget;
        }

        private boolean expired() {
            return ticker.getAsLong() - started >= budget;
        }
    }
}
