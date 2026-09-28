package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.WindowFact;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleMybatis;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleCommandMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Full SCH-owned current facts. This reader never starts another transaction or calls ORDER. */
public final class ScheduleProtectionFactsApiImpl implements ScheduleProtectionFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleCommandMapper mapper;

    public ScheduleProtectionFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.mapper = ScheduleMybatis.template(source).getMapper(ScheduleCommandMapper.class);
    }

    @Override
    public StoreScheduleFacts readStore(String storeId, QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        guard.requireHeld(storeId, source);
        long key = positiveId(storeId);
        try {
            List<WindowFact> windows = mapper.lockedWindows(key).stream()
                    .map(ScheduleProtectionFactsApiImpl::window).toList();
            List<ReservationFact> reservations = mapper.lockedReservations(key).stream()
                    .map(ScheduleProtectionFactsApiImpl::reservation).toList();
            List<ClaimFact> claims = mapper.lockedClaims(key).stream()
                    .map(ScheduleProtectionFactsApiImpl::claim).toList();
            // Diagnose cross-store corruption without locking rows from another healthy store.
            List<Long> crossStore = mapper.crossStoreClaims(key);
            if (!crossStore.isEmpty()) bad("claim store differs from its reservation store");
            StoreScheduleFacts facts = new StoreScheduleFacts(storeId, true, windows, reservations, claims);
            validate(facts);
            return facts;
        } catch (ApiException known) {
            rollbackOnly();
            throw known;
        } catch (RuntimeException failed) {
            rollbackOnly();
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    "schedule protected facts unavailable");
        }
    }

    private static WindowFact window(Map<String, Object> row) {
        return new WindowFact(ScheduleSqlRows.id(row, "id"), ScheduleSqlRows.id(row, "merchant_id"),
                ScheduleSqlRows.id(row, "store_id"), ScheduleSqlRows.id(row, "service_id"),
                ScheduleSqlRows.text(row, "window_kind"), ScheduleSqlRows.at(row, "start_at"),
                ScheduleSqlRows.at(row, "end_at"), (int) ScheduleSqlRows.number(row, "configured_capacity"),
                ScheduleSqlRows.text(row, "status"), ScheduleSqlRows.version(row, "version"));
    }

    private static ReservationFact reservation(Map<String, Object> row) {
        return new ReservationFact(ScheduleSqlRows.id(row, "id"), ScheduleSqlRows.id(row, "order_id"),
                ScheduleSqlRows.id(row, "user_id"), ScheduleSqlRows.id(row, "merchant_id"),
                ScheduleSqlRows.id(row, "store_id"), ScheduleSqlRows.id(row, "service_id"),
                ScheduleSqlRows.text(row, "fulfillment_type"), ScheduleSqlRows.at(row, "start_at"),
                ScheduleSqlRows.at(row, "end_at"), ScheduleSqlRows.at(row, "pickup_start_at"),
                ScheduleSqlRows.at(row, "return_start_at"), ScheduleSqlRows.text(row, "status"),
                ScheduleSqlRows.version(row, "version"));
    }

    private static ClaimFact claim(Map<String, Object> row) {
        return new ClaimFact(ScheduleSqlRows.id(row, "id"), ScheduleSqlRows.id(row, "reservation_id"),
                ScheduleSqlRows.id(row, "window_id"), ScheduleSqlRows.id(row, "store_id"),
                ScheduleSqlRows.id(row, "service_id"), ScheduleSqlRows.text(row, "kind"),
                ScheduleSqlRows.at(row, "start_at"), ScheduleSqlRows.at(row, "end_at"));
    }

    private static long positiveId(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) throw new IllegalArgumentException("nonpositive");
            return id;
        } catch (RuntimeException malformed) {
            bad("store id is invalid");
            throw malformed;
        }
    }

    private static void validate(StoreScheduleFacts facts) {
        Map<String, WindowFact> byWindow = new HashMap<>();
        Map<String, List<WindowFact>> openGroups = new HashMap<>();
        for (WindowFact window : facts.windows()) {
            if (!facts.storeId().equals(window.storeId()) || !validKind(window.kind())
                    || !validInterval(window.startAt(), window.endAt())
                    || window.configuredCapacity() < 0
                    || !("OPEN".equals(window.status()) || "CLOSED".equals(window.status()))
                    || byWindow.putIfAbsent(window.windowId(), window) != null) {
                bad("invalid schedule window facts");
            }
            if ("OPEN".equals(window.status())) {
                openGroups.computeIfAbsent(window.serviceId() + ":" + window.kind(),
                        ignored -> new ArrayList<>()).add(window);
            }
        }
        for (List<WindowFact> group : openGroups.values()) {
            group.sort(Comparator.comparing(WindowFact::startAt).thenComparing(WindowFact::windowId));
            for (int index = 1; index < group.size(); index++) {
                if (group.get(index - 1).endAt().isAfter(group.get(index).startAt())) {
                    bad("overlapping OPEN windows of one service and kind");
                }
            }
        }
        Map<String, ReservationFact> byReservation = new HashMap<>();
        for (ReservationFact reservation : facts.reservations()) {
            if (!facts.storeId().equals(reservation.storeId()) || reservation.userId() == null
                    || !validInterval(reservation.startAt(), reservation.endAt())
                    || !("IN_STORE".equals(reservation.fulfillmentType())
                        || "PICKUP_DELIVERY".equals(reservation.fulfillmentType()))
                    || !("TEMP_LOCKED".equals(reservation.status())
                        || "CONFIRMED".equals(reservation.status())
                        || "RELEASED".equals(reservation.status())
                        || "EXPIRED".equals(reservation.status()))
                    || byReservation.putIfAbsent(reservation.reservationId(), reservation) != null) {
                bad("invalid schedule reservation facts");
            }
        }
        Map<String, List<ClaimFact>> byParent = new HashMap<>();
        Set<String> claimIds = new HashSet<>();
        for (ClaimFact claim : facts.claims()) {
            ReservationFact parent = byReservation.get(claim.reservationId());
            WindowFact window = byWindow.get(claim.windowId());
            if (parent == null || window == null || !claimIds.add(claim.claimId())
                    || !facts.storeId().equals(claim.storeId())
                    || !parent.storeId().equals(claim.storeId())
                    || !parent.serviceId().equals(claim.serviceId())
                    || !window.serviceId().equals(claim.serviceId())
                    || !window.merchantId().equals(parent.merchantId())
                    || !window.kind().equals(claim.kind())
                    || !validInterval(claim.startAt(), claim.endAt())) {
                bad("invalid schedule claim relationship");
            }
            byParent.computeIfAbsent(parent.reservationId(), ignored -> new ArrayList<>()).add(claim);
        }
        for (ReservationFact reservation : facts.reservations()) {
            if (!active(reservation.status())) continue;
            List<ClaimFact> claims = byParent.getOrDefault(reservation.reservationId(), List.of());
            if ("IN_STORE".equals(reservation.fulfillmentType())) {
                if (claims.size() != 1 || !"GENERAL".equals(claims.getFirst().kind())
                        || reservation.pickupStartAt() != null || reservation.returnStartAt() != null
                        || !claims.getFirst().startAt().isEqual(reservation.startAt())
                        || !claims.getFirst().endAt().isEqual(reservation.endAt())) {
                    bad("incomplete GENERAL reservation claim");
                }
                WindowFact window = byWindow.get(claims.getFirst().windowId());
                if (!"OPEN".equals(window.status())
                        || window.startAt().isAfter(claims.getFirst().startAt())
                        || window.endAt().isBefore(claims.getFirst().endAt())) {
                    bad("GENERAL claim is outside its original open window");
                }
            } else {
                if (claims.size() != 2 || reservation.pickupStartAt() == null
                        || reservation.returnStartAt() == null || !minute(reservation.pickupStartAt())
                        || !minute(reservation.returnStartAt())
                        || reservation.returnStartAt().isBefore(reservation.pickupStartAt().plusMinutes(120))) {
                    bad("incomplete pickup and return reservation claims");
                }
                Set<String> kinds = new HashSet<>();
                for (ClaimFact claim : claims) {
                    WindowFact window = byWindow.get(claim.windowId());
                    OffsetDateTime expectedStart = "PICKUP".equals(claim.kind())
                            ? reservation.pickupStartAt() : reservation.returnStartAt();
                    if (!("PICKUP".equals(claim.kind()) || "RETURN".equals(claim.kind()))
                            || !kinds.add(claim.kind()) || !"OPEN".equals(window.status())
                            || !claim.startAt().isEqual(window.startAt())
                            || !claim.endAt().isEqual(window.endAt())
                            || !claim.startAt().isEqual(expectedStart)) {
                        bad("pickup or return claim differs from its original open window");
                    }
                }
            }
        }
    }

    private static boolean active(String status) {
        return "TEMP_LOCKED".equals(status) || "CONFIRMED".equals(status);
    }

    private static boolean validKind(String kind) {
        return "GENERAL".equals(kind) || "PICKUP".equals(kind) || "RETURN".equals(kind);
    }

    private static boolean validInterval(OffsetDateTime start, OffsetDateTime end) {
        return start != null && end != null && minute(start) && minute(end) && end.isAfter(start);
    }

    private static boolean minute(OffsetDateTime at) {
        return at.getSecond() == 0 && at.getNano() == 0;
    }

    private void rollbackOnly() {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }

    private static void bad(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
