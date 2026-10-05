package com.petplatform.schedule.biz.apiimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.command.ScheduleMerchantCommandApi;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ClaimFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.ReservationFact;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.StoreScheduleFacts;
import com.petplatform.schedule.api.dto.ScheduleProtectionTypes.WindowFact;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchCloseResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BlockedWindow;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
import com.petplatform.schedule.api.error.ScheduleWriteApiCodes;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.application.AvailabilityQueryService;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.application.ScheduleCanonicalParams;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleSqlRows;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore;
import com.petplatform.schedule.biz.infrastructure.persistence.mapper.ScheduleWriteMapper;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Merchant schedule write commands (SCH-004; PRD29, joint review receipt, 34号 §2~§4). Every
 * command: 23号 admission binding, owner admission re-verification (also before replays), one
 * top-level READ_COMMITTED transaction on the shared DataSource acquiring the per-store
 * ScheduleCapacityGuardApi, authoritative current reads, guarded writes, append-only
 * schedule_write_action audit and the first success receipt, committed together. Occupied
 * windows (TEMP_LOCKED/CONFIRMED claims) cannot close, shrink or move (SCHW-D4);
 * staff-availability/capability reductions must pass the ORDER protected-assignment check and
 * the whole-store feasibility re-proof or fail closed (SCHW-D6/D7, 36号).
 */
public final class ScheduleMerchantCommandService implements ScheduleMerchantCommandApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Set<String> WINDOW_KINDS = Set.of("GENERAL", "PICKUP", "RETURN");
    /** Technical column guard (06号 INT); no approved business capacity maximum exists. */
    private static final int MAX_CONFIGURED_CAPACITY = 1_000_000_000;
    /** 2026-10-05 ruling (blocker 3): one batch command processes at most 200 entries. */
    private static final int MAX_BATCH_ENTRIES = 200;

    static {
        JSON.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private final ScheduleWriteStore store;
    private final ScheduleAdmissionGate admissions;
    private final ScheduleCapacityGuardApi guard;
    private final ScheduleProtectionFactsApi facts;
    private final MerchantCurrentStaffFactsApi merchant;
    private final OrderProtectionFactsApi orders;
    private final ServiceQueryApi serviceFacts;
    private final ScheduleCapacityProofApiImpl proof;
    private final Clock clock;

    public ScheduleMerchantCommandService(
            ScheduleWriteStore store,
            ScheduleAdmissionGate admissions,
            ScheduleCapacityGuardApi guard,
            ScheduleProtectionFactsApi facts,
            MerchantCurrentStaffFactsApi merchant,
            OrderProtectionFactsApi orders,
            ServiceQueryApi serviceFacts,
            ScheduleCapacityProofApiImpl proof,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.admissions = Objects.requireNonNull(admissions, "admissions is required");
        this.guard = Objects.requireNonNull(guard, "guard is required");
        this.facts = Objects.requireNonNull(facts, "facts is required");
        this.merchant = Objects.requireNonNull(merchant, "merchant is required");
        this.orders = Objects.requireNonNull(orders, "orders is required");
        this.serviceFacts = Objects.requireNonNull(serviceFacts, "serviceFacts is required");
        this.proof = Objects.requireNonNull(proof, "proof is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    private record Executed<T>(boolean fresh, T result) {}

    // ------------------------------------------------------------------ service windows

    @Override
    public boolean createWindowOutcome(CreateWindowCommand c, WindowResult[] out) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.serviceId(), "serviceId");
        interval(c.startAt(), c.endAt());
        if (c.windowKind() == null || !WINDOW_KINDS.contains(c.windowKind())) {
            invalid("windowKind is invalid");
        }
        if (c.configuredCapacity() < 1 || c.configuredCapacity() > MAX_CONFIGURED_CAPACITY) {
            invalid("configuredCapacity is invalid");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("serviceId", c.serviceId());
        p.put("windowKind", c.windowKind());
        p.put("startAt", c.startAt().toString());
        p.put("endAt", c.endAt().toString());
        p.put("configuredCapacity", Integer.toString(c.configuredCapacity()));
        Executed<WindowResult> executed = command(
                "merchant.schedule.window.create",
                ctx,
                "STORE:" + c.merchantId() + ":" + c.storeId(),
                p,
                WindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    requireServiceIdentity(ctx, c.merchantId(), c.storeId(), c.serviceId(),
                            c.windowKind());
                    StoreScheduleFacts snapshot = facts.readStore(c.storeId(), query(ctx));
                    ensureNoOpenOverlap(snapshot, c.serviceId(), c.windowKind(),
                            c.startAt(), c.endAt(), null);
                    long windowId = store.nextId();
                    one(m.insertWindow(ScheduleSqlRows.values(
                            "id", windowId, "merchantId", id(c.merchantId()),
                            "storeId", id(c.storeId()), "serviceId", id(c.serviceId()),
                            "windowKind", c.windowKind(),
                            "startAt", utc(c.startAt()), "endAt", utc(c.endAt()),
                            "configuredCapacity", c.configuredCapacity(), "now", now())));
                    WindowResult result = rowToWindow(m.selectWindowByIdForUpdate(windowId));
                    audit(m, ctx, "WINDOW", windowId, id(c.merchantId()), id(c.storeId()),
                            "WINDOW_CREATE", null, null, 0L);
                    return result;
                });
        out[0] = executed.result();
        return executed.fresh();
    }

    @Override
    public WindowResult updateWindow(UpdateWindowCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        interval(c.startAt(), c.endAt());
        if (c.configuredCapacity() != null
                && (c.configuredCapacity() < 1 || c.configuredCapacity() > MAX_CONFIGURED_CAPACITY)) {
            invalid("configuredCapacity is invalid");
        }
        String reason = optionalReason(c.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("windowId", c.windowId());
        p.put("startAt", c.startAt().toString());
        p.put("endAt", c.endAt().toString());
        p.put("configuredCapacity", c.configuredCapacity() == null
                ? null : Integer.toString(c.configuredCapacity()));
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("reason", reason);
        Executed<WindowResult> executed = command(
                "merchant.schedule.window.update",
                ctx,
                "WINDOW:" + c.windowId(),
                p,
                WindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedWindow(m, c.windowId(), c.merchantId(), c.storeId());
                    String status = ScheduleSqlRows.text(row, "status");
                    // SOLD_OUT is an open-derived state: editing stays possible and is still
                    // governed by the occupied-window rules below (Contract53 §3).
                    if (!"OPEN".equals(status) && !"SOLD_OUT".equals(status)) stateNotAllowed();
                    requireCurrentVersion(row, c.expectedVersion());
                    StoreScheduleFacts snapshot = facts.readStore(c.storeId(), query(ctx));
                    long target = id(c.windowId());
                    boolean occupied = !activeClaimsOn(snapshot, target).isEmpty();
                    int currentCapacity = (int) ScheduleSqlRows.number(row, "configured_capacity");
                    int nextCapacity = c.configuredCapacity() == null
                            ? currentCapacity : c.configuredCapacity();
                    boolean timesChanged =
                            !utc(c.startAt()).equals(ScheduleSqlRows.dateTime(row, "start_at"))
                                    || !utc(c.endAt()).equals(ScheduleSqlRows.dateTime(row, "end_at"));
                    if (occupied && (timesChanged || nextCapacity < currentCapacity)) {
                        // SCHW-D4: occupied windows cannot move or lose capacity; only increases pass.
                        stateNotAllowed();
                    }
                    if (timesChanged) {
                        ensureNoOpenOverlap(snapshot, ScheduleSqlRows.id(row, "service_id"),
                                ScheduleSqlRows.text(row, "window_kind"),
                                c.startAt(), c.endAt(), c.windowId());
                    }
                    one(m.updateWindow(target, utc(c.startAt()), utc(c.endAt()), nextCapacity,
                            c.expectedVersion(), now()));
                    if (nextCapacity != currentCapacity) {
                        // Raising the capacity of a sold-out window re-derives its state in this
                        // transaction (2026-10-05 ruling: the merchant can never force the
                        // window bookable; the system recomputes from occupancy).
                        WindowSoldOutDeriver.rederive(c.storeId(), List.of(target), query(ctx),
                                facts, m::setWindowDerivedStatus, now());
                    }
                    WindowResult result = rowToWindow(m.selectWindowByIdForUpdate(target));
                    audit(m, ctx, "WINDOW", target, id(c.merchantId()), id(c.storeId()),
                            "WINDOW_UPDATE", reason, c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    @Override
    public WindowResult closeWindow(WindowCloseCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        String reason = requiredReason(c.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("windowId", c.windowId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("reason", reason);
        Executed<WindowResult> executed = command(
                "merchant.schedule.window.close",
                ctx,
                "WINDOW:" + c.windowId(),
                p,
                WindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedWindow(m, c.windowId(), c.merchantId(), c.storeId());
                    if (!"OPEN".equals(ScheduleSqlRows.text(row, "status"))) stateNotAllowed();
                    requireCurrentVersion(row, c.expectedVersion());
                    StoreScheduleFacts snapshot = facts.readStore(c.storeId(), query(ctx));
                    if (!activeClaimsOn(snapshot, id(c.windowId())).isEmpty()) stateNotAllowed();
                    one(m.closeWindow(id(c.windowId()), c.expectedVersion(), now()));
                    WindowResult result =
                            rowToWindow(m.selectWindowByIdForUpdate(id(c.windowId())));
                    audit(m, ctx, "WINDOW", id(c.windowId()), id(c.merchantId()), id(c.storeId()),
                            "WINDOW_CLOSE", reason, c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    @Override
    public WindowResult openWindow(WindowOpenCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("windowId", c.windowId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        Executed<WindowResult> executed = command(
                "merchant.schedule.window.open",
                ctx,
                "WINDOW:" + c.windowId(),
                p,
                WindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedWindow(m, c.windowId(), c.merchantId(), c.storeId());
                    if (!"CLOSED".equals(ScheduleSqlRows.text(row, "status"))) stateNotAllowed();
                    requireCurrentVersion(row, c.expectedVersion());
                    StoreScheduleFacts snapshot = facts.readStore(c.storeId(), query(ctx));
                    ensureNoOpenOverlap(snapshot, ScheduleSqlRows.id(row, "service_id"),
                            ScheduleSqlRows.text(row, "window_kind"),
                            ScheduleSqlRows.at(row, "start_at"),
                            ScheduleSqlRows.at(row, "end_at"), c.windowId());
                    one(m.openWindow(id(c.windowId()), c.expectedVersion(), now()));
                    // Reopening re-derives the state from occupancy (2026-10-05 ruling): a
                    // reopened window whose claims already fill it returns as SOLD_OUT, never
                    // forced bookable beyond capacity.
                    WindowSoldOutDeriver.rederive(c.storeId(), List.of(id(c.windowId())),
                            query(ctx), facts, m::setWindowDerivedStatus, now());
                    WindowResult result =
                            rowToWindow(m.selectWindowByIdForUpdate(id(c.windowId())));
                    audit(m, ctx, "WINDOW", id(c.windowId()), id(c.merchantId()), id(c.storeId()),
                            "WINDOW_OPEN", null, c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    @Override
    public BatchCloseResult batchClose(BatchCloseCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        if (c.fromDate() == null || c.toDate() == null || c.toDate().isBefore(c.fromDate())) {
            invalid("batch close date range is invalid");
        }
        String reason = requiredReason(c.reason());
        OffsetDateTime from =
                c.fromDate().atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime to = c.toDate().plusDays(1)
                .atStartOfDay(AvailabilityQueryService.BUSINESS_ZONE).toOffsetDateTime();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("fromDate", c.fromDate().toString());
        p.put("toDate", c.toDate().toString());
        p.put("reason", reason);
        Executed<BatchCloseResult> executed = command(
                "merchant.schedule.window.batchClose",
                ctx,
                "STORE:" + c.merchantId() + ":" + c.storeId(),
                p,
                BatchCloseResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    StoreScheduleFacts snapshot = facts.readStore(c.storeId(), query(ctx));
                    List<WindowFact> targets = new ArrayList<>();
                    for (WindowFact window : snapshot.windows()) {
                        // SOLD_OUT windows are open-derived and stay batch targets: occupied
                        // ones are reported as blocked, never silently skipped (SCHW-D5).
                        if (!"OPEN".equals(window.status())
                                && !"SOLD_OUT".equals(window.status())) continue;
                        // A window intersecting the calendar-day range is a batch target; partial
                        // closes are allowed, occupied targets are reported (SCHW-D5).
                        boolean intersects = window.startAt().isBefore(to)
                                && window.endAt().isAfter(from);
                        if (intersects) targets.add(window);
                    }
                    // 2026-10-05 ruling (blocker 3): one batch command processes at most 200
                    // window entries (the closed plus blocked candidates of this range). A
                    // larger range is rejected whole as a parameter error before any window
                    // closes, so the overflow is never half-applied.
                    if (targets.size() > MAX_BATCH_ENTRIES) {
                        invalid("batch close exceeds " + MAX_BATCH_ENTRIES
                                + " window entries per command");
                    }
                    List<WindowResult> closed = new ArrayList<>();
                    List<BlockedWindow> blocked = new ArrayList<>();
                    for (WindowFact window : targets) {
                        long target = id(window.windowId());
                        if (!activeClaimsOn(snapshot, target).isEmpty()) {
                            blocked.add(new BlockedWindow(
                                    toWindow(window), "SCHEDULE_WINDOW_STATE_NOT_ALLOWED"));
                            continue;
                        }
                        one(m.closeWindow(target, Long.parseLong(window.version()), now()));
                        closed.add(toWindow(window));
                        audit(m, ctx, "WINDOW", target, id(c.merchantId()), id(c.storeId()),
                                "WINDOW_BATCH_CLOSE", reason, Long.parseLong(window.version()),
                                Long.parseLong(window.version()) + 1);
                    }
                    return new BatchCloseResult(c.storeId(), closed, blocked);
                });
        return executed.result();
    }

    // ------------------------------------------------------------------ staff windows

    @Override
    public boolean createStaffWindowOutcome(CreateStaffWindowCommand c, StaffWindowResult[] out) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.staffId(), "staffId");
        interval(c.startAt(), c.endAt());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("staffId", c.staffId());
        p.put("startAt", c.startAt().toString());
        p.put("endAt", c.endAt().toString());
        Executed<StaffWindowResult> executed = command(
                "merchant.schedule.staffWindow.create",
                ctx,
                "STAFF:" + c.merchantId() + ":" + c.storeId() + ":" + c.staffId(),
                p,
                StaffWindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    requireActiveStaff(ctx, c.storeId(), c.staffId(), c.merchantId());
                    ensureNoAvailableOverlap(m, c.storeId(), c.staffId(),
                            c.startAt(), c.endAt(), null);
                    long windowId = store.nextId();
                    one(m.insertStaffWindow(ScheduleSqlRows.values(
                            "id", windowId, "storeId", id(c.storeId()),
                            "staffId", id(c.staffId()),
                            "startAt", utc(c.startAt()), "endAt", utc(c.endAt()),
                            "now", now())));
                    StaffWindowResult result =
                            rowToStaffWindow(m.selectStaffWindowByIdForUpdate(windowId),
                                    c.merchantId());
                    audit(m, ctx, "STAFF_WINDOW", windowId, id(c.merchantId()), id(c.storeId()),
                            "STAFF_WINDOW_CREATE", null, null, 0L);
                    return result;
                });
        out[0] = executed.result();
        return executed.fresh();
    }

    @Override
    public StaffWindowResult updateStaffWindow(UpdateStaffWindowCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.staffId(), "staffId");
        interval(c.startAt(), c.endAt());
        String reason = optionalReason(c.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("staffId", c.staffId());
        p.put("windowId", c.windowId());
        p.put("startAt", c.startAt().toString());
        p.put("endAt", c.endAt().toString());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("reason", reason);
        Executed<StaffWindowResult> executed = command(
                "merchant.schedule.staffWindow.update",
                ctx,
                "STAFF_WINDOW:" + c.windowId(),
                p,
                StaffWindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedStaffWindow(m, c.windowId(), c.storeId(), c.staffId());
                    requireCurrentVersion(row, c.expectedVersion());
                    boolean reducing = isReduction(row, c.startAt(), c.endAt());
                    if (reducing) {
                        if (reason == null) {
                            invalid("reason is required for availability reductions");
                        }
                        requireNoProtectedAssignment(c.storeId(), c.staffId(), ctx);
                    }
                    ensureNoAvailableOverlap(m, c.storeId(), c.staffId(),
                            c.startAt(), c.endAt(), c.windowId());
                    one(m.updateStaffWindow(id(c.windowId()), utc(c.startAt()), utc(c.endAt()),
                            c.expectedVersion(), now()));
                    if (reducing) proof.proveReductionSafe(c.storeId(), query(ctx));
                    StaffWindowResult result = rowToStaffWindow(
                            m.selectStaffWindowByIdForUpdate(id(c.windowId())), c.merchantId());
                    audit(m, ctx, "STAFF_WINDOW", id(c.windowId()), id(c.merchantId()),
                            id(c.storeId()), "STAFF_WINDOW_UPDATE", reason,
                            c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    @Override
    public StaffWindowResult closeStaffWindow(StaffWindowCloseCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.staffId(), "staffId");
        String reason = requiredReason(c.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("staffId", c.staffId());
        p.put("windowId", c.windowId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        p.put("reason", reason);
        Executed<StaffWindowResult> executed = command(
                "merchant.schedule.staffWindow.close",
                ctx,
                "STAFF_WINDOW:" + c.windowId(),
                p,
                StaffWindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedStaffWindow(m, c.windowId(), c.storeId(), c.staffId());
                    requireCurrentVersion(row, c.expectedVersion());
                    requireNoProtectedAssignment(c.storeId(), c.staffId(), ctx);
                    one(m.closeStaffWindow(id(c.windowId()), c.expectedVersion(), now()));
                    proof.proveReductionSafe(c.storeId(), query(ctx));
                    StaffWindowResult result = rowToStaffWindow(
                            m.selectStaffWindowByIdForUpdate(id(c.windowId())), c.merchantId());
                    audit(m, ctx, "STAFF_WINDOW", id(c.windowId()), id(c.merchantId()),
                            id(c.storeId()), "STAFF_WINDOW_CLOSE", reason,
                            c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    @Override
    public StaffWindowResult openStaffWindow(StaffWindowOpenCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.windowId(), "windowId");
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.staffId(), "staffId");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("staffId", c.staffId());
        p.put("windowId", c.windowId());
        p.put("expectedVersion", Long.toString(c.expectedVersion()));
        Executed<StaffWindowResult> executed = command(
                "merchant.schedule.staffWindow.open",
                ctx,
                "STAFF_WINDOW:" + c.windowId(),
                p,
                StaffWindowResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    Map<String, Object> row =
                            lockedStaffWindow(m, c.windowId(), c.storeId(), c.staffId());
                    if (!"CLOSED".equals(ScheduleSqlRows.text(row, "status"))) stateNotAllowed();
                    requireCurrentVersion(row, c.expectedVersion());
                    ensureNoAvailableOverlap(m, c.storeId(), c.staffId(),
                            ScheduleSqlRows.at(row, "start_at"),
                            ScheduleSqlRows.at(row, "end_at"), c.windowId());
                    one(m.openStaffWindow(id(c.windowId()), c.expectedVersion(), now()));
                    StaffWindowResult result = rowToStaffWindow(
                            m.selectStaffWindowByIdForUpdate(id(c.windowId())), c.merchantId());
                    audit(m, ctx, "STAFF_WINDOW", id(c.windowId()), id(c.merchantId()),
                            id(c.storeId()), "STAFF_WINDOW_OPEN", null,
                            c.expectedVersion(), c.expectedVersion() + 1);
                    return result;
                });
        return executed.result();
    }

    // ------------------------------------------------------------------ capabilities

    @Override
    public CapabilityView getCapabilities(CapabilityQuery q) {
        CommandContext ctx = user(q == null ? null : q.context());
        windowId(q.merchantId(), "merchantId");
        windowId(q.storeId(), "storeId");
        windowId(q.staffId(), "staffId");
        long staffKey = id(q.staffId());
        return store.execute(m -> {
            admissions.requireOperable(query(ctx), id(q.merchantId()), id(q.storeId()));
            guard.acquire(List.of(q.storeId()), query(ctx));
            requireActiveStaff(ctx, q.storeId(), q.staffId(), q.merchantId());
            Map<String, Object> header = m.selectCapabilitySetForUpdate(staffKey);
            List<Map<String, Object>> details = m.selectCapabilitiesForUpdate(staffKey);
            if (header == null) {
                // 34号 §3: version "0" only when the confirmed-legal staff has neither header
                // nor details; details without a header are unmigrated legacy and fail closed.
                if (!details.isEmpty()) unavailable("capability set header is missing");
                return new CapabilityView(q.merchantId(), q.storeId(), q.staffId(),
                        List.of(), "0");
            }
            long version = ScheduleSqlRows.number(header, "version");
            if (version == 0 && !details.isEmpty()) {
                unavailable("capability set header and details disagree");
            }
            List<String> serviceIds = new ArrayList<>();
            for (Map<String, Object> detail : details) {
                if (!"ENABLED".equals(ScheduleSqlRows.text(detail, "status"))) {
                    unavailable("capability detail status is unsupported");
                }
                serviceIds.add(ScheduleSqlRows.id(detail, "service_id"));
            }
            return new CapabilityView(q.merchantId(), q.storeId(), q.staffId(),
                    List.copyOf(serviceIds), Long.toString(version));
        });
    }

    @Override
    public CapabilityResult replaceCapabilities(ReplaceCapabilitiesCommand c) {
        CommandContext ctx = user(c == null ? null : c.context());
        windowId(c.merchantId(), "merchantId");
        windowId(c.storeId(), "storeId");
        windowId(c.staffId(), "staffId");
        if (c.serviceIds() == null) invalid("serviceIds are required");
        List<String> distinct = List.copyOf(new LinkedHashSet<>(c.serviceIds()));
        if (distinct.size() != c.serviceIds().size()) invalid("serviceIds contain duplicates");
        for (String serviceId : distinct) windowId(serviceId, "serviceId");
        long expectedVersion = nonNegativeVersion(c.expectedVersion());
        String reason = optionalReason(c.reason());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("merchantId", c.merchantId());
        p.put("storeId", c.storeId());
        p.put("staffId", c.staffId());
        p.put("serviceIds", distinct);
        p.put("expectedVersion", c.expectedVersion());
        p.put("reason", reason);
        Executed<CapabilityResult> executed = command(
                "merchant.schedule.capability.replace",
                ctx,
                "STAFF:" + c.merchantId() + ":" + c.storeId() + ":" + c.staffId(),
                p,
                CapabilityResult.class,
                m -> {
                    admissions.requireOperable(query(ctx), id(c.merchantId()), id(c.storeId()));
                    guard.acquire(List.of(c.storeId()), query(ctx));
                    requireActiveStaff(ctx, c.storeId(), c.staffId(), c.merchantId());
                    for (String serviceId : distinct) {
                        requireServiceIdentity(ctx, c.merchantId(), c.storeId(), serviceId, null);
                    }
                    long staffKey = id(c.staffId());
                    Map<String, Object> header = m.selectCapabilitySetForUpdate(staffKey);
                    List<Map<String, Object>> details = m.selectCapabilitiesForUpdate(staffKey);
                    Set<String> current = new LinkedHashSet<>();
                    for (Map<String, Object> detail : details) {
                        if (!"ENABLED".equals(ScheduleSqlRows.text(detail, "status"))) {
                            unavailable("capability detail status is unsupported");
                        }
                        current.add(ScheduleSqlRows.id(detail, "service_id"));
                    }
                    if (header == null) {
                        if (!details.isEmpty()) unavailable("capability set header is missing");
                        if (expectedVersion != 0) conflict("capability set version changed");
                    } else {
                        long version = ScheduleSqlRows.number(header, "version");
                        if (version == 0 && !details.isEmpty()) {
                            unavailable("capability set header and details disagree");
                        }
                        if (version != expectedVersion) {
                            conflict("capability set version changed");
                        }
                    }
                    Set<String> removed = new LinkedHashSet<>(current);
                    removed.removeAll(distinct);
                    if (!removed.isEmpty() && reason == null) {
                        invalid("reason is required for capability removals");
                    }
                    long nextVersion = expectedVersion + 1;
                    if (header == null) {
                        one(m.insertCapabilitySet(staffKey, id(c.storeId()), nextVersion, now()));
                    } else {
                        one(m.casCapabilitySetVersion(
                                staffKey, expectedVersion, nextVersion, now()));
                    }
                    Set<String> added = new LinkedHashSet<>(distinct);
                    added.removeAll(current);
                    for (String serviceId : added) {
                        one(m.insertCapability(store.nextId(), staffKey, id(serviceId), now()));
                    }
                    for (String serviceId : removed) {
                        one(m.deleteCapability(staffKey, id(serviceId)));
                    }
                    if (!removed.isEmpty()) proof.proveReductionSafe(c.storeId(), query(ctx));
                    List<String> effective = m.selectCapabilitiesForUpdate(staffKey).stream()
                            .map(row -> ScheduleSqlRows.id(row, "service_id")).sorted().toList();
                    audit(m, ctx, "CAPABILITY_SET", staffKey, id(c.merchantId()), id(c.storeId()),
                            "CAPABILITY_REPLACE", reason, expectedVersion, nextVersion);
                    return new CapabilityResult(c.merchantId(), c.storeId(), c.staffId(),
                            List.copyOf(effective), Long.toString(nextVersion));
                });
        return executed.result();
    }

    // ------------------------------------------------------------------ command plumbing

    private <T> Executed<T> command(
            String namespace,
            CommandContext ctx,
            String authority,
            Map<String, Object> params,
            Class<T> type,
            java.util.function.Function<ScheduleWriteMapper, T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "schedule command admission must not run inside an existing transaction");
        }
        ScheduleCanonicalParams.Canonical canonical = ScheduleCanonicalParams.of(params);
        String key =
                ScheduleWriteStore.requestKey(
                        namespace, ctx.operatorType().name(), ctx.operatorId(), authority,
                        ctx.requestId());
        admitWithRecovery(key, canonical);
        try {
            return runBound(key, canonical, ctx, params, type, action);
        } catch (ScheduleWriteStore.CommitUnknown first) {
            try {
                return runBound(key, canonical, ctx, params, type, action);
            } catch (ScheduleWriteStore.CommitUnknown second) {
                unavailable("schedule commit result remains unknown");
                throw second;
            }
        }
    }

    private <T> Executed<T> runBound(
            String key,
            ScheduleCanonicalParams.Canonical canonical,
            CommandContext ctx,
            Map<String, Object> params,
            Class<T> type,
            java.util.function.Function<ScheduleWriteMapper, T> action) {
        return store.execute(
                m -> {
                    ScheduleWriteStore.Binding b = ScheduleWriteStore.require(m, key);
                    ScheduleWriteStore.same(b, canonical);
                    if ("SUCCEEDED".equals(b.status())) {
                        // 23号: authorization is re-verified before a replayed receipt is returned.
                        reverifyAuthority(ctx, params);
                        return new Executed<>(false, read(b.receiptJson(), type));
                    }
                    T result = action.apply(m);
                    one(m.markBindingSucceeded(key, write(result)));
                    return new Executed<>(true, result);
                });
    }

    /** The canonical params always carry merchantId/storeId; replays re-check admission. */
    private void reverifyAuthority(CommandContext ctx, Map<String, Object> params) {
        Object merchantId = params.get("merchantId");
        Object storeId = params.get("storeId");
        if (!(merchantId instanceof String merchant) || !(storeId instanceof String store)) {
            unavailable("idempotency receipt is missing its authority scope");
            return;
        }
        admissions.requireOperable(query(ctx), id(merchant), id(store));
    }

    private void admitWithRecovery(String key, ScheduleCanonicalParams.Canonical canonical) {
        try {
            store.admit(key, canonical);
        } catch (ScheduleWriteStore.CommitUnknown firstUnknown) {
            try {
                store.admit(key, canonical);
            } catch (ScheduleWriteStore.CommitUnknown secondUnknown) {
                unavailable("idempotency admission result remains unknown");
            }
        }
    }

    private String write(Object result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception failure) {
            throw unavailableFailure("schedule receipt serialization failed", failure);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception failure) {
            throw unavailableFailure("schedule receipt is unreadable", failure);
        }
    }

    // ------------------------------------------------------------------ guards and checks

    private void requireServiceIdentity(CommandContext ctx, String merchantId, String storeId,
            String serviceId, String windowKind) {
        ServiceSnapshotDTO snapshot;
        try {
            snapshot = serviceFacts.getServiceSnapshot(
                    new ServiceSnapshotQuery(serviceId, query(ctx)));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unreadable) {
            unavailable("service facts are unavailable");
            throw unreadable;
        }
        if (snapshot == null || !storeId.equals(snapshot.storeId())
                || !merchantId.equals(snapshot.merchantId())) notFound();
        if (windowKind == null) return;
        // SCHW-D2 matrix: IN_STORE maintains GENERAL only; PICKUP_DELIVERY maintains
        // PICKUP/RETURN only. Written command validation is a 400 (approved recommendation).
        String fulfillment = snapshot.fulfillmentType() == null
                ? null : snapshot.fulfillmentType().name();
        boolean valid = "IN_STORE".equals(fulfillment)
                ? "GENERAL".equals(windowKind)
                : "PICKUP_DELIVERY".equals(fulfillment)
                    && ("PICKUP".equals(windowKind) || "RETURN".equals(windowKind));
        if (!valid) invalid("windowKind does not match the service fulfillment type");
    }

    private void requireActiveStaff(CommandContext ctx, String storeId, String staffId,
            String merchantId) {
        CurrentStoreStaffFacts employees;
        try {
            employees = merchant.readStore(storeId, query(ctx));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unreadable) {
            unavailable("merchant staff facts are unavailable");
            throw unreadable;
        }
        if (employees == null || !employees.complete()) {
            unavailable("merchant staff facts are incomplete");
        }
        CurrentStaffFact person = employees.items().stream()
                .filter(item -> staffId.equals(item.staffId()))
                .findFirst()
                .orElse(null);
        // Anti-enumeration: a staff row of another store looks like no staff at all.
        if (person == null || !merchantId.equals(person.merchantId())) notFound();
        if (!"ACTIVE".equals(person.employmentStatus())) {
            // SCHW-D6: INACTIVE staff cannot receive schedule edits as a capacity premise (409).
            stateNotAllowed();
        }
    }

    /** 36号: a staff member with a protected current assignment must be reassigned first. */
    private void requireNoProtectedAssignment(String storeId, String staffId, CommandContext ctx) {
        OrderProtectionSnapshot snapshot;
        try {
            snapshot = orders.getCurrentAssignments(storeId, List.of(staffId), query(ctx));
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException unreadable) {
            unavailable("order assignment facts are unavailable");
            throw unreadable;
        }
        if (snapshot == null || !snapshot.complete()) {
            unavailable("order assignment facts are incomplete");
        }
        boolean blocked = snapshot.items().stream().anyMatch(item -> item != null
                && staffId.equals(item.currentStaffId()) && item.protectRequired());
        if (blocked) {
            throw new ApiException(CommonApiCodes.CONFLICT,
                    "the staff member still holds a protected assignment");
        }
    }

    private static void ensureNoOpenOverlap(StoreScheduleFacts snapshot, String serviceId,
            String kind, OffsetDateTime start, OffsetDateTime end, String excludedWindowId) {
        for (WindowFact window : snapshot.windows()) {
            // SOLD_OUT windows still hold their open slot: a sold-out window blocks overlapping
            // creations and reopens, so a later release can return it to OPEN without ever
            // producing two overlapping open windows (Contract53 §3).
            if ((!"OPEN".equals(window.status()) && !"SOLD_OUT".equals(window.status()))
                    || !serviceId.equals(window.serviceId())
                    || !kind.equals(window.kind())
                    || window.windowId().equals(excludedWindowId)) {
                continue;
            }
            // Half-open [start,end) intervals: adjacency is allowed, real intersection is not.
            if (window.startAt().isBefore(end) && start.isBefore(window.endAt())) {
                throw new ApiException(
                        ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP,
                        "an open window of the same service and kind already covers this interval");
            }
        }
    }

    private void ensureNoAvailableOverlap(ScheduleWriteMapper m, String storeId, String staffId,
            OffsetDateTime start, OffsetDateTime end, String excludedWindowId) {
        for (Map<String, Object> row : m.listStaffWindows(id(storeId), id(staffId))) {
            if (!"AVAILABLE".equals(ScheduleSqlRows.text(row, "status"))
                    || ScheduleSqlRows.id(row, "id").equals(excludedWindowId)) {
                continue;
            }
            OffsetDateTime otherStart = ScheduleSqlRows.at(row, "start_at");
            OffsetDateTime otherEnd = ScheduleSqlRows.at(row, "end_at");
            if (otherStart.isBefore(end) && start.isBefore(otherEnd)) {
                throw new ApiException(
                        ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP,
                        "an available window of the same staff already covers this interval");
            }
        }
    }

    private static List<ClaimFact> activeClaimsOn(StoreScheduleFacts snapshot, long windowId) {
        Set<String> active = new HashSet<>();
        for (ReservationFact reservation : snapshot.reservations()) {
            if ("TEMP_LOCKED".equals(reservation.status())
                    || "CONFIRMED".equals(reservation.status())) {
                active.add(reservation.reservationId());
            }
        }
        List<ClaimFact> claims = new ArrayList<>();
        for (ClaimFact claim : snapshot.claims()) {
            if (id(claim.windowId()) == windowId && active.contains(claim.reservationId())) {
                claims.add(claim);
            }
        }
        return claims;
    }

    private static boolean isReduction(Map<String, Object> row, OffsetDateTime start,
            OffsetDateTime end) {
        OffsetDateTime oldStart = ScheduleSqlRows.at(row, "start_at");
        OffsetDateTime oldEnd = ScheduleSqlRows.at(row, "end_at");
        return start.isAfter(oldStart) || end.isBefore(oldEnd);
    }

    private Map<String, Object> lockedWindow(ScheduleWriteMapper m, String windowId,
            String merchantId, String storeId) {
        Map<String, Object> row = m.selectWindowByIdForUpdate(id(windowId));
        if (row == null || id(merchantId) != ScheduleSqlRows.number(row, "merchant_id")
                || id(storeId) != ScheduleSqlRows.number(row, "store_id")) {
            notFound();
        }
        return row;
    }

    private Map<String, Object> lockedStaffWindow(ScheduleWriteMapper m, String windowId,
            String storeId, String staffId) {
        Map<String, Object> row = m.selectStaffWindowByIdForUpdate(id(windowId));
        if (row == null || id(storeId) != ScheduleSqlRows.number(row, "store_id")
                || id(staffId) != ScheduleSqlRows.number(row, "staff_id")) {
            notFound();
        }
        return row;
    }

    /** Optimistic CAS pre-check on the locked row: a stale expectedVersion is an editing
     * conflict to re-read (409 COMMON_CONFLICT), never a dependency failure (ServiceCommandService
     * requireEditable precedent; SCHC-2 conflict semantics for the whole write side). */
    private static void requireCurrentVersion(Map<String, Object> row, long expectedVersion) {
        if (ScheduleSqlRows.number(row, "version") != expectedVersion) {
            conflict("schedule version changed; re-read and retry");
        }
    }

    private void audit(ScheduleWriteMapper m, CommandContext ctx, String targetType,
            long targetId, long merchantId, long storeId, String action, String reason,
            Long versionBefore, long versionAfter) {
        one(m.insertWriteAction(ScheduleSqlRows.values(
                "id", store.nextId(), "targetType", targetType, "targetId", targetId,
                "merchantId", merchantId, "storeId", storeId, "action", action,
                "actorUserId", id(ctx.operatorId()),
                "requestId", ctx.requestId().getBytes(StandardCharsets.UTF_8),
                "traceId", ctx.traceId(), "reason", reason,
                "versionBefore", versionBefore, "versionAfter", versionAfter,
                "occurredAt", now())));
    }

    // ------------------------------------------------------------------ conversions

    private static WindowResult rowToWindow(Map<String, Object> row) {
        return new WindowResult(
                ScheduleSqlRows.id(row, "id"),
                ScheduleSqlRows.id(row, "merchant_id"),
                ScheduleSqlRows.id(row, "store_id"),
                ScheduleSqlRows.id(row, "service_id"),
                ScheduleSqlRows.text(row, "window_kind"),
                ScheduleSqlRows.at(row, "start_at"),
                ScheduleSqlRows.at(row, "end_at"),
                (int) ScheduleSqlRows.number(row, "configured_capacity"),
                ScheduleSqlRows.text(row, "status"),
                ScheduleSqlRows.version(row, "version"));
    }

    private static WindowResult toWindow(WindowFact window) {
        return new WindowResult(window.windowId(), window.merchantId(), window.storeId(),
                window.serviceId(), window.kind(), window.startAt(), window.endAt(),
                window.configuredCapacity(), window.status(), window.version());
    }

    private static StaffWindowResult rowToStaffWindow(Map<String, Object> row,
            String merchantId) {
        return new StaffWindowResult(
                ScheduleSqlRows.id(row, "id"),
                merchantId,
                ScheduleSqlRows.id(row, "store_id"),
                ScheduleSqlRows.id(row, "staff_id"),
                ScheduleSqlRows.at(row, "start_at"),
                ScheduleSqlRows.at(row, "end_at"),
                ScheduleSqlRows.text(row, "status"),
                ScheduleSqlRows.version(row, "version"));
    }

    // ------------------------------------------------------------------ primitives

    private static CommandContext user(CommandContext context) {
        if (context == null) invalid("command context is required");
        if (context.operatorType() != OperatorType.USER
                || context.operatorId() == null || context.operatorId().isBlank()) {
            throw new ApiException(CommonApiCodes.FORBIDDEN, "merchant session is required");
        }
        if (context.requestId() == null || context.requestId().isBlank()
                || context.requestId().getBytes(StandardCharsets.UTF_8).length > 512) {
            invalid("requestId is invalid");
        }
        return context;
    }

    private static QueryContext query(CommandContext ctx) {
        return new QueryContext(ctx.traceId(), ctx.operatorType(), ctx.operatorId());
    }

    private static void interval(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || start.getSecond() != 0 || start.getNano() != 0
                || end.getSecond() != 0 || end.getNano() != 0 || !end.isAfter(start)) {
            invalid("minute-precision interval is required");
        }
    }

    private static String requiredReason(String reason) {
        String value = reason == null ? null : reason.trim();
        if (value == null || value.isEmpty() || value.length() > 500) {
            invalid("reason must contain 1 to 500 characters");
        }
        return value;
    }

    private static String optionalReason(String reason) {
        if (reason == null) return null;
        String value = reason.trim();
        if (value.isEmpty()) return null;
        if (value.length() > 500) invalid("reason must contain 1 to 500 characters");
        return value;
    }

    private static long nonNegativeVersion(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) {
            invalid("expectedVersion is invalid");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            // 34号 §3: version overflow is rejected, never wrapped around.
            invalid("expectedVersion is invalid");
            throw overflow;
        }
    }

    private static long id(String value) {
        try {
            long id = IDS.fromApi(value);
            if (id > 0) return id;
        } catch (RuntimeException ignored) { }
        invalid("positive public ID is required");
        throw new AssertionError("unreachable");
    }

    private static void windowId(String value, String field) {
        if (value == null || value.isBlank()) invalid(field + " is required");
        id(value);
    }

    private static void one(int rows) {
        if (rows != 1) unavailable("schedule write affected an unexpected row count");
    }

    private static LocalDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void notFound() {
        throw new ApiException(CommonApiCodes.NOT_FOUND, "schedule resource not found");
    }

    private static void stateNotAllowed() {
        throw new ApiException(
                ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                "该时段当前状态不允许该操作");
    }

    private static void conflict(String message) {
        throw new ApiException(CommonApiCodes.CONFLICT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private static ApiException unavailableFailure(String message, Throwable cause) {
        ApiException failure = new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }
}
