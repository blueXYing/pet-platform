package com.petplatform.schedule.biz.domain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Exact, non-persisting staff matching for the candidate's full time-overlap closure. */
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
    public record Reservation(String id, String serviceId, List<Claim> claims, String fixedStaffId) {
        public Reservation { claims = List.copyOf(claims); }
    }
    public record Staff(String id, Set<String> serviceIds, List<Interval> availability) {
        public Staff {
            serviceIds = Set.copyOf(serviceIds);
            availability = List.copyOf(availability);
        }
    }
    public enum Outcome { FEASIBLE, INFEASIBLE, BUDGET_EXHAUSTED }
    public record Result(Outcome outcome, int evaluatedReservations) {}

    private final LongSupplier nanoTime;

    public CapacityFeasibilitySolver(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public Result solve(String candidateId, List<Reservation> all, List<Window> windows,
            List<Staff> staff, long budgetMillis) {
        if (budgetMillis <= 0 || budgetMillis > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("invalid calculation budget");
        }
        Timer timer = new Timer(nanoTime, budgetMillis * 1_000_000L);
        Map<String, Integer> windowCapacity = new HashMap<>();
        for (Window window : windows) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, 0);
            if (window.id() == null || window.capacity() < 0
                    || windowCapacity.putIfAbsent(window.id(), window.capacity()) != null) {
                throw new IllegalArgumentException("invalid window");
            }
        }
        Map<String, Reservation> byId = new HashMap<>();
        for (Reservation reservation : all) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, 0);
            if (reservation.id() == null || reservation.serviceId() == null
                    || reservation.claims().isEmpty()
                    || byId.putIfAbsent(reservation.id(), reservation) != null) {
                throw new IllegalArgumentException("invalid reservation");
            }
            for (Claim claim : reservation.claims()) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, 0);
                if (claim == null || claim.interval() == null
                        || !windowCapacity.containsKey(claim.windowId())) {
                    throw new IllegalArgumentException("claim window missing");
                }
            }
        }
        Reservation candidate = byId.get(candidateId);
        if (candidate == null) throw new IllegalArgumentException("candidate missing");
        // Including all segments whenever a reservation is reached makes pickup and return a bridge.
        Set<String> included = new HashSet<>();
        included.add(candidateId);
        List<Reservation> frontier = new ArrayList<>();
        frontier.add(candidate);
        for (int position = 0; position < frontier.size(); position++) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, frontier.size());
            Reservation source = frontier.get(position);
            for (Reservation other : all) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, frontier.size());
                if (included.contains(other.id())) continue;
                if (overlaps(source, other)) {
                    included.add(other.id());
                    frontier.add(other);
                }
            }
        }
        List<Reservation> relevant = frontier.stream()
                .sorted(Comparator.comparing(Reservation::id)).toList();
        int count = relevant.size();
        Map<String, TreeMap<Instant, Integer>> occupancy = new HashMap<>();
        // Original-window capacity is a store-wide invariant, including active claims outside
        // the candidate's personnel-overlap closure. Historical staff qualification is not.
        for (Reservation reservation : all) {
            for (Claim claim : reservation.claims()) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                TreeMap<Instant, Integer> endpoints = occupancy.computeIfAbsent(
                        claim.windowId(), ignored -> new TreeMap<>());
                endpoints.merge(claim.interval().start(), 1, Integer::sum);
                endpoints.merge(claim.interval().end(), -1, Integer::sum);
            }
        }
        for (Map.Entry<String, TreeMap<Instant, Integer>> entry : occupancy.entrySet()) {
            int concurrent = 0;
            for (int delta : entry.getValue().values()) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                concurrent += delta;
                if (concurrent > windowCapacity.get(entry.getKey())) {
                    return new Result(Outcome.INFEASIBLE, count);
                }
            }
        }
        List<Staff> sortedStaff = staff.stream().sorted(Comparator.comparing(Staff::id)).toList();
        Set<String> staffIds = new HashSet<>();
        for (Staff person : sortedStaff) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
            if (person.id() == null || !staffIds.add(person.id())) {
                throw new IllegalArgumentException("invalid staff facts");
            }
        }
        List<List<Integer>> domains = new ArrayList<>(count);
        for (Reservation reservation : relevant) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
            List<Integer> options = new ArrayList<>();
            for (int index = 0; index < sortedStaff.size(); index++) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                Staff person = sortedStaff.get(index);
                if (reservation.fixedStaffId() != null
                        && !reservation.fixedStaffId().equals(person.id())) continue;
                if (!person.serviceIds().contains(reservation.serviceId())) continue;
                boolean covers = true;
                for (Claim claim : reservation.claims()) {
                    if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                    boolean segmentCovered = covers(person.availability(), claim.interval(), timer);
                    if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                    if (!segmentCovered) {
                        covers = false;
                        break;
                    }
                }
                if (covers) options.add(index);
            }
            if (options.isEmpty()) return new Result(Outcome.INFEASIBLE, count);
            domains.add(options);
        }
        BitSet[] conflicts = new BitSet[count];
        int[] degree = new int[count];
        for (int left = 0; left < count; left++) {
            for (int right = left + 1; right < count; right++) {
                if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
                if (overlaps(relevant.get(left), relevant.get(right))) {
                    if (conflicts[left] == null) conflicts[left] = new BitSet();
                    if (conflicts[right] == null) conflicts[right] = new BitSet();
                    conflicts[left].set(right);
                    conflicts[right].set(left);
                    degree[left]++;
                    degree[right]++;
                }
            }
        }
        int[] assigned = new int[count];
        Arrays.fill(assigned, -1);
        List<Integer> variable = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (relevant.get(index).fixedStaffId() != null) assigned[index] = domains.get(index).getFirst();
            else variable.add(index);
        }
        for (int left = 0; left < count; left++) {
            if (assigned[left] < 0) continue;
            for (int right = left + 1; right < count; right++) {
                if (conflicts[left] != null && conflicts[left].get(right)
                        && assigned[left] == assigned[right]) {
                    return new Result(Outcome.INFEASIBLE, count);
                }
            }
        }
        variable.sort(Comparator.<Integer>comparingInt(index -> domains.get(index).size())
                .thenComparing((left, right) -> Integer.compare(degree[right], degree[left]))
                .thenComparing(index -> relevant.get(index).id()));
        // Explicit DFS stack avoids turning a large, valid store into a Java stack overflow.
        int[] optionPosition = new int[variable.size()];
        int depth = 0;
        while (depth >= 0) {
            if (timer.expired()) return new Result(Outcome.BUDGET_EXHAUSTED, count);
            if (depth == variable.size()) return new Result(Outcome.FEASIBLE, count);
            int reservationIndex = variable.get(depth);
            List<Integer> options = domains.get(reservationIndex);
            if (optionPosition[depth] >= options.size()) {
                optionPosition[depth] = 0;
                assigned[reservationIndex] = -1;
                depth--;
                if (depth >= 0) {
                    assigned[variable.get(depth)] = -1;
                    optionPosition[depth]++;
                }
                continue;
            }
            int person = options.get(optionPosition[depth]);
            boolean allowed = true;
            for (int other = 0; other < count; other++) {
                if (conflicts[reservationIndex] != null
                        && conflicts[reservationIndex].get(other) && assigned[other] == person) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) {
                assigned[reservationIndex] = person;
                depth++;
            } else {
                optionPosition[depth]++;
            }
        }
        return new Result(Outcome.INFEASIBLE, count);
    }

    private static boolean overlaps(Reservation left, Reservation right) {
        for (Claim one : left.claims()) {
            for (Claim two : right.claims()) {
                if (one.interval().overlaps(two.interval())) return true;
            }
        }
        return false;
    }

    private static boolean covers(List<Interval> availability, Interval target, Timer timer) {
        List<Interval> ordered = availability.stream()
                .sorted(Comparator.comparing(Interval::start).thenComparing(Interval::end)).toList();
        Instant cursor = target.start();
        for (Interval interval : ordered) {
            if (timer.expired()) return false;
            if (interval.start().isAfter(cursor)) return false;
            if (interval.end().isAfter(cursor)) cursor = interval.end();
            if (!cursor.isBefore(target.end())) return true;
        }
        return false;
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
