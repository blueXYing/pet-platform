package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.WindowFact;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * System-side link between reservation capacity changes and the derived SOLD_OUT window state
 * (Contract52 §7 blocker 1, ruling 2026-10-05, plan A): a service window is an explicit,
 * system-computed SOLD_OUT ("已约满") while its effective claims fill its capacity and returns
 * to OPEN when a release frees it. Occupancy counts the active TEMP_LOCKED/CONFIRMED claims on
 * the window's original row — the same occupied notion as SCHW-D4 and the solver's store-wide
 * occupancy invariant — and capacity is the window's own configured capacity. The flip always
 * runs inside the caller's already-guarded transaction, so confirm/hold and the release paths
 * (ReservationExpiryApiImpl/ReservationRefundReleaseApiImpl/cancel-expiry, swap) commit their
 * reservation change and the window flip together; there is no second write channel. Flips are
 * derived facts, not merchant actions: they bump the window version but never write a
 * schedule_write_action audit row.
 */
final class WindowSoldOutDeriver {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private WindowSoldOutDeriver() {}

    /** The CAS window-status update shared by the command/write mapper statements. */
    @FunctionalInterface
    interface StatusStatement {
        int apply(long windowId, String expectedStatus, String nextStatus, LocalDateTime now);
    }

    /**
     * Re-derives OPEN/SOLD_OUT for the given windows of one store from the locked facts.
     * Unknown or non-open-derived targets are skipped; every flip CASes on the observed status
     * and any unexpected row count fails closed as 503, rolling the whole command back.
     */
    static void rederive(String storeId, Collection<Long> windowIds, QueryContext context,
            ScheduleProtectionFactsApi facts, StatusStatement update, LocalDateTime now) {
        if (windowIds == null || windowIds.isEmpty()) return;
        StoreScheduleFacts snapshot = facts.readStore(storeId, context);
        Set<Long> activeReservations = new HashSet<>();
        for (ReservationFact reservation : snapshot.reservations()) {
            if ("TEMP_LOCKED".equals(reservation.status())
                    || "CONFIRMED".equals(reservation.status())) {
                activeReservations.add(IDS.fromApi(reservation.reservationId()));
            }
        }
        Map<Long, Integer> occupancy = new HashMap<>();
        for (ClaimFact claim : snapshot.claims()) {
            if (activeReservations.contains(IDS.fromApi(claim.reservationId()))) {
                occupancy.merge(IDS.fromApi(claim.windowId()), 1, Integer::sum);
            }
        }
        Map<Long, WindowFact> windows = new HashMap<>();
        for (WindowFact window : snapshot.windows()) {
            windows.put(IDS.fromApi(window.windowId()), window);
        }
        for (Long target : new TreeSet<>(windowIds)) {
            WindowFact window = windows.get(target);
            if (window == null) continue;
            String status = window.status();
            if (!"OPEN".equals(status) && !"SOLD_OUT".equals(status)) continue;
            String next = occupancy.getOrDefault(target, 0) >= window.configuredCapacity()
                    ? "SOLD_OUT" : "OPEN";
            if (next.equals(status)) continue;
            if (update.apply(target, status, next, now) != 1) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        "derived window status changed under the command");
            }
        }
    }
}
