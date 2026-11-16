package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofQuery;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.CapacityProofResult;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.WindowFact;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityProofApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Claim;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Interval;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Outcome;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Reservation;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Window;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Resource-only proof. A future write command must repeat it under its own guard before commit.
 * CCR-SCH-STAFF-OFFLINE-001 (2026-11-16): staff scheduling is offline-managed, so feasibility is
 * the store-wide window-capacity invariant only — no staff availability/capability dimension.
 * The MER current-staff facts read stays as the merchant-consistency anchor of the windows.
 */
public final class ScheduleCapacityProofApiImpl implements ScheduleCapacityProofApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final String CANDIDATE_ID = "_candidate_";
    private static final String CAPACITY_EXCEEDED = "SCHEDULE_CAPACITY_EXCEEDED";
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final MerchantCurrentStaffFactsApi merchant;
    private final OrderProtectionFactsApi order;
    private final long budgetMillis;
    private final CapacityFeasibilitySolver solver;

    public ScheduleCapacityProofApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi facts, MerchantCurrentStaffFactsApi merchant,
            OrderProtectionFactsApi order, Clock clock, long budgetMillis) {
        this(source, guard, facts, merchant, order, clock, budgetMillis, System::nanoTime);
    }

    /** Injected monotonic ticker makes budget exhaustion deterministic in tests. */
    public ScheduleCapacityProofApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi facts, MerchantCurrentStaffFactsApi merchant,
            OrderProtectionFactsApi order, Clock clock, long budgetMillis, LongSupplier ticker) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.facts = Objects.requireNonNull(facts, "facts is required");
        this.merchant = Objects.requireNonNull(merchant, "merchant is required");
        this.order = Objects.requireNonNull(order, "order is required");
        Objects.requireNonNull(clock, "clock is required");
        if (budgetMillis <= 0) throw new IllegalArgumentException("positive budget is required");
        this.budgetMillis = budgetMillis;
        this.solver = new CapacityFeasibilitySolver(ticker);
    }

    @Override
    public CapacityProofResult checkNewReservation(CapacityProofQuery query) {
        HoldProofPlan plan = prepareForHold(query);
        return new CapacityProofResult(query.storeId(), true, plan.evaluatedReservations());
    }

    /** The candidate and its diagnostic counts are derived from the same locked facts as the proof. */
    HoldProofPlan prepareForHold(CapacityProofQuery query) {
        return prepare(query, null);
    }

    HoldProofPlan prepareForSwap(CapacityProofQuery query, String reservationId) {
        return prepare(query, reservationId);
    }

    /**
     * Protected-reduction proof (34号 §2 SCHC-1, simplified by CCR-SCH-STAFF-OFFLINE-001):
     * after a capability reduction has been applied inside the caller's guarded transaction,
     * every active reservation of the store must still fit the configured window capacities.
     * Known infeasibility is a 409 business conflict; budget exhaustion or unreadable facts
     * fail closed with 503 and the whole transaction rolls back.
     */
    void proveReductionSafe(String storeId, QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        guard.requireHeld(storeId, source);
        try {
            StoreScheduleFacts schedule = facts.readStore(storeId, context);
            if (schedule == null || !schedule.complete() || !storeId.equals(schedule.storeId())) {
                bad("incomplete schedule facts");
            }
            CurrentStoreStaffFacts employees = merchant.readStore(storeId, context);
            if (employees == null || !employees.complete()
                    || !storeId.equals(employees.storeId())) bad("incomplete employee facts");
            for (WindowFact window : schedule.windows()) {
                if (!employees.merchantId().equals(window.merchantId())) {
                    bad("schedule window merchant differs from current store merchant");
                }
            }
            OrderProtectionSnapshot orders = order.readStore(storeId, context);
            if (orders == null || !orders.complete() || !storeId.equals(orders.storeId())
                    || orders.items().size() != orders.totalOrders()) bad("incomplete order facts");
            Map<String, OrderProtectionFact> ordersByReservation = orderFacts(orders);
            List<Reservation> reservations = reservations(schedule, windows(schedule),
                    ordersByReservation);
            List<Window> capacity = schedule.windows().stream()
                    .map(window -> new Window(window.windowId(), window.configuredCapacity()))
                    .toList();
            CapacityFeasibilitySolver.Result result =
                    solver.solveAll(reservations, capacity, budgetMillis);
            if (result.outcome() == Outcome.BUDGET_EXHAUSTED) {
                bad("schedule reduction proof calculation budget exhausted");
            }
            if (result.outcome() == Outcome.INFEASIBLE) {
                throw new ApiException(CommonApiCodes.CONFLICT,
                        "the reduction breaks existing protected reservations");
            }
        } catch (ApiException known) {
            rollbackOnly();
            throw known;
        } catch (RuntimeException failed) {
            rollbackOnly();
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule reduction proof dependency unavailable");
        }
    }

    private HoldProofPlan prepare(CapacityProofQuery query, String replacedId) {
        validateQuery(query);
        guard.requireHeld(query.storeId(), source);
        try {
            StoreScheduleFacts schedule = facts.readStore(query.storeId(), query.context());
            if (schedule == null || !schedule.complete()
                    || !query.storeId().equals(schedule.storeId())) bad("incomplete schedule facts");
            CurrentStoreStaffFacts employees = merchant.readStore(query.storeId(), query.context());
            if (employees == null || !employees.complete()
                    || !query.storeId().equals(employees.storeId())) bad("incomplete employee facts");
            for (WindowFact window : schedule.windows()) {
                if (!employees.merchantId().equals(window.merchantId())) {
                    bad("schedule window merchant differs from current store merchant");
                }
            }
            OrderProtectionSnapshot orders = order.readStore(query.storeId(), query.context());
            if (orders == null || !orders.complete() || !query.storeId().equals(orders.storeId())
                    || orders.items().size() != orders.totalOrders()) bad("incomplete order facts");
            Map<String, WindowFact> windows = windows(schedule);
            List<Claim> candidateClaims = candidate(query, windows);
            Map<String, OrderProtectionFact> ordersByReservation = orderFacts(orders);
            List<Reservation> reservations = reservations(schedule, windows, ordersByReservation);
            if (replacedId != null) {
                var old = reservations.stream().filter(r -> replacedId.equals(r.id())).findFirst()
                    .orElseThrow(() -> new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "Swap source absent"));
                // CCR-SCH-STAFF-OFFLINE-001: Reservation no longer carries serviceId (the
                // capacity solver is service-agnostic); the swap's service match is proven
                // by the ORDER domain elsewhere.
                reservations.remove(old);
            }
            reservations.add(new Reservation(CANDIDATE_ID, candidateClaims));
            List<Window> capacity = schedule.windows().stream()
                    .map(window -> new Window(window.windowId(), window.configuredCapacity())).toList();
            CapacityFeasibilitySolver.Result result =
                    solver.solve(CANDIDATE_ID, reservations, capacity, budgetMillis);
            if (result.outcome() == Outcome.BUDGET_EXHAUSTED) {
                bad("schedule capacity proof calculation budget exhausted");
            }
            if (result.outcome() == Outcome.INFEASIBLE) {
                throw new ApiException(CAPACITY_EXCEEDED, "schedule capacity is unavailable");
            }
            List<ProvenClaim> proven = candidateClaims.stream().map(claim -> {
                WindowFact window = windows.get(claim.windowId());
                // CCR-SCH-SLOT-001: slots carry no kind; the claim kind is the selection role
                // (GENERAL appointment, or PICKUP/RETURN by the chosen direction).
                String kind = "IN_STORE".equals(query.fulfillmentType()) ? "GENERAL"
                        : claim.windowId().equals(query.selectedPickupWindowId())
                                ? "PICKUP" : "RETURN";
                return new ProvenClaim(window.windowId(), window.merchantId(), kind,
                        java.time.OffsetDateTime.ofInstant(claim.interval().start(), java.time.ZoneOffset.UTC),
                        java.time.OffsetDateTime.ofInstant(claim.interval().end(), java.time.ZoneOffset.UTC));
            }).toList();
            // CCR-SCH-STAFF-OFFLINE-001: the effective capacity is the configured slot capacity
            // (no qualified-staff minimum); both reservation snapshots record that number.
            int configured = candidateClaims.stream().mapToInt(claim ->
                    windows.get(claim.windowId()).configuredCapacity()).min().orElseThrow();
            return new HoldProofPlan(proven, configured, result.evaluatedReservations());
        } catch (ApiException known) {
            rollbackOnly();
            throw known;
        } catch (RuntimeException failed) {
            rollbackOnly();
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule capacity proof dependency unavailable");
        }
    }

    record ProvenClaim(String windowId, String merchantId, String kind,
            OffsetDateTime startAt, OffsetDateTime endAt) {}
    record HoldProofPlan(List<ProvenClaim> claims, int configuredCapacity,
            int evaluatedReservations) {
        HoldProofPlan { claims = List.copyOf(claims); }
    }

    private static void validateQuery(CapacityProofQuery query) {
        if (query == null || query.context() == null) invalid("capacity query is required");
        apiId(query.storeId());
        apiId(query.serviceId());
        if ("IN_STORE".equals(query.fulfillmentType())) {
            if (!validInterval(query.appointmentStart(), query.appointmentEnd())
                    || query.selectedPickupWindowId() != null || query.selectedReturnWindowId() != null) {
                invalid("invalid GENERAL appointment interval or selected windows");
            }
            if (query.selectedGeneralWindowId() != null) apiId(query.selectedGeneralWindowId());
        } else if ("PICKUP_DELIVERY".equals(query.fulfillmentType())) {
            if (query.appointmentStart() != null || query.appointmentEnd() != null
                    || query.selectedGeneralWindowId() != null
                    || query.selectedPickupWindowId() == null
                    || query.selectedReturnWindowId() == null) {
                invalid("pickup and return selected windows are required");
            }
            apiId(query.selectedPickupWindowId());
            apiId(query.selectedReturnWindowId());
            if (query.selectedPickupWindowId().equals(query.selectedReturnWindowId())) {
                invalid("pickup and return windows must differ");
            }
        } else invalid("fulfillment type is invalid");
    }

    private static Map<String, WindowFact> windows(StoreScheduleFacts schedule) {
        Map<String, WindowFact> result = new HashMap<>();
        for (WindowFact window : schedule.windows()) {
            if (window == null || !schedule.storeId().equals(window.storeId())
                    || result.putIfAbsent(window.windowId(), window) != null) bad("invalid window facts");
        }
        return result;
    }

    private List<Claim> candidate(CapacityProofQuery query, Map<String, WindowFact> windows) {
        if ("IN_STORE".equals(query.fulfillmentType())) {
            // CCR-SCH-SLOT-001: candidate slots are the store's open slots covering the whole
            // appointment; store-wide slots cannot overlap, so at most one qualifies.
            List<WindowFact> eligible = windows.values().stream()
                    .filter(window -> openDerived(window.status())
                            && query.storeId().equals(window.storeId())
                            && !window.startAt().isAfter(query.appointmentStart())
                            && !window.endAt().isBefore(query.appointmentEnd()))
                    .toList();
            if (eligible.size() != 1) conflict("GENERAL appointment needs one open store slot");
            WindowFact window = eligible.getFirst();
            if (query.selectedGeneralWindowId() != null
                    && !query.selectedGeneralWindowId().equals(window.windowId())) {
                conflict("selected GENERAL window is not available");
            }
            return List.of(new Claim(window.windowId(), interval(
                    query.appointmentStart(), query.appointmentEnd())));
        }
        WindowFact pickup = windows.get(query.selectedPickupWindowId());
        WindowFact returning = windows.get(query.selectedReturnWindowId());
        if (!selected(query, pickup) || !selected(query, returning)) {
            conflict("selected pickup or return window is not available");
        }
        if (returning.startAt().isBefore(pickup.startAt().plusMinutes(120))) {
            conflict("return window starts too early");
        }
        // The two complete original slots are two claims of one future reservation.
        return List.of(new Claim(pickup.windowId(), interval(pickup.startAt(), pickup.endAt())),
                new Claim(returning.windowId(), interval(returning.startAt(), returning.endAt())));
    }

    /** OPEN and its derived SOLD_OUT sub-state are both open slots; the solver, not the
     * status, rejects net-new occupancy on a filled slot (a swap re-proofs after removing its
     * own reservation, so rescheduling inside a sold-out slot stays possible). */
    private static boolean openDerived(String status) {
        return "OPEN".equals(status) || "SOLD_OUT".equals(status);
    }

    /** A selected unified slot only needs to be an open slot of this store (CCR-SCH-SLOT-001:
     * no service/kind dimension; the pickup vs return role comes from the selection field). */
    private static boolean selected(CapacityProofQuery query, WindowFact window) {
        return window != null && openDerived(window.status())
                && query.storeId().equals(window.storeId());
    }

    private static Map<String, OrderProtectionFact> orderFacts(OrderProtectionSnapshot snapshot) {
        Map<String, OrderProtectionFact> byReservation = new HashMap<>();
        Set<String> orderIds = new HashSet<>();
        long assignments = 0;
        for (OrderProtectionFact fact : snapshot.items()) {
            if (fact == null || !snapshot.storeId().equals(fact.storeId())
                    || !orderIds.add(fact.orderId())) bad("invalid order protection facts");
            if (fact.currentStaffId() != null) assignments++;
            if (fact.reservationId() != null
                    && byReservation.putIfAbsent(fact.reservationId(), fact) != null) {
                bad("duplicate order reservation binding");
            }
        }
        if (assignments != snapshot.totalCurrentAssignments()) bad("incomplete assignments");
        return byReservation;
    }

    private static List<Reservation> reservations(StoreScheduleFacts schedule,
            Map<String, WindowFact> windows, Map<String, OrderProtectionFact> orders) {
        Map<String, List<ClaimFact>> claims = new HashMap<>();
        for (ClaimFact claim : schedule.claims()) {
            claims.computeIfAbsent(claim.reservationId(), ignored -> new java.util.ArrayList<>()).add(claim);
        }
        List<Reservation> result = new java.util.ArrayList<>();
        for (ReservationFact fact : schedule.reservations()) {
            if (!("TEMP_LOCKED".equals(fact.status()) || "CONFIRMED".equals(fact.status()))) {
                continue;
            }
            OrderProtectionFact linked = orders.get(fact.reservationId());
            if (linked == null || !fact.orderId().equals(linked.orderId())
                    || !fact.userId().equals(linked.userId())
                    || !fact.merchantId().equals(linked.merchantId())
                    || !fact.storeId().equals(linked.storeId())
                    || !fact.serviceId().equals(linked.serviceId())
                    || !fact.fulfillmentType().equals(linked.fulfillmentType())) {
                bad("active reservation and order facts disagree");
            }
            List<Claim> segments = new java.util.ArrayList<>();
            for (ClaimFact claim : claims.getOrDefault(fact.reservationId(), List.of())) {
                if (!windows.containsKey(claim.windowId())) bad("claim original window is absent");
                segments.add(new Claim(claim.windowId(), interval(claim.startAt(), claim.endAt())));
            }
            if (segments.isEmpty()) bad("active reservation has no claim");
            result.add(new Reservation(fact.reservationId(), segments));
        }
        return result;
    }

    private static boolean validInterval(OffsetDateTime start, OffsetDateTime end) {
        return start != null && end != null && start.getSecond() == 0 && start.getNano() == 0
                && end.getSecond() == 0 && end.getNano() == 0 && end.isAfter(start);
    }

    private static Interval interval(OffsetDateTime start, OffsetDateTime end) {
        return new Interval(start.toInstant(), end.toInstant());
    }

    private static long apiId(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) throw new IllegalArgumentException("nonpositive");
            return id;
        } catch (RuntimeException malformed) {
            invalid("public ID is invalid");
            throw malformed;
        }
    }

    private void rollbackOnly() {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void conflict(String message) {
        throw new ApiException(CAPACITY_EXCEEDED, message);
    }

    private static void bad(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
