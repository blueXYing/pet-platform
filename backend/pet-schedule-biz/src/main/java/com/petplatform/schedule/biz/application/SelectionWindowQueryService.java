package com.petplatform.schedule.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.schedule.api.dto.SelectionWindowDTO;
import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;
import com.petplatform.schedule.api.query.SelectionWindowQuery;
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

/** SCH original-window display projection. It never creates a hold or promises a staff assignment. */
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
        String applicable = switch (snapshot.fulfillmentType()) {
            case IN_STORE -> "GENERAL";
            case PICKUP_DELIVERY -> "PICKUP_RETURN";
        };
        if (query.kind() != null && !("GENERAL".equals(applicable) && "GENERAL".equals(query.kind()))
                && !("PICKUP_RETURN".equals(applicable)
                        && ("PICKUP".equals(query.kind()) || "RETURN".equals(query.kind())))) {
            invalid("kind does not match service fulfillment");
        }
        if (staff == null) unavailable("qualified staff facts provider is unavailable");

        LocalDateTime from = query.startDate().atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = query.endDate().plusDays(1)
                .atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Instant now = clock.instant();
        List<SelectionWindowDTO> items = store.read(jdbc -> {
            if (store.hasBrokenClaimLinks(jdbc, id(query.storeId()), id(query.serviceId()),
                    from, to)) unavailable("selection claim links are inconsistent");
            List<WindowRow> windows = store.windows(jdbc, id(query.storeId()),
                    id(query.serviceId()), from, to);
            List<ClaimRow> claims = store.claims(jdbc, id(query.storeId()),
                    id(query.serviceId()), from, to);
            Map<Long, Set<Long>> occupied = occupancy(claims, query, snapshot);
            List<SelectionWindowDTO> result = new ArrayList<>();
            Map<String, LocalDateTime> openEnds = new HashMap<>();
            Set<Long> windowIds = new HashSet<>();
            for (WindowRow row : windows) {
                validateWindow(row, query, snapshot.merchantId(), windowIds, openEnds);
                // SOLD_OUT stays listed as an open-derived window: remaining=0 keeps it
                // unbookable exactly like an at-capacity OPEN window was (Contract53 §3).
                if (!openDerived(row.status()) || !matches(row.kind(), applicable, query.kind())
                        || !row.end().toInstant(ZoneOffset.UTC).isAfter(now)) continue;
                OffsetDateTime start = row.start().atOffset(ZoneOffset.UTC);
                OffsetDateTime end = row.end().atOffset(ZoneOffset.UTC);
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
                int effective = Math.min(row.configuredCapacity(), qualified);
                int taken = occupied.getOrDefault(row.id(), Set.of()).size();
                int remaining = Math.max(0, effective - taken);
                result.add(new SelectionWindowDTO(IDS.toApi(row.id()), row.kind(), start, end,
                        effective, taken, remaining,
                        start.toInstant().isAfter(now) && remaining > 0));
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

    private static boolean matches(String kind, String applicable, String requested) {
        return ("GENERAL".equals(applicable) ? "GENERAL".equals(kind)
                : ("PICKUP".equals(kind) || "RETURN".equals(kind)))
                && (requested == null || requested.equals(kind));
    }

    private static void validateWindow(WindowRow row, SelectionWindowQuery query,
            String merchantId, Set<Long> ids, Map<String, LocalDateTime> openEnds) {
        if (row.id() <= 0 || row.merchantId() <= 0 || row.storeId() <= 0
                || row.serviceId() <= 0 || !ids.add(row.id())
                || !merchantId.equals(IDS.toApi(row.merchantId()))
                || !query.storeId().equals(IDS.toApi(row.storeId()))
                || !query.serviceId().equals(IDS.toApi(row.serviceId()))
                || !validKind(row.kind()) || !validInterval(row.start(), row.end())
                || row.configuredCapacity() < 1 || row.version() < 0
                || !(openDerived(row.status()) || "CLOSED".equals(row.status()))) {
            unavailable("selection window facts are invalid");
        }
        // SOLD_OUT is an open-derived sub-state and joins the same overlap invariant.
        if (openDerived(row.status())) {
            LocalDateTime priorEnd = openEnds.put(row.kind(), row.end());
            if (priorEnd != null && priorEnd.isAfter(row.start())) {
                unavailable("overlapping original selection windows");
            }
        }
    }

    private static boolean openDerived(String status) {
        return "OPEN".equals(status) || "SOLD_OUT".equals(status);
    }

    private static Map<Long, Set<Long>> occupancy(List<ClaimRow> rows,
            SelectionWindowQuery query, ServiceSnapshotDTO snapshot) {
        FulfillmentType fulfillment = snapshot.fulfillmentType();
        Map<Long, List<ClaimRow>> byReservation = new HashMap<>();
        for (ClaimRow row : rows) {
            if (row.reservationId() <= 0 || row.merchantId() <= 0
                    || !snapshot.merchantId().equals(IDS.toApi(row.merchantId()))
                    || !query.storeId().equals(IDS.toApi(row.storeId()))
                    || !query.serviceId().equals(IDS.toApi(row.serviceId()))
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
            if (!fulfillment.name().equals(parent.fulfillmentType())
                    || claims.size() != (fulfillment == FulfillmentType.IN_STORE ? 1 : 2)) {
                unavailable("active reservation claims are incomplete");
            }
            Set<String> kinds = new HashSet<>();
            for (ClaimRow claim : claims) {
                if (claim.claimId() == null || claim.claimId() <= 0
                        || claim.windowId() == null || claim.windowId() <= 0
                        || claim.claimStoreId() == null || claim.claimStoreId() != parent.storeId()
                        || claim.claimServiceId() == null || claim.claimServiceId() != parent.serviceId()
                        || claim.originalMerchantId() == null
                        || claim.originalMerchantId() != parent.merchantId()
                        || claim.originalStoreId() == null
                        || claim.originalStoreId() != parent.storeId()
                        || claim.originalServiceId() == null
                        || claim.originalServiceId() != parent.serviceId()
                        || !validKind(claim.claimKind()) || !kinds.add(claim.claimKind())
                        || !claim.claimKind().equals(claim.originalKind())
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
        if (query.kind() != null && !validKind(query.kind())) invalid("kind is invalid");
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
