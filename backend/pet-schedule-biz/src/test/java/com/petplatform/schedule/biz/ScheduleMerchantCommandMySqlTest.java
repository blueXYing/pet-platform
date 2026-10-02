package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.dto.MerchantAdmissionDTO;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.dto.MerchantMembershipPageDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
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
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
import com.petplatform.schedule.api.error.ScheduleWriteApiCodes;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandService;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCH-004 merchant write side against real MySQL (core rows of W2-SCHW-001/003/004/005/006/
 * 007/008): 23号 idempotent envelope, OWNER admission, the per-store guard inside one
 * transaction, occupied-window protection (SCHW-D4), batch partial close (SCHW-D5), staff
 * reduction protection (SCHW-D6 with 36号 assignment facts), capability set CAS (SCHC-2) and
 * the append-only audit (SCHW-D9). Every command opens its own admission and execution
 * transactions, so tests call the API directly without an surrounding transaction.
 */
class ScheduleMerchantCommandMySqlTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:34:56.789Z");
    private static final OffsetDateTime S = OffsetDateTime.parse("2030-01-01T01:00:00Z");
    private static final OffsetDateTime E = OffsetDateTime.parse("2030-01-01T02:00:00Z");
    private static final OffsetDateTime S130 = OffsetDateTime.parse("2030-01-01T01:30:00Z");
    private static final OffsetDateTime E230 = OffsetDateTime.parse("2030-01-01T02:30:00Z");
    private static final OffsetDateTime E300 = OffsetDateTime.parse("2030-01-01T03:00:00Z");

    @Test
    void createWindowCommitsAuditsAndReplaysTheFirstReceipt() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            WindowResult[] out = new WindowResult[1];
            assertTrue(app.commands.createWindowOutcome(create("950"), out));
            assertEquals("OPEN", out[0].status());
            assertEquals("0", out[0].version());
            assertEquals("GENERAL", out[0].windowKind());
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals(1, db.count("schedule_write_action"));
            // Same requestId replays the stored receipt without a second row or audit.
            WindowResult[] replay = new WindowResult[1];
            assertFalse(app.commands.createWindowOutcome(create("950"), replay));
            assertEquals(out[0].windowId(), replay[0].windowId());
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals(1, db.count("schedule_write_action"));
            assertEquals("SUCCEEDED", db.jdbc.queryForObject(
                    "SELECT status FROM command_idempotency", String.class));
        }
    }

    @Test
    void sameRequestIdWithDifferentParametersIsAConflict() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            app.commands.createWindowOutcome(create("950"), new WindowResult[1]);
            CreateWindowCommand different = new CreateWindowCommand(
                    new CommandContext("950", "sch004-test", OperatorType.USER, "601", "test"),
                    "10", "201", "301", "GENERAL", S, E, 5);
            ApiException conflict = assertThrows(ApiException.class,
                    () -> app.commands.createWindowOutcome(different, new WindowResult[1]));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
            assertEquals(1, db.count("schedule_availability_window"));
        }
    }

    @Test
    void overlappingOpenWindowsOfOneServiceKindAreRejectedButAdjacentPass() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            app.commands.createWindowOutcome(create("950"), new WindowResult[1]);
            // A real intersection [01:30,02:30) against [01:00,02:00) is rejected (SCHW-D3).
            ApiException overlap = assertThrows(ApiException.class, () -> app.commands
                    .createWindowOutcome(create("951", S130, E230), new WindowResult[1]));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP, overlap.code());
            // Half-open adjacency [02:00,03:00) is allowed.
            assertTrue(app.commands.createWindowOutcome(create("952", E, E300),
                    new WindowResult[1]));
            assertEquals(2, db.count("schedule_availability_window"));
        }
    }

    @Test
    void occupiedWindowCannotCloseShrinkOrMoveButCapacityIncreasePasses() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            db.hold(app, "501"); // TEMP_LOCKED claim binds window 101 at version 0
            // SCHW-D4: close, move and shrink are all refused while a claim holds the window.
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.closeWindow(
                            new WindowCloseCommand(ctx(), "101", "10", "201", 0L, "x")))
                            .code());
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.updateWindow(
                            new UpdateWindowCommand(ctx(), "101", "10", "201",
                                    S130, E230, 2, 0L, null))).code());
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.updateWindow(
                            new UpdateWindowCommand(ctx(), "101", "10", "201", S, E, 1, 0L, null)))
                            .code());
            // Capacity increase stays allowed; its CAS on version 0 proves nothing else moved.
            WindowResult raised = app.commands.updateWindow(new UpdateWindowCommand(
                    ctx(), "101", "10", "201", S, E, 5, 0L, null));
            assertEquals("1", raised.version());
            assertEquals(5, raised.configuredCapacity());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void closeRequiresReasonAndReopenRechecksOverlap() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            // PRD29: 关闭…必填原因 — an empty reason is a 400 before any write.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> app.commands.closeWindow(
                            new WindowCloseCommand(ctx(), "101", "10", "201", 0L, " "))).code());
            assertEquals("OPEN", db.windowStatus(101));
            WindowResult closed = app.commands.closeWindow(
                    new WindowCloseCommand(ctx(), "101", "10", "201", 0L, "停业检修"));
            assertEquals("CLOSED", closed.status());
            // While closed, an overlapping replacement window may exist; reopening into it 409s.
            assertTrue(app.commands.createWindowOutcome(create("951", S130, E230),
                    new WindowResult[1]));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP,
                    assertThrows(ApiException.class, () -> app.commands.openWindow(
                            new WindowOpenCommand(ctx(), "101", "10", "201", 1L))).code());
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_availability_window WHERE status='OPEN'", Integer.class));
        }
    }

    @Test
    void staleExpectedVersionIsAConflictNotADependencyFailure() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            // A stale optimistic version is an editing conflict to re-read (COMMON_CONFLICT),
            // never a 503 dependency failure; the row and the audit stay untouched.
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.updateWindow(new UpdateWindowCommand(
                            ctx(), "101", "10", "201", S, E, 3, 7L, null))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.closeWindow(
                            new WindowCloseCommand(ctx(), "101", "10", "201", 7L, "x"))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.closeStaffWindow(new StaffWindowCloseCommand(
                            ctx(), "404", "10", "201", "401", 9L, "请假"))).code());
            assertEquals("OPEN", db.windowStatus(101));
            assertEquals("AVAILABLE", db.staffWindowStatus(404));
            assertEquals(0, db.count("schedule_write_action"));
        }
    }

    @Test
    void batchCloseSplitsBlockedFromClosedAndReplaysTheOriginalSplit() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            app.commands.createWindowOutcome(create("951",
                    OffsetDateTime.parse("2030-01-01T03:00:00Z"),
                    OffsetDateTime.parse("2030-01-01T04:00:00Z")), new WindowResult[1]);
            db.hold(app, "501"); // occupies window 101
            BatchCloseCommand batch = new BatchCloseCommand(ctx(), "10", "201",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-02"), "临时停业一天");
            BatchCloseResult first = app.commands.batchClose(batch);
            assertEquals(1, first.closedWindows().size());
            assertEquals(1, first.blockedWindows().size());
            assertEquals("101", first.blockedWindows().getFirst().window().windowId());
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    first.blockedWindows().getFirst().reasonCode());
            // PRD29: 未关闭时段必须明示，部分成功不伪称全店停业 — the replay returns the original split.
            BatchCloseResult replayed = app.commands.batchClose(batch);
            assertEquals(first.closedWindows().size(), replayed.closedWindows().size());
            assertEquals(first.blockedWindows().size(), replayed.blockedWindows().size());
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_availability_window WHERE status='OPEN'", Integer.class));
            // One audit row per closed target under the same requestId.
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action "
                    + "WHERE action='WINDOW_BATCH_CLOSE'", Integer.class));
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void staffWindowsRequireActiveEmploymentAndRejectOverlaps() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            // INACTIVE staff is not a capacity premise (SCHW-D6): 409 and no rows.
            app.merchant.employmentStatus = "INACTIVE";
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    assertThrows(ApiException.class, () -> app.commands.createStaffWindowOutcome(
                            new CreateStaffWindowCommand(ctx(), "10", "201", "401", S, E),
                            new StaffWindowResult[1])).code());
            assertEquals(0, db.count("staff_availability_window"));
            app.merchant.employmentStatus = "ACTIVE";
            StaffWindowResult[] out = new StaffWindowResult[1];
            assertTrue(app.commands.createStaffWindowOutcome(
                    new CreateStaffWindowCommand(ctx(), "10", "201", "401", S, E), out));
            assertEquals("AVAILABLE", out[0].status());
            assertEquals("0", out[0].version());
            // Overlap rejected, adjacent allowed.
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP,
                    assertThrows(ApiException.class, () -> app.commands.createStaffWindowOutcome(
                            new CreateStaffWindowCommand(ctx(), "10", "201", "401", S130, E),
                            new StaffWindowResult[1])).code());
            assertTrue(app.commands.createStaffWindowOutcome(new CreateStaffWindowCommand(
                    ctx(), "10", "201", "401", E, E300), new StaffWindowResult[1]));
            assertEquals(2, db.count("staff_availability_window"));
        }
    }

    @Test
    void staffReductionIsProtectedByAssignmentCheckAndWholeStoreProof() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral(); // staff 401 capability+availability cover the seeded window
            db.hold(app, "501"); // TEMP_LOCKED claim depends on the only qualified employee 401
            // A protected current assignment must be reassigned first (36号): 409, no writes.
            app.orders.protectStaff("401");
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.closeStaffWindow(new StaffWindowCloseCommand(
                            ctx(), "404", "10", "201", "401", 0L, "请假"))).code());
            assertEquals("AVAILABLE", db.staffWindowStatus(404));
            // Without an assignment the reduction still must keep every active reservation
            // coverable: 401 is the only qualified person, so the close fails closed as a 409.
            app.orders.protectStaff(null);
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.closeStaffWindow(new StaffWindowCloseCommand(
                            ctx(), "404", "10", "201", "401", 0L, "请假"))).code());
            assertEquals("AVAILABLE", db.staffWindowStatus(404));
            // A second qualified, scheduled employee keeps the store feasible: reduction passes.
            db.secondStaffCovers();
            StaffWindowResult closed = app.commands.closeStaffWindow(new StaffWindowCloseCommand(
                    ctx(), "404", "10", "201", "401", 0L, "请假"));
            assertEquals("CLOSED", closed.status());
            assertEquals("1", closed.version());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void capabilitySetCoversFirstEmptyVersionCasAndConflict() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            CapabilityView first = app.commands.getCapabilities(
                    new CapabilityQuery(ctx(), "10", "201", "401"));
            assertEquals(List.of(), first.serviceIds());
            assertEquals("0", first.version());
            // First PUT must carry expectedVersion "0"; the header is created atomically.
            CapabilityResult created = app.commands.replaceCapabilities(replace("0", null, "301"));
            assertEquals("1", created.version());
            assertEquals(List.of("301"), created.serviceIds());
            // A second editor still holding version "0" loses with COMMON_CONFLICT (SCHC-2).
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.replaceCapabilities(replace("0", null, "301"))).code());
            CapabilityResult grown = app.commands.replaceCapabilities(
                    replace("1", null, "301", "302"));
            assertEquals("2", grown.version());
            assertEquals(List.of("301", "302"), grown.serviceIds());
            // The pinned key binds its parameters, then the same key with different
            // parameters never overwrites the set (23号).
            CapabilityResult pinned = app.commands.replaceCapabilities(
                    new ReplaceCapabilitiesCommand(COMMAND_KEY, "10", "201", "401",
                            List.of("301", "302"), "2", null));
            assertEquals("3", pinned.version());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    assertThrows(ApiException.class, () -> app.commands.replaceCapabilities(
                            new ReplaceCapabilitiesCommand(COMMAND_KEY, "10", "201", "401",
                                    List.of("301", "302", "303"), "3", null))).code());
        }
    }

    @Test
    void capabilityRemovalRequiresReasonAndFailClosedWithoutAlternativeStaff() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            // The seeded legacy detail is inventoried and backfilled to a non-zero baseline
            // version before protected use (34号 storage §3).
            db.jdbc.update("INSERT INTO staff_capability_set(staff_id,store_id,version,"
                    + "updated_at) VALUES(401,201,1,UTC_TIMESTAMP(3))");
            db.hold(app, "501"); // a protected reservation served by the only employee 401
            CapabilityView view = app.commands.getCapabilities(
                    new CapabilityQuery(ctx(), "10", "201", "401"));
            assertEquals(List.of("301"), view.serviceIds());
            assertEquals("1", view.version());
            // Removing the last qualification of the only employee breaks the held reservation:
            // a missing reason is a 400; with a reason the whole-store proof still refuses it.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> app.commands.replaceCapabilities(replace("1", null))).code());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.replaceCapabilities(replace("1", "不再提供该服务"))).code());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM staff_service_capability WHERE staff_id=401",
                    Integer.class));
            assertEquals(1L, db.jdbc.queryForObject(
                    "SELECT version FROM staff_capability_set WHERE staff_id=401", Long.class));
            // A second qualified scheduled employee makes the removal provably safe.
            db.secondStaffCovers();
            CapabilityResult reduced = app.commands.replaceCapabilities(
                    replace("1", "不再提供该服务"));
            assertEquals("2", reduced.version());
            assertEquals(List.of(), reduced.serviceIds());
        }
    }

    @Test
    void legacyCapabilityDetailsWithoutHeaderFailClosed() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(900,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            // 34号 §3: unmigrated details are LEGACY_UNVERSIONED — never an empty set at "0".
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> app.commands.getCapabilities(
                            new CapabilityQuery(ctx(), "10", "201", "401"))).code());
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> app.commands.replaceCapabilities(replace("0", null, "302"))).code());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM staff_service_capability WHERE staff_id=401",
                    Integer.class));
        }
    }

    private static SnowflakeIdGenerator seq(long start) {
        AtomicLong sequence = new AtomicLong(start);
        return sequence::incrementAndGet;
    }

    /** Fresh terminal context per logical request; replays intentionally reuse one. */
    private static CommandContext ctx() {
        return new CommandContext(UUID.randomUUID().toString(), "sch004-test",
                OperatorType.USER, "601", "test");
    }

    /** One pinned key to prove cross-parameter conflicts for a single requestId. */
    private static final CommandContext COMMAND_KEY = ctx();

    private static CreateWindowCommand create(String requestId) {
        return create(requestId, S, E);
    }

    private static CreateWindowCommand create(String requestId, OffsetDateTime start,
            OffsetDateTime end) {
        return new CreateWindowCommand(
                new CommandContext(requestId, "sch004-test", OperatorType.USER, "601", "test"),
                "10", "201", "301", "GENERAL", start, end, 2);
    }

    private static ReplaceCapabilitiesCommand replace(String expectedVersion, String reason,
            String... serviceIds) {
        return new ReplaceCapabilitiesCommand(ctx(), "10", "201", "401",
                List.of(serviceIds), expectedVersion, reason);
    }

    private static final class FakeMerchant implements MerchantCurrentStaffFactsApi {
        private String employmentStatus = "ACTIVE";

        @Override
        public CurrentStoreStaffFacts readStore(String storeId, QueryContext context) {
            List<CurrentStaffFact> staff = new ArrayList<>();
            staff.add(new CurrentStaffFact("401", "10", storeId, employmentStatus, true, "0"));
            if ("ACTIVE".equals(employmentStatus)) {
                staff.add(new CurrentStaffFact("402", "10", storeId, "ACTIVE", true, "0"));
            }
            return new CurrentStoreStaffFacts("10", storeId, true, staff);
        }
    }

    private static final class FakeOrders implements OrderProtectionFactsApi {
        private String protectedStaffId;
        private Database db;

        private void protectStaff(String staffId) {
            this.protectedStaffId = staffId;
        }

        @Override
        public OrderProtectionSnapshot readStore(String store, QueryContext query) {
            List<OrderProtectionFact> found = rows(store, null);
            return new OrderProtectionSnapshot(store, true, found.size(), assignments(found),
                    found);
        }

        @Override
        public OrderProtectionSnapshot getByReservations(String store,
                List<String> reservationIds, QueryContext query) {
            List<OrderProtectionFact> found = new ArrayList<>();
            for (String reservationId : reservationIds) {
                found.addAll(rows(store, reservationId));
            }
            return new OrderProtectionSnapshot(store, true, found.size(), assignments(found),
                    found);
        }

        private int assignments(List<OrderProtectionFact> facts) {
            return (int) facts.stream().filter(fact -> fact.currentStaffId() != null).count();
        }

        private List<OrderProtectionFact> rows(String store, String reservationId) {
            String sql = "SELECT id,reservation_id,user_id,merchant_id,store_id,service_id,"
                    + "fulfillment_type FROM pet_order WHERE store_id=?"
                    + (reservationId == null ? "" : " AND reservation_id=?") + " FOR UPDATE";
            Object[] args = reservationId == null
                    ? new Object[]{Long.parseLong(store)}
                    : new Object[]{Long.parseLong(store), Long.parseLong(reservationId)};
            return db.jdbc.query(sql, (rs, n) -> new OrderProtectionFact(rs.getString("id"),
                    rs.getString("reservation_id"), rs.getString("user_id"),
                    rs.getString("merchant_id"), rs.getString("store_id"),
                    rs.getString("service_id"), rs.getString("fulfillment_type"),
                    null, "PENDING_PAYMENT", "UNVERIFIED", "0", null, null, false), args);
        }

        @Override
        public OrderProtectionSnapshot getCurrentAssignments(String store,
                List<String> staffIds, QueryContext query) {
            List<OrderProtectionFact> facts = new ArrayList<>();
            if (protectedStaffId != null && staffIds.contains(protectedStaffId)) {
                facts.add(new OrderProtectionFact("501", "601", "601", "10", store, "301",
                        "IN_STORE", protectedStaffId, "PENDING_PAYMENT", "UNVERIFIED", "0",
                        "701", "0", true));
            }
            return new OrderProtectionSnapshot(store, true, facts.size(), facts.size(), facts);
        }
    }

    private static final class Components {
        private final ScheduleCapacityGuardApiImpl guard;
        private final ScheduleCapacityProofApiImpl proof;
        private final ScheduleMerchantCommandApiImpl commands;
        private final FakeMerchant merchant = new FakeMerchant();
        private final FakeOrders orders = new FakeOrders();

        private Components(Database db) {
            orders.db = db;
            guard = new ScheduleCapacityGuardApiImpl(db.source);
            ScheduleProtectionFactsApi facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
            MerchantAdmissionQueryApi admission = new MerchantAdmissionQueryApi() {
                @Override
                public MerchantMembershipPageDTO listMemberships(MerchantMembershipQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public MerchantAdmissionDTO getAdmission(MerchantAdmissionQuery query) {
                    return new MerchantAdmissionDTO(query.merchantId(), query.storeId(), "OWNER",
                            "ALLOWED", OffsetDateTime.parse("2026-09-27T12:00:00Z"), "1", null,
                            null, "ACTIVE", "ACTIVE", null, List.of(), List.of(), List.of());
                }
            };
            ServiceQueryApi services = new ServiceQueryApi() {
                @Override
                public ServiceSnapshotDTO getServiceSnapshot(ServiceSnapshotQuery query) {
                    return new ServiceSnapshotDTO(query.serviceId(), "10", "201", "洗澡", "cat",
                            "洗护", null, 30, FulfillmentType.IN_STORE, null, null, null, null);
                }

                @Override
                public com.petplatform.service.api.dto.ServiceSnapshotPageDTO
                        getStoreServiceSnapshots(StoreServiceSnapshotQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public com.petplatform.service.api.dto.ServiceBookabilityDTO checkBookable(
                        com.petplatform.service.api.query.ServiceBookabilityQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
                    throw new UnsupportedOperationException();
                }
            };
            proof = new ScheduleCapacityProofApiImpl(db.source, guard, facts, merchant, orders,
                    Clock.fixed(NOW, ZoneOffset.UTC), 1000);
            ScheduleWriteStore store = new ScheduleWriteStore(db.source, seq(5000));
            ScheduleMerchantCommandService service = new ScheduleMerchantCommandService(store,
                    new ScheduleAdmissionGate(admission), guard, facts, merchant, orders,
                    services, proof, Clock.fixed(NOW, ZoneOffset.UTC));
            commands = new ScheduleMerchantCommandApiImpl(service);
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "schw_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            String url = System.getenv().getOrDefault("SCH004_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("MySQL test URL must target a local root");
            }
            String user = System.getenv().getOrDefault("SCH004_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("SCH004_MYSQL_PASSWORD", "root");
            admin = new JdbcTemplate(source(url, user, password));
            source = source(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("Schema06 is absent");
                for (String script : List.of("06-核心数据库Schema-v0.1.sql",
                        "14-Command-Idempotency-Schema-v0.1.sql",
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                        "38-Booking-Create-Schema-v0.1.sql",
                        "52-Schedule-Write-Schema-v0.1.sql")) {
                    try (Connection connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root.resolve("docs/03-database/" + script)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failed) {
                close();
                throw failed;
            }
        }

        private void seedGeneral() {
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "service_id,start_at,end_at,configured_capacity,status,version,created_at,"
                    + "updated_at,window_kind) VALUES(101,10,201,301,'2030-01-01 01:00:00',"
                    + "'2030-01-01 02:00:00',2,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),"
                    + "'GENERAL')");
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(402,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(404,201,401,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }

        private void secondStaffCovers() {
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(412,402,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(414,201,402,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }

        /** One TEMP_LOCKED hold plus its bound order, both committed. */
        private void hold(Components app, String orderId) {
            ScheduleProtectionFactsApi facts =
                    new ScheduleProtectionFactsApiImpl(source, app.guard);
            ReservationHoldApiImpl holdApi = new ReservationHoldApiImpl(source, seq(2000),
                    app.guard, facts, app.proof, app.orders, Clock.fixed(NOW, ZoneOffset.UTC));
            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            HoldResult held = tx.execute(status -> {
                app.guard.acquire(List.of("201"),
                        new QueryContext("sch004-test", OperatorType.USER, "601"));
                HoldResult result = holdApi.hold(new HoldCommand(ctx(), orderId, "601", "10",
                        "201", "301", "IN_STORE", S,
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"), null, null, "101",
                        null, null));
                insertOrder(result);
                return result;
            });
            if (held == null) throw new IllegalStateException("hold failed");
        }

        private void insertOrder(HoldResult held) {
            jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,"
                    + "pet_id,reservation_id,order_stage,payment_status,verification_status,"
                    + "fulfillment_type,original_amount,discount_amount,pay_amount,refunded_amount,"
                    + "appointment_start_at,appointment_end_at,created_at,updated_at) VALUES"
                    + "(?,901,601,10,201,301,701,?,'PENDING_PAYMENT','INIT','UNVERIFIED',"
                    + "'IN_STORE',100.00,0.00,100.00,0.00,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    Long.parseLong(held.orderId()), Long.parseLong(held.reservationId()),
                    java.sql.Timestamp.from(held.startAt().toInstant()),
                    java.sql.Timestamp.from(held.endAt().toInstant()));
        }

        private String windowStatus(long windowId) {
            return jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window WHERE id=?", String.class,
                    windowId);
        }

        private String staffWindowStatus(long windowId) {
            return jdbc.queryForObject(
                    "SELECT status FROM staff_availability_window WHERE id=?", String.class,
                    windowId);
        }

        private int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(url + "?allowPublicKeyRetrieval=true&useSSL=false"
                    + "&connectionTimeZone=UTC", user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failed) {
                        connection.close();
                        throw failed;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }
}
