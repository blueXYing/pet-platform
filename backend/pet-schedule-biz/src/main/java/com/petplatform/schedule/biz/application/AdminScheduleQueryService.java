package com.petplatform.schedule.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminAuthorization;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminReservationRow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminScheduleWindow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindow;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminStaffWindowListQuery;
import com.petplatform.schedule.api.query.AdminScheduleQueryApi.AdminWindowListQuery;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.CollectionCheck;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Decision;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.Phase;
import com.petplatform.schedule.biz.application.AdminGovernanceAuthorizationPort.ScheduleGovernanceAuthorizationPort;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleAdminViewStore;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminStaffWindowRow;
import com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminWindowRow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform schedule view read model for the single operator (contract 64, PRD 6.2.5). Read-
 * only: the authorization port re-proves session/action/scope inside this kernel before any
 * row is read (collection entry check; no single-resource route exists). Vocabularies are the
 * storage/SCH-004 ones (window OPEN/CLOSED/SOLD_OUT, staff AVAILABLE/CLOSED, reservation
 * TEMP_LOCKED/CONFIRMED/RELEASED/EXPIRED); occupancy reuses the 53号 effective-claim notion.
 * No write path exists here — schedule writes are the merchant 53号 face; dataScope ALL is the
 * only supported mode (no uniform city attribution on schedule rows, CCR-PERM-001 §3).
 */
public final class AdminScheduleQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RANGE_DAYS = 92;
    private static final Set<String> WINDOW_KINDS = Set.of("GENERAL", "PICKUP", "RETURN");
    private static final Set<String> WINDOW_STATUSES = Set.of("OPEN", "CLOSED", "SOLD_OUT");
    private static final Set<String> STAFF_WINDOW_STATUSES = Set.of("AVAILABLE", "CLOSED");
    private static final Set<String> RESERVATION_STATUSES =
            Set.of("TEMP_LOCKED", "CONFIRMED", "RELEASED", "EXPIRED");
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();

    private final ScheduleAdminViewStore store;
    private final ScheduleGovernanceAuthorizationPort authorization;
    private final java.time.Clock clock;

    public AdminScheduleQueryService(
            ScheduleAdminViewStore store, ScheduleGovernanceAuthorizationPort authorization) {
        this(store, authorization, java.time.Clock.systemUTC());
    }

    public AdminScheduleQueryService(
            ScheduleAdminViewStore store,
            ScheduleGovernanceAuthorizationPort authorization,
            java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public PageResult<AdminScheduleWindow> listWindows(AdminWindowListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context, "schedule.read", "SCHEDULE_WINDOW_READ");
        Long merchantId = optionalId(query.merchantId(), "merchantId");
        Long storeId = optionalId(query.storeId(), "storeId");
        Long serviceId = optionalId(query.serviceId(), "serviceId");
        String windowKind = vocabulary(query.windowKind(), WINDOW_KINDS, "windowKind");
        String status = vocabulary(query.status(), WINDOW_STATUSES, "status");
        java.time.LocalDateTime[] range = range(query.startFrom(), query.startTo());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countWindows(
                merchantId, storeId, serviceId, windowKind, status, range[0], range[1]));
        if (total == 0) {
            return new PageResult<>(List.of(), total, page, pageSize);
        }
        LocalDateTime now = utcNow();
        List<AdminScheduleWindow> items = store.read(mapper -> mapper.selectWindowPage(
                        merchantId, storeId, serviceId, windowKind, status, range[0], range[1],
                        now, pageSize, (long) (page - 1) * pageSize)).stream()
                .map(AdminScheduleQueryService::window)
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public PageResult<AdminStaffWindow> listStaffWindows(AdminStaffWindowListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context, "schedule.read", "SCHEDULE_STAFF_WINDOW_READ");
        Long storeId = optionalId(query.storeId(), "storeId");
        Long staffId = optionalId(query.staffId(), "staffId");
        String status = vocabulary(query.status(), STAFF_WINDOW_STATUSES, "status");
        java.time.LocalDateTime[] range = range(query.startFrom(), query.startTo());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countStaffWindows(
                storeId, staffId, status, range[0], range[1]));
        if (total == 0) {
            return new PageResult<>(List.of(), total, page, pageSize);
        }
        List<AdminStaffWindow> items = store.read(mapper -> mapper.selectStaffWindowPage(
                        storeId, staffId, status, range[0], range[1],
                        pageSize, (long) (page - 1) * pageSize)).stream()
                .map(AdminScheduleQueryService::staffWindow)
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    public PageResult<AdminReservationRow> listReservations(AdminReservationListQuery query) {
        Objects.requireNonNull(query, "query is required");
        QueryContext context = operator(query.context());
        AdminAuthorization auth = reference(query.authorization());
        authorizeCollection(auth, context, "schedule.read", "SCHEDULE_RESERVATION_READ");
        Long merchantId = optionalId(query.merchantId(), "merchantId");
        Long storeId = optionalId(query.storeId(), "storeId");
        Long serviceId = optionalId(query.serviceId(), "serviceId");
        Long orderId = optionalId(query.orderId(), "orderId");
        String status = vocabulary(query.status(), RESERVATION_STATUSES, "status");
        java.time.LocalDateTime[] range = range(query.startFrom(), query.startTo());
        int page = page(query.page());
        int pageSize = pageSize(query.pageSize());
        long total = store.read(mapper -> mapper.countReservations(
                merchantId, storeId, serviceId, orderId, status, range[0], range[1]));
        if (total == 0) {
            return new PageResult<>(List.of(), total, page, pageSize);
        }
        List<AdminReservationRow> items = store.read(mapper -> mapper.selectReservationPage(
                        merchantId, storeId, serviceId, orderId, status, range[0], range[1],
                        pageSize, (long) (page - 1) * pageSize)).stream()
                .map(AdminScheduleQueryService::reservation)
                .toList();
        return new PageResult<>(items, total, page, pageSize);
    }

    // ------------------------------------------------------------------ authorization

    private void authorizeCollection(
            AdminAuthorization auth, QueryContext context, String action, String purpose) {
        Decision decision = authorization.checkCollection(new CollectionCheck(
                auth.sessionId(), auth.sessionGeneration(), context.operatorId(), action,
                purpose, Phase.READ_RESULT));
        if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                || decision.authzVersion().isBlank()) {
            throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "授权决断不可用");
        }
        if (!decision.allowed()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "无权执行该操作");
        }
    }

    // ------------------------------------------------------------------ projections

    private static AdminScheduleWindow window(AdminWindowRow row) {
        return new AdminScheduleWindow(
                IDS.toApi(row.getId()), IDS.toApi(row.getMerchantId()), IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getServiceId()), row.getWindowKind(),
                instant(row.getStartAt()), instant(row.getEndAt()),
                row.getConfiguredCapacity() == null ? 0 : row.getConfiguredCapacity(),
                row.getStatus(),
                versionOf(row.getVersion()),
                instant(row.getUpdatedAt()),
                row.getConfirmedCount() == null ? 0 : row.getConfirmedCount(),
                row.getTempLockedCount() == null ? 0 : row.getTempLockedCount());
    }

    private static AdminStaffWindow staffWindow(AdminStaffWindowRow row) {
        return new AdminStaffWindow(
                IDS.toApi(row.getId()), IDS.toApi(row.getStoreId()), IDS.toApi(row.getStaffId()),
                instant(row.getStartAt()), instant(row.getEndAt()), row.getStatus(),
                versionOf(row.getVersion()),
                instant(row.getUpdatedAt()));
    }

    private static AdminReservationRow reservation(
            com.petplatform.schedule.biz.infrastructure.persistence.entity.ScheduleAdminViewRows.AdminReservationRow row) {
        return new AdminReservationRow(
                IDS.toApi(row.getId()), IDS.toApi(row.getOrderId()), IDS.toApi(row.getStoreId()),
                IDS.toApi(row.getServiceId()), row.getFulfillmentType(),
                instant(row.getStartAt()), instant(row.getEndAt()),
                instant(row.getPickupStartAt()), instant(row.getReturnStartAt()),
                row.getStatus(), instant(row.getLockExpireAt()),
                row.getCapacitySnapshot() == null ? 0 : row.getCapacitySnapshot(),
                row.getQualifiedStaffCountSnapshot() == null ? 0 : row.getQualifiedStaffCountSnapshot(),
                instant(row.getCreatedAt()));
    }

    // ------------------------------------------------------------------ validation helpers

    private static QueryContext operator(QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (context.operatorType() != OperatorType.PLATFORM_OPERATOR
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "平台运营身份必需");
        }
        return context;
    }

    private static AdminAuthorization reference(AdminAuthorization authorization) {
        if (authorization == null || authorization.sessionId() == null
                || authorization.sessionId().isBlank() || authorization.sessionGeneration() < 0) {
            throw new ApiException(CommonApiCodes.UNAUTHORIZED, "admin authorization reference is required");
        }
        return authorization;
    }

    private static String vocabulary(String value, Set<String> allowed, String field) {
        if (value == null) return null;
        if (!allowed.contains(value)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
        return value;
    }

    private java.time.LocalDateTime[] range(OffsetDateTime from, OffsetDateTime to) {
        java.time.LocalDateTime start = utc(from, "startFrom");
        java.time.LocalDateTime end = utc(to, "startTo");
        if ((start == null) != (end == null)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "time range must be paired");
        }
        if (start != null && (end.isBefore(start)
                || Duration.between(start, end).toDays() > MAX_RANGE_DAYS)) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, "time range is invalid");
        }
        return new java.time.LocalDateTime[] {start, end};
    }

    private static Long optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return IDS.fromApi(value);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static int page(int page) {
        return page < 1 ? 1 : page;
    }

    private static int pageSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static java.time.LocalDateTime utc(OffsetDateTime value, String field) {
        if (value == null) return null;
        try {
            return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException invalid) {
            throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, field + " is invalid");
        }
    }

    private static OffsetDateTime instant(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /** CAS versions start at 0 (53号 wire: "0") — not Snowflake ids, never PublicIdCodec. */
    private static String versionOf(Long version) {
        return Long.toUnsignedString(version == null ? 0L : version);
    }
}
