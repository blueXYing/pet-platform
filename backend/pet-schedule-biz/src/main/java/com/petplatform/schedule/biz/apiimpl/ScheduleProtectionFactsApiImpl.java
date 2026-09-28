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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Full SCH-owned current facts. This reader never starts another transaction or calls ORDER. */
public final class ScheduleProtectionFactsApiImpl implements ScheduleProtectionFactsApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final ScheduleCapacityGuardApi guard;
    private final JdbcTemplate jdbc;

    public ScheduleProtectionFactsApiImpl(DataSource source, ScheduleCapacityGuardApi guard) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.jdbc = new JdbcTemplate(source);
    }

    @Override
    public StoreScheduleFacts readStore(String storeId, QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        guard.requireHeld(storeId, source);
        long key = positiveId(storeId);
        try {
            List<WindowFact> windows = jdbc.query("SELECT id,merchant_id,store_id,service_id,window_kind,"
                    + "start_at,end_at,configured_capacity,status,version FROM schedule_availability_window "
                    + "FORCE INDEX (idx_schedule_service_time) "
                    + "WHERE store_id=? ORDER BY id FOR UPDATE", (rs, n) -> window(rs), key);
            List<ReservationFact> reservations = jdbc.query("SELECT id,order_id,user_id,merchant_id,"
                    + "store_id,service_id,fulfillment_type,start_at,end_at,pickup_start_at,"
                    + "return_start_at,status,version FROM schedule_reservation "
                    + "FORCE INDEX (idx_reservation_service_time) WHERE store_id=? "
                    + "ORDER BY id FOR UPDATE", (rs, n) -> reservation(rs), key);
            List<ClaimFact> claims = jdbc.query("SELECT c.id,c.reservation_id,c.window_id,c.store_id,"
                    + "c.service_id,c.kind,c.start_at,c.end_at FROM schedule_reservation_claim c "
                    + "FORCE INDEX (idx_claim_store_reservation) WHERE c.store_id=? "
                    + "ORDER BY c.id FOR UPDATE", (rs, n) -> claim(rs), key);
            // Diagnose cross-store corruption without locking rows from another healthy store.
            List<Long> crossStore = jdbc.query("SELECT c.id FROM schedule_reservation r "
                    + "FORCE INDEX (idx_reservation_service_time) "
                    + "JOIN schedule_reservation_claim c FORCE INDEX (uk_claim_reservation_kind) "
                    + "ON c.reservation_id=r.id WHERE r.store_id=? AND c.store_id<>? LIMIT 1",
                    (rs, n) -> rs.getLong(1), key, key);
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

    private static WindowFact window(ResultSet rs) throws SQLException {
        return new WindowFact(id(rs, "id"), id(rs, "merchant_id"), id(rs, "store_id"),
                id(rs, "service_id"), rs.getString("window_kind"), at(rs, "start_at"),
                at(rs, "end_at"), rs.getInt("configured_capacity"), rs.getString("status"),
                version(rs, "version"));
    }

    private static ReservationFact reservation(ResultSet rs) throws SQLException {
        return new ReservationFact(id(rs, "id"), id(rs, "order_id"), id(rs, "user_id"),
                id(rs, "merchant_id"), id(rs, "store_id"), id(rs, "service_id"),
                rs.getString("fulfillment_type"), at(rs, "start_at"), at(rs, "end_at"),
                at(rs, "pickup_start_at"), at(rs, "return_start_at"), rs.getString("status"),
                version(rs, "version"));
    }

    private static ClaimFact claim(ResultSet rs) throws SQLException {
        return new ClaimFact(id(rs, "id"), id(rs, "reservation_id"), id(rs, "window_id"),
                id(rs, "store_id"), id(rs, "service_id"), rs.getString("kind"),
                at(rs, "start_at"), at(rs, "end_at"));
    }

    private static String id(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value <= 0) bad("missing or invalid " + column);
        return IDS.toApi(value);
    }

    private static String version(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) bad("invalid " + column);
        return Long.toString(value);
    }

    private static OffsetDateTime at(ResultSet rs, String column) throws SQLException {
        java.time.LocalDateTime value = rs.getObject(column,java.time.LocalDateTime.class);
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
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
