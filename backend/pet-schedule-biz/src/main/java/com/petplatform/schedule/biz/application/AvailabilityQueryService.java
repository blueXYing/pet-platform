package com.petplatform.schedule.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.schedule.api.dto.AvailabilityPageDTO;
import com.petplatform.schedule.api.dto.AvailabilityWindowDTO;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleReadStore;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAvailabilityWindowEntity;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleReadMapper;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceQueryApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CCR-W2-API-001 schedule domain (SCH-001, ruling 2026-09-23): minute-level availability query.
 * Visibility first - the four-condition conjunction consumed through the service-domain
 * checkBookable (its own repeatable-read snapshot) answers 404 SERVICE_NOT_FOUND for missing,
 * ineligible or store-mismatched services; facts failures stay 503. Capacity follows SSOT 12.2
 * (min of configured and qualified available staff): the staff facts provider is MANDATORY.
 * The SCH-002 real assembly supplies one, while the defensive absent-provider path remains
 * fail-closed instead of inventing placeholder capacity.
 */
public final class AvailabilityQueryService {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    /** Registry-12 §4 not-found code (SCH-D9/SVC-D1b: 404, indistinguishable, anti-probing). */
    private static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";
    /** Supplement 23 §2 initial ruling: one business zone for the whole platform. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** SCH-D2: at most 31 calendar days per query. */
    private static final int MAX_SPAN_DAYS = 31;

    private final ScheduleReadStore store;
    private final ServiceQueryApi serviceFacts;
    private final QualifiedStaffFactsPort staffFacts; // nullable by design: absent fails closed
    private final Clock clock;

    public AvailabilityQueryService(
            ScheduleReadStore store,
            ServiceQueryApi serviceFacts,
            QualifiedStaffFactsPort staffFacts,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.serviceFacts = Objects.requireNonNull(serviceFacts, "serviceFacts is required");
        this.staffFacts = staffFacts;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public AvailabilityPageDTO page(AvailabilityQuery query) {
        validate(query);
        // Visibility first (SCH-D6 ordering): a confirmed invisible service is 404 even when the
        // staff facts provider is absent; checkBookable already throws SERVICE_NOT_FOUND for a
        // missing row or a store-id mismatch and DEPENDENCY_UNAVAILABLE for unknown facts.
        ServiceBookabilityDTO bookability =
                serviceFacts.checkBookable(
                        new ServiceBookabilityQuery(
                                query.serviceId(), query.storeId(), query.context()));
        if (!bookability.bookable()) notFound();
        QualifiedStaffFactsPort staff = staffFacts;
        if (staff == null) unavailable("qualified staff facts provider is unavailable");
        OffsetDateTime from = query.startDate().atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime to =
                query.endDate().plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        Instant now = clock.instant();
        List<AvailabilityWindowDTO> items =
                store.read(
                        mapper -> {
                            // CCR-SCH-SLOT-002: the store's bookable range (lead/maxDays,
                            // defaults 60/7) hides every slot starting before now+lead or after
                            // now+maxDays — out-of-range items are not returned at all.
                            Map<String, Object> bookingRow =
                                    mapper.selectBookingWindow(targetId(query.storeId()));
                            BookingWindowRule.Range range = BookingWindowRule.resolve(
                                    bookingRow == null ? null
                                            : ScheduleSqlRows.nullableNumber(bookingRow,
                                                    "lead_minutes"),
                                    bookingRow == null ? null
                                            : ScheduleSqlRows.nullableNumber(bookingRow,
                                                    "max_days"));
                            // CCR-SCH-SLOT-001: candidate slots are the store's whole open slot
                            // timeline; this service's bookability is decided per slot by the
                            // qualified-staff coverage below, not by a window ownership filter.
                            List<ScheduleAvailabilityWindowEntity> rows =
                                    mapper.selectOpenWindows(
                                            targetId(query.storeId()),
                                            utc(from),
                                            utc(to));
                            // CCR-SCH-SLOT-003: every fetched OPEN/SOLD_OUT window holds its
                            // open slot (even an out-of-range one), so a template segment
                            // intersecting it is skipped — the merged item list never presents
                            // the same time twice.
                            List<WeekTemplateRule.Expanded> realIntervals =
                                    new ArrayList<>(rows.size());
                            for (ScheduleAvailabilityWindowEntity row : rows) {
                                realIntervals.add(new WeekTemplateRule.Expanded(
                                        row.getStartAt().atOffset(ZoneOffset.UTC),
                                        row.getEndAt().atOffset(ZoneOffset.UTC),
                                        row.getConfiguredCapacity()));
                            }
                            List<AvailabilityWindowDTO> result = new ArrayList<>(rows.size());
                            for (ScheduleAvailabilityWindowEntity row : rows) {
                                if (!notFinished(row, now)) continue; // ended windows never return
                                if (!BookingWindowRule.within(
                                        row.getStartAt().atOffset(ZoneOffset.UTC).toInstant(),
                                        now, range)) {
                                    continue; // outside the bookable range stays hidden
                                }
                                result.add(aggregate(row, query, staff, mapper, now));
                            }
                            // CCR-SCH-SLOT-003 read-time expansion: items = real windows ∪
                            // template expansion of the future weekdays, bounded by
                            // now+maxDays; a virtual segment answers effectiveCapacity =
                            // segment capacity, occupied 0 (it materializes at hold time).
                            Map<String, Object> templateRow =
                                    mapper.selectWeekTemplate(targetId(query.storeId()));
                            if (templateRow != null) {
                                String days = ScheduleSqlRows.json(templateRow, "days");
                                if (days == null) unavailable("week template facts are invalid");
                                for (WeekTemplateRule.Expanded virtual : WeekTemplateRule.expand(
                                        WeekTemplateRule.parse(days), query.startDate(),
                                        query.endDate(), now, range, realIntervals,
                                        BUSINESS_ZONE)) {
                                    result.add(new AvailabilityWindowDTO(
                                            query.storeId(), query.serviceId(),
                                            virtual.start(), virtual.end(),
                                            virtual.capacity(), virtual.capacity(),
                                            virtual.capacity(), 0, virtual.capacity(),
                                            virtual.start().toInstant().isAfter(now)));
                                }
                                result.sort(java.util.Comparator.comparing(
                                        AvailabilityWindowDTO::start)
                                        .thenComparing(AvailabilityWindowDTO::end));
                            }
                            return List.copyOf(result);
                        });
        return new AvailabilityPageDTO(
                query.storeId(), query.serviceId(), query.startDate(), query.endDate(), items);
    }

    private AvailabilityWindowDTO aggregate(
            ScheduleAvailabilityWindowEntity row,
            AvailabilityQuery query,
            QualifiedStaffFactsPort staff,
            ScheduleReadMapper mapper,
            Instant now) {
        if (row.getStoreId() == null
                || row.getId() == null
                || row.getStartAt() == null
                || row.getEndAt() == null
                || row.getConfiguredCapacity() == null
                || row.getConfiguredCapacity() < 1
                || !row.getEndAt().isAfter(row.getStartAt())) {
            unavailable("schedule window facts are invalid");
        }
        OffsetDateTime start = row.getStartAt().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = row.getEndAt().atOffset(ZoneOffset.UTC);
        int staffCount;
        try {
            staffCount =
                    staff.countQualifiedAvailableStaff(
                            query.storeId(), query.serviceId(), start, end);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailableReturn("qualified staff facts provider is unavailable");
        }
        if (staffCount < 0) unavailable("qualified staff facts are invalid");
        // Single pool (CCR-SCH-SLOT-001): occupancy is the effective-claim count on the slot
        // across every service of the store — the same notion the SOLD_OUT deriver uses.
        long occupied = mapper.countActiveClaimsOnWindow(row.getId());
        int effective = Math.min(row.getConfiguredCapacity(), staffCount);
        int remaining = (int) Math.max(effective - occupied, 0);
        boolean notYetStarted = start.toInstant().isAfter(now);
        return new AvailabilityWindowDTO(
                query.storeId(),
                query.serviceId(),
                start,
                end,
                row.getConfiguredCapacity(),
                staffCount,
                effective,
                (int) occupied,
                remaining,
                notYetStarted && remaining > 0);
    }

    /** SCH-D2: a window already finished (end <= now) is filtered out entirely. */
    private static boolean notFinished(ScheduleAvailabilityWindowEntity row, Instant now) {
        if (row.getEndAt() == null) return true; // corrupt facts surface later as 503
        return row.getEndAt().atOffset(ZoneOffset.UTC).toInstant().isAfter(now);
    }

    private static void validate(AvailabilityQuery query) {
        if (query == null) invalid("query is required");
        if (query.serviceId() == null || query.serviceId().isBlank()) invalid("serviceId is required");
        if (query.storeId() == null || query.storeId().isBlank()) invalid("storeId is required");
        LocalDate start = query.startDate();
        LocalDate end = query.endDate();
        if (start == null) invalid("startDate is required");
        if (end == null) invalid("endDate is required");
        if (end.isBefore(start)) invalid("endDate is before startDate");
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SPAN_DAYS) invalid("date span exceeds 31 days");
        targetId(query.serviceId());
        targetId(query.storeId());
    }

    private static long targetId(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) invalid("id is invalid");
            return id;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException malformed) {
            invalid("id is invalid");
            throw malformed;
        }
    }

    private static java.time.LocalDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(SERVICE_NOT_FOUND, "service resource not found");
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    /** Same failure with an explicit unreachable return for the catch-and-wrap call sites. */
    private static RuntimeException unavailableReturn(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
