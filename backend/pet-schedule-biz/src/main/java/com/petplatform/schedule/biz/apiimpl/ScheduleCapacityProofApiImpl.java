package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
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
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Staff;
import com.petplatform.schedule.biz.domain.service.CapacityFeasibilitySolver.Window;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resource-only proof. A future write command must repeat it under its own guard before commit. */
public final class ScheduleCapacityProofApiImpl implements ScheduleCapacityProofApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final String CANDIDATE_ID = "_candidate_";
    private static final String CAPACITY_EXCEEDED = "SCHEDULE_CAPACITY_EXCEEDED";
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final MerchantCurrentStaffFactsApi merchant;
    private final OrderProtectionFactsApi order;
    private final JdbcTemplate jdbc;
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
        this.jdbc = new JdbcTemplate(source);
    }

    @Override
    public CapacityProofResult checkNewReservation(CapacityProofQuery query) {
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
            Map<String, CurrentStaffFact> people = people(employees);
            List<CapabilityRow> capabilities = capabilities(people.keySet());
            List<AvailabilityRow> availability = availability(query.storeId());
            List<Staff> staff = staff(people, employees.merchantId(), capabilities, availability);
            Map<String, OrderProtectionFact> ordersByReservation = orderFacts(orders);
            List<Reservation> reservations = reservations(schedule, windows, ordersByReservation);
            reservations.add(new Reservation(CANDIDATE_ID, query.serviceId(), candidateClaims, null));
            List<Window> capacity = schedule.windows().stream()
                    .map(window -> new Window(window.windowId(), window.configuredCapacity())).toList();
            CapacityFeasibilitySolver.Result result =
                    solver.solve(CANDIDATE_ID, reservations, capacity, staff, budgetMillis);
            if (result.outcome() == Outcome.BUDGET_EXHAUSTED) {
                bad("schedule capacity proof calculation budget exhausted");
            }
            if (result.outcome() == Outcome.INFEASIBLE) {
                throw new ApiException(CAPACITY_EXCEEDED, "schedule capacity is unavailable");
            }
            return new CapacityProofResult(query.storeId(), true, result.evaluatedReservations());
        } catch (ApiException known) {
            rollbackOnly();
            throw known;
        } catch (RuntimeException failed) {
            rollbackOnly();
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule capacity proof dependency unavailable");
        }
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
            List<WindowFact> eligible = windows.values().stream()
                    .filter(window -> "OPEN".equals(window.status())
                            && "GENERAL".equals(window.kind())
                            && query.storeId().equals(window.storeId())
                            && query.serviceId().equals(window.serviceId())
                            && !window.startAt().isAfter(query.appointmentStart())
                            && !window.endAt().isBefore(query.appointmentEnd()))
                    .toList();
            if (eligible.size() != 1) conflict("GENERAL appointment needs one original open window");
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
        if (!selected(query, pickup, "PICKUP") || !selected(query, returning, "RETURN")) {
            conflict("selected pickup or return window is not available");
        }
        if (returning.startAt().isBefore(pickup.startAt().plusMinutes(120))) {
            conflict("return window starts too early");
        }
        // The two complete original windows are two claims of one future reservation.
        return List.of(new Claim(pickup.windowId(), interval(pickup.startAt(), pickup.endAt())),
                new Claim(returning.windowId(), interval(returning.startAt(), returning.endAt())));
    }

    private boolean selected(CapacityProofQuery query, WindowFact window, String kind) {
        return window != null && "OPEN".equals(window.status()) && kind.equals(window.kind())
                && query.storeId().equals(window.storeId())
                && query.serviceId().equals(window.serviceId());
    }

    private static Map<String, CurrentStaffFact> people(CurrentStoreStaffFacts employees) {
        if (employees.merchantId() == null) bad("employee merchant is missing");
        Map<String, CurrentStaffFact> result = new HashMap<>();
        for (CurrentStaffFact person : employees.items()) {
            if (person == null || !employees.storeId().equals(person.storeId())
                    || !employees.merchantId().equals(person.merchantId())
                    || !("ACTIVE".equals(person.employmentStatus())
                        || "INACTIVE".equals(person.employmentStatus()))
                    || parseNonnegative(person.version()) < 0
                    || result.putIfAbsent(person.staffId(), person) != null) {
                bad("invalid employee facts");
            }
        }
        return result;
    }

    private List<CapabilityRow> capabilities(Set<String> staffIds) {
        if (staffIds.isEmpty()) return List.of();
        List<Long> keys = staffIds.stream().map(ScheduleCapacityProofApiImpl::apiId)
                .sorted().toList();
        List<CapabilityRow> result = new ArrayList<>();
        // Chunking only bounds SQL bind parameters; it does not impose a business staff limit.
        for (int from = 0; from < keys.size(); from += 500) {
            List<Long> chunk = keys.subList(from, Math.min(from + 500, keys.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            result.addAll(jdbc.query("SELECT id,staff_id,service_id,status FROM staff_service_capability "
                    + "FORCE INDEX (uk_staff_service) "
                    + "WHERE staff_id IN (" + placeholders + ") ORDER BY staff_id,service_id FOR UPDATE",
                    (rs, n) -> new CapabilityRow(id(rs, "staff_id"), id(rs, "service_id"),
                            rs.getString("status")), chunk.toArray()));
        }
        return result;
    }

    private List<AvailabilityRow> availability(String storeId) {
        return jdbc.query("SELECT id,store_id,staff_id,start_at,end_at,status,version "
                + "FROM staff_availability_window FORCE INDEX (idx_store_avail_time) WHERE store_id=? "
                + "ORDER BY staff_id,start_at,id FOR UPDATE",
                (rs, n) -> new AvailabilityRow(id(rs, "store_id"), id(rs, "staff_id"),
                        at(rs, "start_at"), at(rs, "end_at"), rs.getString("status"),
                        version(rs, "version")), apiId(storeId));
    }

    private static List<Staff> staff(Map<String, CurrentStaffFact> people, String merchantId,
            List<CapabilityRow> capabilities, List<AvailabilityRow> availability) {
        Map<String, Set<String>> serviceIds = new HashMap<>();
        for (CapabilityRow row : capabilities) {
            if (!people.containsKey(row.staffId()) || !"ENABLED".equals(row.status())) {
                bad("invalid staff capability facts");
            }
            if (!serviceIds.computeIfAbsent(row.staffId(), ignored -> new HashSet<>())
                    .add(row.serviceId())) bad("duplicate staff capability");
        }
        Map<String, List<Interval>> intervals = new HashMap<>();
        for (AvailabilityRow row : availability) {
            if (!people.containsKey(row.staffId())
                    || !("AVAILABLE".equals(row.status()) || "CLOSED".equals(row.status()))
                    || !validInterval(row.start(), row.end())) {
                bad("invalid staff availability facts");
            }
            if ("AVAILABLE".equals(row.status())) {
                intervals.computeIfAbsent(row.staffId(), ignored -> new ArrayList<>())
                        .add(interval(row.start(), row.end()));
            }
        }
        List<Staff> result = new ArrayList<>();
        for (CurrentStaffFact person : people.values()) {
            if (!merchantId.equals(person.merchantId())) bad("employee merchant mismatch");
            List<Interval> schedule = intervals.getOrDefault(person.staffId(), List.of()).stream()
                    .sorted(Comparator.comparing(Interval::start)).toList();
            for (int index = 1; index < schedule.size(); index++) {
                if (schedule.get(index - 1).overlaps(schedule.get(index))) {
                    bad("overlapping effective staff availability");
                }
            }
            if ("ACTIVE".equals(person.employmentStatus()) && person.serviceEnabled()) {
                result.add(new Staff(person.staffId(),
                        serviceIds.getOrDefault(person.staffId(), Set.of()), schedule));
            }
        }
        return result;
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
            claims.computeIfAbsent(claim.reservationId(), ignored -> new ArrayList<>()).add(claim);
        }
        List<Reservation> result = new ArrayList<>();
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
            List<Claim> segments = new ArrayList<>();
            for (ClaimFact claim : claims.getOrDefault(fact.reservationId(), List.of())) {
                if (!windows.containsKey(claim.windowId())) bad("claim original window is absent");
                segments.add(new Claim(claim.windowId(), interval(claim.startAt(), claim.endAt())));
            }
            if (segments.isEmpty()) bad("active reservation has no claim");
            result.add(new Reservation(fact.reservationId(), fact.serviceId(), segments,
                    linked.protectRequired() ? linked.currentStaffId() : null));
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

    private static String id(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value <= 0) bad("invalid " + column);
        return IDS.toApi(value);
    }

    private static String version(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) bad("invalid " + column);
        return Long.toString(value);
    }

    private static OffsetDateTime at(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(java.time.ZoneOffset.UTC);
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

    private static long parseNonnegative(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) bad("negative version");
            return parsed;
        } catch (RuntimeException malformed) {
            bad("invalid version");
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

    private record CapabilityRow(String staffId, String serviceId, String status) {}
    private record AvailabilityRow(String storeId, String staffId, OffsetDateTime start,
            OffsetDateTime end, String status, String version) {}
}
