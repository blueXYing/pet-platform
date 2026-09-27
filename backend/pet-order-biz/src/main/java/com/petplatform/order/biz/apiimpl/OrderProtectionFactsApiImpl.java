package com.petplatform.order.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.order.biz.infrastructure.persistence.OrderProtectionReadStore;
import com.petplatform.order.biz.infrastructure.persistence.OrderProtectionReadStore.AssignmentRow;
import com.petplatform.order.biz.infrastructure.persistence.OrderProtectionReadStore.OrderRow;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete ORDER-owned facts for a caller already holding the SCH store guard. */
public final class OrderProtectionFactsApiImpl implements OrderProtectionFactsApi {
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi schedule;
    private final MerchantCurrentStaffFactsApi merchant;
    private final Clock clock;
    private final OrderProtectionReadStore orders;

    public OrderProtectionFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi schedule, MerchantCurrentStaffFactsApi merchant, Clock clock) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.schedule = Objects.requireNonNull(schedule, "schedule facts are required");
        this.merchant = Objects.requireNonNull(merchant, "merchant facts are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.orders = new OrderProtectionReadStore(source);
    }

    @Override
    public OrderProtectionSnapshot readStore(String storeId, QueryContext context) {
        String id = positiveId(storeId);
        return guarded(id, () -> readCompleteStore(id, context));
    }

    @Override
    public OrderProtectionSnapshot getByReservations(String storeId, List<String> reservationIds,
            QueryContext context) {
        String id = positiveId(storeId);
        List<String> requested = requiredIds(reservationIds);
        return guarded(id, () -> {
            OrderProtectionSnapshot all = readCompleteStore(id, context);
            Map<String, OrderProtectionFact> byReservation = new HashMap<>();
            for (OrderProtectionFact fact : all.items()) {
                byReservation.put(fact.reservationId(), fact);
            }
            List<OrderProtectionFact> selected = new ArrayList<>(requested.size());
            for (String reservationId : requested) {
                OrderProtectionFact fact = byReservation.get(reservationId);
                if (fact == null) throw unavailable("reservation has no bound order");
                selected.add(fact);
            }
            return new OrderProtectionSnapshot(id, true, all.totalOrders(),
                    all.totalCurrentAssignments(), selected);
        });
    }

    @Override
    public OrderProtectionSnapshot getCurrentAssignments(String storeId, List<String> affectedStaffIds,
            QueryContext context) {
        String id = positiveId(storeId);
        Set<String> target = affectedStaffIds == null ? null : Set.copyOf(requiredIds(affectedStaffIds));
        return guarded(id, () -> {
            OrderProtectionSnapshot all = readCompleteStore(id, context);
            List<OrderProtectionFact> selected = all.items().stream()
                    .filter(fact -> fact.currentStaffId() != null)
                    .filter(fact -> target == null || target.contains(fact.currentStaffId()))
                    .toList();
            return new OrderProtectionSnapshot(id, true, all.totalOrders(),
                    all.totalCurrentAssignments(), selected);
        });
    }

    private <T> T guarded(String storeId, Supplier<T> work) {
        try {
            guard.requireHeld(storeId, source);
            return work.get();
        } catch (RuntimeException failure) {
            Object resource = TransactionSynchronizationManager.getResource(source);
            if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw failure;
        }
    }

    private OrderProtectionSnapshot readCompleteStore(String storeId, QueryContext context) {
        List<OrderRow> parentRows = orders.readStoreOrders(storeId);
        List<AssignmentRow> assignmentRows = orders.readStoreAssignments(storeId);
        if (orders.hasGlobalCurrentOrphan()) throw unavailable("orphan current assignment");

        Map<String, OrderRow> byId = new LinkedHashMap<>();
        Map<String, OrderRow> byReservationId = new HashMap<>();
        for (OrderRow row : parentRows) {
            validateOrder(row, storeId);
            if (byId.put(row.id(), row) != null ||
                    byReservationId.put(row.reservationId(), row) != null) {
                throw unavailable("duplicate order identity");
            }
        }
        Map<String, AssignmentRow> currentByOrder = new HashMap<>();
        for (AssignmentRow assignment : assignmentRows) {
            if (!byId.containsKey(assignment.orderId()) || !validId(assignment.id()) ||
                    !validId(assignment.staffId()) || assignment.version() < 0 ||
                    (assignment.isCurrent() != 0 && assignment.isCurrent() != 1)) {
                throw unavailable("invalid assignment row");
            }
            if (assignment.isCurrent() == 1 &&
                    currentByOrder.put(assignment.orderId(), assignment) != null) {
                throw unavailable("multiple current assignments");
            }
        }
        for (OrderRow row : parentRows) {
            AssignmentRow current = currentByOrder.get(row.id());
            if ((row.serviceStaffId() == null) != (current == null) ||
                    (current != null && !current.staffId().equals(row.serviceStaffId()))) {
                throw unavailable("order and current assignment differ");
            }
        }

        StoreScheduleFacts scheduleFacts = readSchedule(storeId, context);
        CurrentStoreStaffFacts merchantFacts = readMerchant(storeId, context);
        Map<String, ReservationFact> reservations = new HashMap<>();
        for (ReservationFact reservation : scheduleFacts.reservations()) {
            if (reservation == null || reservations.put(reservation.reservationId(), reservation) != null) {
                throw unavailable("duplicate or null reservation fact");
            }
            OrderRow bound = byId.get(reservation.orderId());
            if (bound == null || !bound.reservationId().equals(reservation.reservationId())) {
                throw unavailable("reservation has no bidirectional order binding");
            }
        }
        Map<String, CurrentStaffFact> staff = new HashMap<>();
        for (CurrentStaffFact person : merchantFacts.items()) {
            if (person == null || staff.put(person.staffId(), person) != null) {
                throw unavailable("duplicate or null merchant staff fact");
            }
        }
        Map<String, List<ClaimFact>> claims = new HashMap<>();
        for (ClaimFact claim : scheduleFacts.claims()) {
            if (claim == null || claim.reservationId() == null) {
                throw unavailable("invalid schedule claim fact");
            }
            claims.computeIfAbsent(claim.reservationId(), ignored -> new ArrayList<>()).add(claim);
        }

        List<OrderProtectionFact> facts = new ArrayList<>(parentRows.size());
        for (OrderRow row : parentRows) {
            ReservationFact reservation = reservations.get(row.reservationId());
            if (reservation == null || !row.id().equals(reservation.orderId()) ||
                    !row.userId().equals(reservation.userId()) ||
                    !row.merchantId().equals(reservation.merchantId()) ||
                    !row.storeId().equals(reservation.storeId()) ||
                    !row.serviceId().equals(reservation.serviceId()) ||
                    !row.fulfillmentType().equals(reservation.fulfillmentType()) ||
                    !row.merchantId().equals(merchantFacts.merchantId())) {
                throw unavailable("order and reservation facts differ");
            }
            boolean protect = protectRequired(row, reservation,
                    claims.getOrDefault(reservation.reservationId(), List.of()));
            AssignmentRow assignment = currentByOrder.get(row.id());
            if (protect && assignment != null) {
                CurrentStaffFact person = staff.get(assignment.staffId());
                if (person == null || !row.storeId().equals(person.storeId()) ||
                        !row.merchantId().equals(person.merchantId())) {
                    throw unavailable("protected staff is not attributable to the store");
                }
            }
            facts.add(new OrderProtectionFact(row.id(), row.reservationId(), row.userId(),
                    row.merchantId(), row.storeId(), row.serviceId(), row.fulfillmentType(),
                    row.serviceStaffId(), row.orderStage(), row.verificationStatus(),
                    Long.toString(row.version()), assignment == null ? null : assignment.id(),
                    assignment == null ? null : Long.toString(assignment.version()), protect));
        }
        return new OrderProtectionSnapshot(storeId, true, facts.size(), currentByOrder.size(), facts);
    }

    private StoreScheduleFacts readSchedule(String storeId, QueryContext context) {
        try {
            StoreScheduleFacts result = schedule.readStore(storeId, context);
            if (result == null || !result.complete() || !storeId.equals(result.storeId()) ||
                    result.reservations() == null || result.claims() == null) {
                throw unavailable("schedule facts incomplete");
            }
            return result;
        } catch (RuntimeException failure) {
            throw unavailable("schedule facts unavailable");
        }
    }

    private CurrentStoreStaffFacts readMerchant(String storeId, QueryContext context) {
        try {
            CurrentStoreStaffFacts result = merchant.readStore(storeId, context);
            if (result == null || !result.complete() || !storeId.equals(result.storeId()) ||
                    !validId(result.merchantId()) || result.items() == null) {
                throw unavailable("merchant facts incomplete");
            }
            return result;
        } catch (RuntimeException failure) {
            throw unavailable("merchant facts unavailable");
        }
    }

    private boolean protectRequired(OrderRow order, ReservationFact reservation, List<ClaimFact> claims) {
        String stage = order.orderStage();
        String verification = order.verificationStatus();
        String status = reservation.status();
        boolean active = "TEMP_LOCKED".equals(status) || "CONFIRMED".equals(status);
        boolean released = "RELEASED".equals(status) || "EXPIRED".equals(status);
        if ((!active && !released) ||
                (!"UNVERIFIED".equals(verification) && !"VERIFIED".equals(verification))) {
            throw unavailable("unknown order or reservation status");
        }
        if ("COMPLETED".equals(stage)) {
            if (!"VERIFIED".equals(verification)) {
                throw unavailable("completed order is unverified");
            }
            if (active) {
                Instant now = clock.instant();
                for (ClaimFact claim : claims) {
                    if (claim.endAt() == null || claim.endAt().toInstant().isAfter(now)) {
                        throw unavailable("completed order has future active claim");
                    }
                }
            }
            return false;
        }
        if ("CANCELED".equals(stage)) {
            return active && order.serviceStaffId() != null;
        }
        if ("PENDING_PAYMENT".equals(stage) || "PENDING_CONFIRM".equals(stage) ||
                "PENDING_SERVICE".equals(stage)) {
            if (!"UNVERIFIED".equals(verification) || released) {
                throw unavailable("in-flight order has released or verified reservation");
            }
            return order.serviceStaffId() != null;
        }
        throw unavailable("unknown order stage");
    }

    private static void validateOrder(OrderRow row, String storeId) {
        if (row == null || !storeId.equals(row.storeId()) || !validId(row.id()) ||
                !validId(row.reservationId()) || !validId(row.userId()) ||
                !validId(row.merchantId()) || !validId(row.serviceId()) ||
                (row.serviceStaffId() != null && !validId(row.serviceStaffId())) ||
                row.version() < 0 ||
                (!"IN_STORE".equals(row.fulfillmentType()) &&
                        !"PICKUP_DELIVERY".equals(row.fulfillmentType()))) {
            throw unavailable("invalid order row");
        }
    }

    private static List<String> requiredIds(List<String> ids) {
        if (ids == null) throw invalid("ID list is required");
        List<String> normalized = new ArrayList<>(ids.size());
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            String parsed = positiveId(id);
            if (!seen.add(parsed)) throw invalid("duplicate ID");
            normalized.add(parsed);
        }
        return normalized;
    }

    private static String positiveId(String value) {
        if (!validId(value)) throw invalid("positive decimal ID is required");
        return value;
    }

    private static boolean validId(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '0') return false;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 && Long.toString(parsed).equals(value);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
