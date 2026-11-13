package com.petplatform.schedule.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.schedule.api.dto.SelectionWindowDTO;
import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;
import com.petplatform.schedule.api.query.SelectionWindowQuery;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore.ClaimRow;
import com.petplatform.schedule.biz.infrastructure.persistence.SelectionReadStore.WindowRow;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SCH slot display projection (CCR-SCH-SLOT-001 unified slots). It never creates a hold or
 * promises a staff assignment. The slot list is the store's whole open timeline; a slot is
 * bookable for the queried service when that service has qualified, scheduled staff covering
 * it. The C-side item keeps its historical windowId/kind shape: kind is the selection ROLE the
 * service's fulfillment projects onto the slot (GENERAL for IN_STORE; PICKUP and RETURN for
 * PICKUP_DELIVERY), so the existing booking/reschedule consumers stay unchanged.
 */
public final class SelectionWindowQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final SelectionReadStore store;
    private final ServiceQueryApi services;
    private final QualifiedStaffFactsPort staff;
    private final Clock clock;

    public SelectionWindowQueryService(SelectionReadStore store, ServiceQueryApi services,
            QualifiedStaffFactsPort staff, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.services = Objects.requireNonNull(services);
        this.staff = staff;
        this.clock = Objects.requireNonNull(clock);
    }

    public SelectionWindowPageDTO page(SelectionWindowQuery query) {
        validate(query);
        ServiceBookabilityDTO first = bookability(query);
        ServiceSnapshotDTO snapshot = services.getServiceSnapshot(
                new ServiceSnapshotQuery(query.serviceId(), query.context()));
        if (snapshot == null || snapshot.fulfillmentType() == null
                || !query.serviceId().equals(snapshot.serviceId())
                || !query.storeId().equals(snapshot.storeId())
                || !first.merchantId().equals(snapshot.merchantId())) {
            unavailable("service fulfillment facts are inconsistent");
        }
        ServiceBookabilityDTO latest = bookability(query);
        if (!first.merchantId().equals(latest.merchantId())
                || !snapshot.merchantId().equals(latest.merchantId())) {
            unavailable("service binding changed during selection read");
        }
        boolean pickupDelivery =
                snapshot.fulfillmentType() == FulfillmentType.PICKUP_DELIVERY;
        if (staff == null) unavailable("qualified staff facts provider is unavailable");

        LocalDateTime from = query.startDate().atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = query.endDate().plusDays(1)
                .atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Instant now = clock.instant();
        List<SelectionWindowDTO> items = store.read(jdbc -> {
            if (store.hasBrokenClaimLinks(jdbc, id(query.storeId()), from, to)) {
                unavailable("selection claim links are inconsistent");
            }
            // CCR-SCH-SLOT-002: the store's bookable range (lead/maxDays, defaults 60/7)
            // hides every slot starting before now+lead or after now+maxDays — the C-end
            // selection list never offers an out-of-range slot.
            Map<String, Object> bookingRow = jdbc.selectBookingWindow(id(query.storeId()));
            BookingWindowRule.Range range = BookingWindowRule.resolve(
                    bookingRow == null ? null
                            : ScheduleSqlRows.nullableNumber(bookingRow, "lead_minutes"),
                    bookingRow == null ? null
                            : ScheduleSqlRows.nullableNumber(bookingRow, "max_days"));
            // Unified slots (CCR-SCH-SLOT-001): the whole store timeline is the candidate set.
            List<WindowRow> windows = store.windows(jdbc, id(query.storeId()), from, to);
            List<ClaimRow> claims = store.claims(jdbc, id(query.storeId()), from, to);
            Map<Long, Set<Long>> occupied = occupancy(claims, query, snapshot);
            List<SelectionWindowDTO> result = new ArrayList<>();
            Set<Long> windowIds = new HashSet<>();
            LocalDateTime priorOpenEnd = null;
            for (WindowRow row : windows) {
                validateWindow(row, query, snapshot.merchantId(), windowIds);
                // SOLD_OUT stays listed as an open-derived slot: remaining=0 keeps it
                // unbookable exactly like an at-capacity OPEN slot was (Contract53 §3).
                if (!openDerived(row.status())
                        || !row.end().toInstant(ZoneOffset.UTC).isAfter(now)) continue;
                // Store-wide slots may not overlap; adjacency is allowed.
                if (priorOpenEnd != null && priorOpenEnd.isAfter(row.start())) {
                    unavailable("overlapping original selection slots");
                }
                if (openDerived(row.status())) priorOpenEnd = row.end();
                OffsetDateTime start = row.start().atOffset(ZoneOffset.UTC);
                OffsetDateTime end = row.end().atOffset(ZoneOffset.UTC);
                if (!BookingWindowRule.within(start.toInstant(), now, range)) {
                    // Outside the bookable range: hidden from the selectable list entirely.
                    continue;
                }
                int qualified;
                try {
                    qualified = staff.countQualifiedAvailableStaff(query.storeId(),
                            query.serviceId(), start, end);
                } catch (ApiException known) {
                    throw known;
                } catch (RuntimeException failure) {
                    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                            "qualified staff facts are unavailable");
                }
                if (qualified < 0) unavailable("qualified staff count is invalid");
                // Effective capacity keeps the SSOT §12 service view: min(slot capacity,
                // qualified staff of this service covering the slot).
                int effective = Math.min(row.configuredCapacity(), qualified);
                int taken = occupied.getOrDefault(row.id(), Set.of()).size();
                int remaining = Math.max(0, effective - taken);
                boolean available = start.toInstant().isAfter(now) && remaining > 0;
                // Role projection keeps the historical item shape: a pickup-delivery service
                // sees the same slot as both a PICKUP and a RETURN candidate.
                if (pickupDelivery) {
                    result.add(new SelectionWindowDTO(IDS.toApi(row.id()), "PICKUP", start, end,
                            effective, taken, remaining, available));
                    result.add(new SelectionWindowDTO(IDS.toApi(row.id()), "RETURN", start, end,
                            effective, taken, remaining, available));
                } else {
                    result.add(new SelectionWindowDTO(IDS.toApi(row.id()), "GENERAL", start, end,
                            effective, taken, remaining, available));
                }
            }
            return List.copyOf(result);
        });
        return new SelectionWindowPageDTO(query.storeId(), query.serviceId(),
                query.startDate(), query.endDate(), items);
    }

    private ServiceBookabilityDTO bookability(SelectionWindowQuery query) {
        ServiceBookabilityDTO facts = services.checkBookable(new ServiceBookabilityQuery(
                query.serviceId(), query.storeId(), query.context()));
        if (facts == null || !query.serviceId().equals(facts.serviceId())
                || !query.storeId().equals(facts.storeId()) || facts.merchantId() == null) {
            unavailable("service bookability facts are inconsistent");
        }
        if (!facts.bookable()) {
            throw new ApiException("SERVICE_NOT_FOUND", "service resource not found");
        }
        return facts;
    }

    private static void validateWindow(WindowRow row, SelectionWindowQuery query,
            String merchantId, Set<Long> ids) {
        if (row.id() <= 0 || row.merchantId() <= 0 || row.storeId() <= 0 || !ids.add(row.id())
                || !merchantId.equals(IDS.toApi(row.merchantId()))
                || !query.storeId().equals(IDS.toApi(row.storeId()))
                || !validInterval(row.start(), row.end())
                || row.configuredCapacity() < 1 || row.version() < 0
                || !(openDerived(row.status()) || "CLOSED".equals(row.status()))) {
            unavailable("selection window facts are invalid");
        }
    }

    private static boolean openDerived(String status) {
        return "OPEN".equals(status) || "SOLD_OUT".equals(status);
    }

    /** Single-pool occupancy (CCR-SCH-SLOT-001): active claims on each slot across all
     * services — each reservation's claim structure follows its own fulfillment type. */
    private static Map<Long, Set<Long>> occupancy(List<ClaimRow> rows,
            SelectionWindowQuery query, ServiceSnapshotDTO snapshot) {
        Map<Long, List<ClaimRow>> byReservation = new HashMap<>();
        for (ClaimRow row : rows) {
            if (row.reservationId() <= 0 || row.merchantId() <= 0
                    || !snapshot.merchantId().equals(IDS.toApi(row.merchantId()))
                    || !query.storeId().equals(IDS.toApi(row.storeId()))
                    || !validInterval(row.start(), row.end())
                    || !("TEMP_LOCKED".equals(row.status()) || "CONFIRMED".equals(row.status())
                            || "RELEASED".equals(row.status()) || "EXPIRED".equals(row.status()))) {
                unavailable("reservation occupancy facts are invalid");
            }
            if ("TEMP_LOCKED".equals(row.status()) || "CONFIRMED".equals(row.status())) {
                byReservation.computeIfAbsent(row.reservationId(), ignored -> new ArrayList<>())
                        .add(row);
            }
        }
        Map<Long, Set<Long>> occupied = new HashMap<>();
        for (List<ClaimRow> claims : byReservation.values()) {
            ClaimRow parent = claims.getFirst();
            FulfillmentType fulfillment;
            try {
                fulfillment = FulfillmentType.valueOf(parent.fulfillmentType());
            } catch (RuntimeException unknown) {
                unavailable("active reservation fulfillment is unknown");
                throw unknown;
            }
            if (claims.size() != (fulfillment == FulfillmentType.IN_STORE ? 1 : 2)) {
                unavailable("active reservation claims are incomplete");
            }
            Set<String> kinds = new HashSet<>();
            for (ClaimRow claim : claims) {
                if (claim.claimId() == null || claim.claimId() <= 0
                        || claim.windowId() == null || claim.windowId() <= 0
                        || claim.claimStoreId() == null || claim.claimStoreId() != parent.storeId()
                        || claim.claimServiceId() == null
                        || claim.claimServiceId() != parent.serviceId()
                        || claim.originalMerchantId() == null
                        || claim.originalMerchantId() != parent.merchantId()
                        || claim.originalStoreId() == null
                        || claim.originalStoreId() != parent.storeId()
                        || !validKind(claim.claimKind()) || !kinds.add(claim.claimKind())
                        || !("OPEN".equals(claim.originalStatus())
                                || "SOLD_OUT".equals(claim.originalStatus()))
                        || !validInterval(claim.claimStart(), claim.claimEnd())
                        || !validInterval(claim.originalStart(), claim.originalEnd())) {
                    unavailable("original claim facts are invalid");
                }
                if (fulfillment == FulfillmentType.IN_STORE) {
                    if (!"GENERAL".equals(claim.claimKind())
                            || !claim.claimStart().equals(parent.start())
                            || !claim.claimEnd().equals(parent.end())
                            || claim.claimStart().isBefore(claim.originalStart())
                            || claim.claimEnd().isAfter(claim.originalEnd())
                            || parent.pickupStart() != null || parent.returnStart() != null) {
                        unavailable("GENERAL claim differs from reservation");
                    }
                } else if (!("PICKUP".equals(claim.claimKind())
                        || "RETURN".equals(claim.claimKind()))
                        || !claim.claimStart().equals(claim.originalStart())
                        || !claim.claimEnd().equals(claim.originalEnd())
                        || !("PICKUP".equals(claim.claimKind())
                                ? claim.claimStart().equals(parent.pickupStart())
                                : claim.claimStart().equals(parent.returnStart()))) {
                    unavailable("directional claim differs from original window");
                }
                occupied.computeIfAbsent(claim.windowId(), ignored -> new HashSet<>())
                        .add(parent.reservationId());
            }
            if (fulfillment == FulfillmentType.PICKUP_DELIVERY
                    && (!kinds.contains("PICKUP") || !kinds.contains("RETURN")
                            || parent.pickupStart() == null || parent.returnStart() == null
                            || parent.returnStart().isBefore(parent.pickupStart().plusMinutes(120)))) {
                unavailable("directional reservation facts are invalid");
            }
        }
        return occupied;
    }

    private static void validate(SelectionWindowQuery query) {
        if (query == null || query.startDate() == null || query.endDate() == null) {
            invalid("selection query and dates are required");
        }
        id(query.storeId());
        id(query.serviceId());
        if (query.endDate().isBefore(query.startDate())
                || ChronoUnit.DAYS.between(query.startDate(), query.endDate()) + 1 > 31) {
            invalid("selection date range is invalid");
        }
    }

    private static boolean validKind(String kind) {
        return "GENERAL".equals(kind) || "PICKUP".equals(kind) || "RETURN".equals(kind);
    }

    private static boolean validInterval(LocalDateTime start, LocalDateTime end) {
        return start != null && end != null && end.isAfter(start)
                && start.getSecond() == 0 && start.getNano() == 0
                && end.getSecond() == 0 && end.getNano() == 0;
    }

    private static long id(String raw) {
        try {
            long value = IDS.fromApi(raw);
            if (value > 0) return value;
        } catch (RuntimeException ignored) { }
        invalid("id is invalid");
        throw new IllegalStateException("unreachable");
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
