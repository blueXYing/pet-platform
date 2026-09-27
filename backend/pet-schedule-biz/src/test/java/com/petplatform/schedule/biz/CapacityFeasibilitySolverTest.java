package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Claim;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Interval;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Outcome;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Reservation;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Staff;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Window;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CapacityFeasibilitySolverTest {
    private static final CapacityFeasibilitySolver SOLVER =
            new CapacityFeasibilitySolver(() -> 0L);

    @Test
    void backtracksAcrossServicesInsteadOfGreedilyUsingTheOnlyQualifiedPerson() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "A", "wa", 0, 60, null),
                reservation("existing", "B", "wb", 0, 60, null));
        List<Staff> staff = List.of(
                staff("one", Set.of("A", "B"), 0, 60),
                staff("two", Set.of("A"), 0, 60));
        assertEquals(Outcome.FEASIBLE, result(reservations, staff, 1, 1));
        assertEquals(Outcome.INFEASIBLE, result(reservations, staff.subList(0, 1), 1, 1));
    }

    @Test
    void pickupAndReturnRequireOnePersonButDoNotRequireCoverageOfTheGap() {
        Reservation candidate = new Reservation("candidate", "A", List.of(
                new Claim("pickup", interval(0, 60)),
                new Claim("return", interval(180, 240))), null);
        List<Window> windows = List.of(new Window("pickup", 1), new Window("return", 1));
        Staff firstLeg = new Staff("first", Set.of("A"), List.of(interval(0, 60)));
        Staff secondLeg = new Staff("second", Set.of("A"), List.of(interval(180, 240)));
        Staff both = new Staff("both", Set.of("A"), List.of(interval(0, 60), interval(180, 240)));
        assertEquals(Outcome.INFEASIBLE,
                SOLVER.solve("candidate", List.of(candidate), windows,
                        List.of(firstLeg, secondLeg), 1000).outcome());
        assertEquals(Outcome.FEASIBLE,
                SOLVER.solve("candidate", List.of(candidate), windows,
                        List.of(firstLeg, secondLeg, both), 1000).outcome());
    }

    @Test
    void adjacentHalfOpenReservationsCanReuseStaffAndAdjacentShiftsCoverAClaim() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "A", "wa", 60, 120, null),
                reservation("existing", "A", "wb", 0, 60, "one"));
        Staff person = new Staff("one", Set.of("A"),
                List.of(interval(0, 60), interval(60, 120)));
        assertEquals(Outcome.FEASIBLE, result(reservations, List.of(person), 1, 1));
        Staff gap = new Staff("one", Set.of("A"),
                List.of(interval(0, 60), interval(60, 89), interval(90, 120)));
        assertEquals(Outcome.INFEASIBLE, result(reservations, List.of(gap), 1, 1));
    }

    @Test
    void eachOriginalWindowHasItsOwnAtomicCapacity() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "A", "same", 30, 90, null),
                reservation("existing", "A", "same", 0, 60, null));
        List<Staff> staff = List.of(staff("one", Set.of("A"), 0, 90),
                staff("two", Set.of("A"), 0, 90));
        assertEquals(Outcome.INFEASIBLE, result(reservations, staff, 1));
        assertEquals(Outcome.FEASIBLE, result(reservations, staff, 2));
    }

    @Test
    void conflictingFixedAssignmentsCannotBeRematched() {
        List<Reservation> reservations = List.of(
                reservation("candidate", "A", "wa", 0, 60, null),
                reservation("existing", "A", "wb", 0, 60, "one"));
        assertEquals(Outcome.INFEASIBLE,
                result(reservations, List.of(staff("one", Set.of("A"), 0, 60)), 1, 1));
    }

    @Test
    void calculationBudgetIsDeterministicAndNeverMeansCapacityConflict() {
        AtomicLong ticker = new AtomicLong();
        CapacityFeasibilitySolver budgeted =
                new CapacityFeasibilitySolver(() -> ticker.getAndAdd(1_000_000L));
        var result = budgeted.solve("candidate",
                List.of(reservation("candidate", "A", "wa", 0, 60, null)),
                List.of(new Window("wa", 1)), List.of(staff("one", Set.of("A"), 0, 60)), 1);
        assertEquals(Outcome.BUDGET_EXHAUSTED, result.outcome());
    }

    private static Outcome result(List<Reservation> reservations, List<Staff> staff,
            int... capacities) {
        List<Window> windows = capacities.length == 1
                ? List.of(new Window("same", capacities[0]))
                : List.of(new Window("wa", capacities[0]), new Window("wb", capacities[1]));
        return SOLVER.solve("candidate", reservations, windows, staff, 1000).outcome();
    }

    private static Reservation reservation(String id, String service, String window,
            int start, int end, String fixed) {
        return new Reservation(id, service,
                List.of(new Claim(window, interval(start, end))), fixed);
    }

    private static Staff staff(String id, Set<String> services, int start, int end) {
        return new Staff(id, services, List.of(interval(start, end)));
    }

    private static Interval interval(int startMinutes, int endMinutes) {
        Instant base = Instant.parse("2030-01-01T00:00:00Z");
        return new Interval(base.plusSeconds(startMinutes * 60L),
                base.plusSeconds(endMinutes * 60L));
    }
}
