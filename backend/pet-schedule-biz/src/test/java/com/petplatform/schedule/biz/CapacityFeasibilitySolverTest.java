package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Claim;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Interval;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Outcome;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Reservation;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Window;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * CCR-SCH-STAFF-OFFLINE-001 (2026-11-16): feasibility is the store-wide window-capacity
 * invariant only — the concurrent effective-claim count of every original window may never
 * exceed its configured capacity. No staff dimension exists any more.
 */
class CapacityFeasibilitySolverTest {
    private static final CapacityFeasibilitySolver SOLVER =
            new CapacityFeasibilitySolver(() -> 0L);

    @Test
    void concurrentClaimsAboveConfiguredCapacityAreInfeasible() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "wa", 0, 60),
                reservation("existing", "wa", 30, 90));
        assertEquals(Outcome.INFEASIBLE, result(reservations, 1, 1));
        assertEquals(Outcome.FEASIBLE, result(reservations, 2, 2));
    }

    @Test
    void adjacentHalfOpenClaimsReuseTheWholeConfiguredCapacity() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "wa", 0, 60),
                reservation("existing", "wa", 60, 120));
        assertEquals(Outcome.FEASIBLE, result(reservations, 1, 1));
    }

    @Test
    void eachOriginalWindowHasItsOwnAtomicCapacity() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "same", 30, 90),
                reservation("existing", "same", 0, 60));
        assertEquals(Outcome.INFEASIBLE, result(reservations, 1));
        assertEquals(Outcome.FEASIBLE, result(reservations, 2));
    }

    @Test
    void configuredZeroIsKnownExhaustionRatherThanCorruptFacts() {
        assertEquals(Outcome.INFEASIBLE, SOLVER.solve("candidate",
                List.of(reservation("candidate", "wa", 0, 60)),
                List.of(new Window("wa", 0)), 1000).outcome());
    }

    @Test
    void anotherOriginalWindowAlreadyOverCapacityBlocksEvenWithoutTimeOverlap() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "wa", 0, 60),
                reservation("distant-one", "wb", 120, 180),
                reservation("distant-two", "wb", 120, 180));
        var result = SOLVER.solve("candidate", reservations,
                List.of(new Window("wa", 1), new Window("wb", 1)), 1000);
        assertEquals(Outcome.INFEASIBLE, result.outcome());
        assertEquals(3, result.evaluatedReservations(),
                "the capacity sweep evaluates every reservation of the store");
    }

    @Test
    void solveAllProvesTheSameStoreWideInvariantWithoutACandidate() {
        List<Reservation> reservations = List.of(
                reservation("one", "wa", 0, 60),
                reservation("two", "wa", 30, 90));
        assertEquals(Outcome.INFEASIBLE,
                SOLVER.solveAll(reservations, List.of(new Window("wa", 1)), 1000).outcome());
        assertEquals(Outcome.FEASIBLE,
                SOLVER.solveAll(reservations, List.of(new Window("wa", 2)), 1000).outcome());
    }

    @Test
    void unknownCandidateOrMissingClaimWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SOLVER.solve("absent",
                List.of(reservation("candidate", "wa", 0, 60)),
                List.of(new Window("wa", 1)), 1000));
        assertThrows(IllegalArgumentException.class, () -> SOLVER.solve("candidate",
                List.of(reservation("candidate", "missing", 0, 60)),
                List.of(new Window("wa", 1)), 1000));
    }

    @Test
    void calculationBudgetIsDeterministicAndNeverMeansCapacityConflict() {
        AtomicLong ticker = new AtomicLong();
        CapacityFeasibilitySolver budgeted =
                new CapacityFeasibilitySolver(() -> ticker.getAndAdd(1_000_000L));
        var result = budgeted.solve("candidate",
                List.of(reservation("candidate", "wa", 0, 60)),
                List.of(new Window("wa", 1)), 1);
        assertEquals(Outcome.BUDGET_EXHAUSTED, result.outcome());
    }

    @Test
    void largeClaimSweepStopsOnBudgetWithoutABusinessSizeLimit() {
        AtomicLong calls = new AtomicLong();
        CapacityFeasibilitySolver budgeted = new CapacityFeasibilitySolver(
                () -> calls.incrementAndGet() < 50_000 ? 0L : 2_000_000L);
        List<Reservation> reservations = new ArrayList<>();
        reservations.add(reservation("candidate", "wa", 0, 60));
        for (int index = 0; index < 199; index++) {
            reservations.add(reservation("r" + index, "wa", 0, 60));
        }
        assertEquals(Outcome.BUDGET_EXHAUSTED, budgeted.solve("candidate", reservations,
                List.of(new Window("wa", 200)), 1).outcome());
    }

    private static Outcome result(List<Reservation> reservations, int... capacities) {
        List<Window> windows = capacities.length == 1
                ? List.of(new Window("same", capacities[0]))
                : List.of(new Window("wa", capacities[0]), new Window("wb", capacities[1]));
        return SOLVER.solve("candidate", reservations, windows, 1000).outcome();
    }

    private static Reservation reservation(String id, String window, int start, int end) {
        return new Reservation(id, List.of(new Claim(window, interval(start, end))));
    }

    private static Interval interval(int startMinutes, int endMinutes) {
        Instant base = Instant.parse("2030-01-01T00:00:00Z");
        return new Interval(base.plusSeconds(startMinutes * 60L),
                base.plusSeconds(endMinutes * 60L));
    }
}
