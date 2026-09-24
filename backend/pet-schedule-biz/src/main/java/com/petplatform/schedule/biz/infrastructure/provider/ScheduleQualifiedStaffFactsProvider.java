package com.petplatform.schedule.biz.infrastructure.provider;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleStaffFactsReadStore;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.StaffAvailabilityEntity;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.StaffCapabilityEntity;
import java.time.LocalDateTime;
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
import java.util.UUID;
import javax.sql.DataSource;

/** SCH-owned intersection of MER staff IDs, service capability and whole-window availability. */
public final class ScheduleQualifiedStaffFactsProvider implements QualifiedStaffFactsPort {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";
    private final MerchantStoreStaffFactsApi merchant;
    private final ScheduleStaffFactsReadStore store;

    public ScheduleQualifiedStaffFactsProvider(MerchantStoreStaffFactsApi merchant, DataSource source) {
        this.merchant = Objects.requireNonNull(merchant, "merchant is required");
        this.store = new ScheduleStaffFactsReadStore(source);
    }

    @Override
    public int countQualifiedAvailableStaff(
            String storeId, String serviceId, OffsetDateTime from, OffsetDateTime to) {
        long storeKey = inputId(storeId);
        long serviceKey = inputId(serviceId);
        if (from == null || to == null || !to.isAfter(from)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "staff facts interval is invalid");
        }
        StoreStaffFactsDTO facts = merchantFacts(storeId);
        Set<Long> staff = staffIds(facts, storeKey);
        LocalDateTime startUtc = utc(from);
        LocalDateTime endUtc = utc(to);
        return store.read(mapper -> {
            List<StaffCapabilityEntity> capabilities = mapper.selectServiceCapabilities(serviceKey);
            if (capabilities == null) unavailable("service capability facts are missing");
            Set<Long> qualified = new HashSet<>();
            for (StaffCapabilityEntity row : capabilities) {
                if (row == null || invalidId(row.getId()) || invalidId(row.getStaffId())
                        || row.getServiceId() == null || row.getServiceId() != serviceKey) {
                    unavailable("service capability relationship is invalid");
                }
                if (!"ENABLED".equals(row.getStatus())) {
                    unavailable("unknown service capability status");
                }
                if (staff.contains(row.getStaffId())) qualified.add(row.getStaffId());
            }
            if (qualified.isEmpty()) return 0;
            List<StaffAvailabilityEntity> rows = mapper.selectCandidateStaffAvailability(
                    storeKey, new ArrayList<>(qualified));
            if (rows == null) unavailable("staff availability facts are missing");
            Map<Long, List<Interval>> byStaff = new HashMap<>();
            for (StaffAvailabilityEntity row : rows) {
                if (row == null || invalidId(row.getId())
                        || row.getStoreId() == null || row.getStoreId() != storeKey
                        || invalidId(row.getStaffId()) || !qualified.contains(row.getStaffId())
                        || row.getStartAt() == null || row.getEndAt() == null
                        || !row.getEndAt().isAfter(row.getStartAt())) {
                    unavailable("staff availability interval is invalid");
                }
                if (!row.getStartAt().isBefore(endUtc) || !row.getEndAt().isAfter(startUtc)) {
                    continue; // Legal rows outside this query interval have no bearing on its result.
                }
                if (!"AVAILABLE".equals(row.getStatus()) && !"CLOSED".equals(row.getStatus())) {
                    unavailable("unknown staff availability status");
                }
                if ("AVAILABLE".equals(row.getStatus())) {
                    byStaff.computeIfAbsent(row.getStaffId(), ignored -> new ArrayList<>())
                            .add(new Interval(row.getStartAt(), row.getEndAt()));
                }
            }
            int count = 0;
            for (long id : qualified) {
                if (covers(byStaff.get(id), startUtc, endUtc)) count++;
            }
            return count;
        });
    }

    private StoreStaffFactsDTO merchantFacts(String storeId) {
        try {
            // This context links internal reads only. It is never a user or merchant credential.
            StoreStaffFactsDTO facts = merchant.listActiveStoreStaffFacts(new StoreStaffFactsQuery(
                    storeId, new QueryContext(UUID.randomUUID().toString(),
                            OperatorType.SYSTEM, "schedule-capacity")));
            if (facts == null) unavailable("merchant staff facts are missing");
            return facts;
        } catch (ApiException known) {
            if (CommonApiCodes.NOT_FOUND.equals(known.code())) {
                throw new ApiException(SERVICE_NOT_FOUND, "service resource not found");
            }
            if (CommonApiCodes.DEPENDENCY_UNAVAILABLE.equals(known.code())) throw known;
            unavailable("merchant staff facts are unavailable");
            throw known;
        } catch (RuntimeException failure) {
            unavailable("merchant staff facts are unavailable");
            throw failure;
        }
    }

    private static Set<Long> staffIds(StoreStaffFactsDTO facts, long storeId) {
        if (facts.storeId() == null || !facts.storeId().equals(IDS.toApi(storeId))
                || facts.activeStaffIds() == null) unavailable("merchant staff facts are invalid");
        Set<Long> result = new HashSet<>();
        for (String id : facts.activeStaffIds()) {
            try {
                long value = IDS.fromApi(id);
                if (value <= 0) unavailable("merchant staff id is invalid");
                result.add(value);
            } catch (IllegalArgumentException malformed) {
                unavailable("merchant staff id is invalid");
            }
        }
        return result;
    }

    private static boolean covers(List<Interval> intervals, LocalDateTime from, LocalDateTime to) {
        if (intervals == null || intervals.isEmpty()) return false;
        intervals.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        LocalDateTime cursor = from;
        for (Interval interval : intervals) {
            if (interval.start().isAfter(cursor)) return false;
            if (interval.end().isAfter(cursor)) cursor = interval.end();
            if (!cursor.isBefore(to)) return true;
        }
        return false;
    }

    private static long inputId(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id <= 0) throw new IllegalArgumentException("nonpositive");
            return id;
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "staff facts target id is invalid");
        }
    }

    private static boolean invalidId(Long id) {
        return id == null || id <= 0;
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {}
}
