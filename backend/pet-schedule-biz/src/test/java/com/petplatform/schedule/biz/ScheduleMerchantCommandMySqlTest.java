package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchGenerateCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BatchGenerateResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BlockedWindow;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowReplaceCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.BookingWindowView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.SkippedWindow;
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
import java.time.LocalTime;
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
                    "10", "201", S, E, 5);
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

    // ------------------------------------------------------- CCR-SCH-SLOT-002 batch-generate

    /**
     * 覆盖重建 (53号 §3.4): unified slots intersecting the GENERATION interval
     * [fromDate@dailyStart, toDate@dailyEnd] close first, then minute-level windows tile the
     * interval. Fixtures live at UTC 01:00-03:00 = Beijing 09:00-11:00, so dailyStart/dailyEnd
     * address them through the business zone.
     */
    @Test
    void batchGenerateClosesIntervalIntersectingWindowsAndCreatesMinuteSlots() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            app.commands.createWindowOutcome(create("960"), new WindowResult[1]); // [01,02Z)
            app.commands.createWindowOutcome(create("961", E, E300), new WindowResult[1]); // [02,03Z)
            // An adjacent after-interval slot must survive: the close scope is the
            // generation interval, not whole calendar days (H5 pinned semantics).
            app.commands.createWindowOutcome(create("962", E300,
                    OffsetDateTime.parse("2030-01-01T04:00:00Z")), new WindowResult[1]);
            BatchGenerateResult result = app.commands.batchGenerate(generate("970",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(9, 0), LocalTime.of(11, 0), 30, 6));
            assertEquals(4, result.createdWindows().size());
            assertEquals(2, result.closedWindows().size());
            assertEquals(0, result.skippedWindows().size());
            for (WindowResult created : result.createdWindows()) {
                assertEquals("OPEN", created.status());
                assertEquals("0", created.version());
                assertEquals(6, created.configuredCapacity());
            }
            assertEquals(OffsetDateTime.parse("2030-01-01T01:00:00Z").toInstant(),
                    result.createdWindows().get(0).startAt().toInstant());
            assertEquals(OffsetDateTime.parse("2030-01-01T01:30:00Z").toInstant(),
                    result.createdWindows().get(0).endAt().toInstant());
            // Two closed + three survivors (2 old CLOSED, 1 untouched after-interval OPEN) + 4.
            assertEquals(7, db.count("schedule_availability_window"));
            assertEquals(5, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_availability_window WHERE status='OPEN'", Integer.class));
            // One audit row per closed target and per created window under one requestId.
            // The WINDOW_CREATE count is scoped to the batch requestId ("970"): the three
            // fixture windows above were also created through the real API and carry their
            // own WINDOW_CREATE audit rows (CCR-SCH-SLOT-002 batch-generate).
            assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action"
                    + " WHERE action='WINDOW_BATCH_CLOSE'", Integer.class));
            assertEquals(4, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action"
                    + " WHERE action='WINDOW_CREATE' AND request_id=?",
                    Integer.class, "970".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Occupied windows are skipped (reasonCode OCCUPIED) and their interval is never
     * regenerated — the candidates overlapping the skip are dropped, not created. */
    @Test
    void batchGenerateSkipsOccupiedWindowsAndNeverRegeneratesTheirInterval() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral(); // window 101 [01,02Z) capacity 2, store has a permissive range
            db.hold(app, "501"); // TEMP_LOCKED claim occupies window 101
            app.commands.createWindowOutcome(create("961", E, E300), new WindowResult[1]);
            BatchGenerateResult result = app.commands.batchGenerate(generate("971",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 6));
            assertEquals(1, result.skippedWindows().size());
            assertEquals("101", result.skippedWindows().getFirst().window().windowId());
            assertEquals("OCCUPIED", result.skippedWindows().getFirst().reasonCode());
            assertEquals(1, result.closedWindows().size());
            // [01,02Z) overlaps the occupied skip and is not created; [02,03Z) is.
            assertEquals(1, result.createdWindows().size());
            assertEquals(OffsetDateTime.parse("2030-01-01T02:00:00Z").toInstant(),
                    result.createdWindows().getFirst().startAt().toInstant());
            assertEquals("OPEN", db.windowStatus(101)); // the occupied skip stays open
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_availability_window WHERE status='CLOSED'", Integer.class));
        }
    }

    /** A partially overlapping occupied skip swallows every candidate touching it: the
     * store-wide overlap rule holds, no partial overlap is ever created. */
    @Test
    void batchGenerateDropsEveryCandidateOverlappingAPartialSkip() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral();
            // Move the occupied window to straddle the candidate grid: [01:30Z, 02:30Z); the
            // seeded staff availability [01:00Z,02:00Z) still covers the tighter claim.
            db.jdbc.update("UPDATE schedule_availability_window SET start_at="
                    + "'2030-01-01 01:30:00', end_at='2030-01-01 02:30:00' WHERE id=101");
            db.hold(app, "501", OffsetDateTime.parse("2030-01-01T01:45:00Z"),
                    OffsetDateTime.parse("2030-01-01T02:00:00Z"));
            BatchGenerateResult result = app.commands.batchGenerate(generate("975",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 6));
            assertEquals(1, result.skippedWindows().size());
            assertEquals(0, result.closedWindows().size());
            // [01,02Z) and [02,03Z) both touch [01:30,02:30Z): neither is created.
            assertEquals(0, result.createdWindows().size());
            assertEquals("OPEN", db.windowStatus(101));
            assertEquals(1, db.count("schedule_availability_window"));
        }
    }

    /** H5 pinned "保存并生效" semantics: a second segment call of the same day must not close
     * the windows the first segment just created (adjacency to the generation interval). */
    @Test
    void batchGenerateSameDaySecondSegmentKeepsFirstSegmentWindows() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            BatchGenerateResult first = app.commands.batchGenerate(generate("980",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(9, 0), LocalTime.of(10, 0), 60, 6));
            BatchGenerateResult second = app.commands.batchGenerate(generate("981",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(10, 0), LocalTime.of(11, 0), 60, 6));
            assertEquals(1, first.createdWindows().size());
            assertEquals(1, second.createdWindows().size());
            assertEquals(0, second.closedWindows().size());
            assertEquals(0, second.skippedWindows().size());
            assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_availability_window WHERE status='OPEN'", Integer.class));
        }
    }

    /** §3.2 family ceiling: created+closed+skipped candidates over 200 reject the whole
     * command before any close or creation. */
    @Test
    void batchGenerateRejectsMoreThan200CandidatesWhole() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            db.seedGeneral(); // one open window inside the interval -> 376+1 candidates
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> app.commands.batchGenerate(generate("982",
                            LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-08"),
                            LocalTime.of(0, 0), LocalTime.of(23, 59), 30, 6))).code());
            assertEquals("OPEN", db.windowStatus(101));
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals(0, db.count("schedule_write_action"));
        }
    }

    /** Request lexicon (CCR §1): date span 1..366, start<end, interval 1..1440, capacity
     * 1..999, reason 1..500 mandatory. */
    @Test
    void batchGenerateValidatesTheRequestLexicon() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            LocalDate day = LocalDate.parse("2030-01-01");
            for (BatchGenerateCommand bad : List.of(
                    generate("983", day, day, LocalTime.of(9, 0), LocalTime.of(9, 0), 60, 6, "覆盖"),
                    generate("984", day, day, LocalTime.of(10, 0), LocalTime.of(9, 0), 60, 6, "覆盖"),
                    generate("985", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 1441, 6, "覆盖"),
                    generate("986", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 0, 6, "覆盖"),
                    generate("987", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 1000, "覆盖"),
                    generate("988", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 0, "覆盖"),
                    generate("989", LocalDate.parse("2030-01-01"),
                            LocalDate.parse("2031-01-02"), LocalTime.of(9, 0),
                            LocalTime.of(11, 0), 60, 6, "覆盖"),
                    generate("990", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 6, " "),
                    generate("991", day, day, LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 6, null))) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> app.commands.batchGenerate(bad)).code());
            }
            assertEquals(0, db.count("schedule_availability_window"));
        }
    }

    /** 23号 idempotency: the same command replays the first receipt (no second rows); the
     * same requestId with different parameters is a 409 key conflict. */
    @Test
    void batchGenerateReplaysFirstReceiptAndConflictsOnDifferentParameters() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            CommandContext pinned = ctx();
            BatchGenerateCommand command = new BatchGenerateCommand(pinned, "10", "201",
                    LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                    LocalTime.of(9, 0), LocalTime.of(11, 0), 60, 6, "周模板保存");
            BatchGenerateResult first = app.commands.batchGenerate(command);
            BatchGenerateResult replay = app.commands.batchGenerate(command);
            assertEquals(first.createdWindows().size(), replay.createdWindows().size());
            assertEquals(first.createdWindows().getFirst().windowId(),
                    replay.createdWindows().getFirst().windowId());
            assertEquals(2, db.count("schedule_availability_window"));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.batchGenerate(new BatchGenerateCommand(pinned, "10",
                            "201", LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-01"),
                            LocalTime.of(9, 0), LocalTime.of(11, 0), 30, 6, "周模板保存")))
                    .code());
            assertEquals(2, db.count("schedule_availability_window"));
        }
    }

    // ------------------------------------------------------- CCR-SCH-SLOT-002 booking-window

    /** §3.5: absent row = defaults 60/7 at version "0" with a null updatedAt; PUT is a full
     * replacement under expectedVersion CAS; replays return the first receipt; boundary
     * values outside lead 0..1440 / maxDays 1..90 are 400s. */
    @Test
    void bookingWindowGetDefaultsPutCasAndBoundaries() throws Exception {
        try (Database db = new Database()) {
            Components app = new Components(db);
            BookingWindowView absent = app.commands.getBookingWindow(
                    new BookingWindowQuery(ctx(), "10", "201"));
            assertEquals(60, absent.leadMinutes());
            assertEquals(7, absent.maxDays());
            assertEquals("0", absent.version());
            assertNull(absent.updatedAt());
            assertEquals(0, db.count("merchant_schedule_booking_window")); // never backfilled
            BookingWindowResult created = app.commands.replaceBookingWindow(
                    replaceBookingWindow("0", 30, 14));
            assertEquals(30, created.leadMinutes());
            assertEquals(14, created.maxDays());
            assertEquals("1", created.version());
            BookingWindowView stored = app.commands.getBookingWindow(
                    new BookingWindowQuery(ctx(), "10", "201"));
            assertEquals(30, stored.leadMinutes());
            assertEquals(14, stored.maxDays());
            assertEquals("1", stored.version());
            assertNotNull(stored.updatedAt());
            // A second editor still holding "0" loses with COMMON_CONFLICT (no LWW).
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.replaceBookingWindow(replaceBookingWindow("0", 45, 30)))
                    .code());
            // Same pinned key replays the first receipt without advancing the version.
            CommandContext pinned = ctx();
            assertEquals("2", app.commands.replaceBookingWindow(
                    new BookingWindowReplaceCommand(pinned, "10", "201", 45, 30, "1"))
                    .version());
            assertEquals("2", app.commands.replaceBookingWindow(
                    new BookingWindowReplaceCommand(pinned, "10", "201", 45, 30, "1"))
                    .version());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> app.commands.replaceBookingWindow(new BookingWindowReplaceCommand(
                            pinned, "10", "201", 46, 30, "2"))).code());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_schedule_booking_window", Integer.class));
            assertEquals(45, db.jdbc.queryForObject(
                    "SELECT lead_minutes FROM merchant_schedule_booking_window", Integer.class));
            // Envelope: lead 0..1440 minutes, maxDays 1..90 days.
            for (int[] bad : List.of(new int[] {-1, 7}, new int[] {1441, 7},
                    new int[] {60, 0}, new int[] {60, 91})) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> app.commands.replaceBookingWindow(
                                replaceBookingWindow("3", bad[0], bad[1]))).code());
            }
            assertEquals(45, db.jdbc.queryForObject(
                    "SELECT lead_minutes FROM merchant_schedule_booking_window", Integer.class));
        }
    }

    private static BatchGenerateCommand generate(String requestId, LocalDate fromDate,
            LocalDate toDate, LocalTime dailyStart, LocalTime dailyEnd, int intervalMinutes,
            int configuredCapacity, String reason) {
        return new BatchGenerateCommand(
                new CommandContext(requestId, "sch004-test", OperatorType.USER, "601", "test"),
                "10", "201", fromDate, toDate, dailyStart, dailyEnd, intervalMinutes,
                configuredCapacity, reason);
    }

    private static BatchGenerateCommand generate(String requestId, LocalDate fromDate,
            LocalDate toDate, LocalTime dailyStart, LocalTime dailyEnd, int intervalMinutes,
            int configuredCapacity) {
        return generate(requestId, fromDate, toDate, dailyStart, dailyEnd, intervalMinutes,
                configuredCapacity, "模板生成");
    }

    private static BookingWindowReplaceCommand replaceBookingWindow(String expectedVersion,
            int leadMinutes, int maxDays) {
        return new BookingWindowReplaceCommand(ctx(), "10", "201", leadMinutes, maxDays,
                expectedVersion);
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
                "10", "201", start, end, 2);
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

            @Override
            public com.petplatform.service.api.dto.ServiceSnapshotPageDTO searchVisibleServices(
                    com.petplatform.service.api.query.CatalogServiceSearchQuery query) {
                throw new UnsupportedOperationException("catalog search not used by this slice");
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
            // Same local-MySQL harness contract as the sibling schedule tests
            // (ReservationHold/ScheduleProtectionFoundation): whichever <prefix>_MYSQL_URL the
            // environment defines wins, user/password follow the same prefix, and the no-env
            // defaults only ever target a local root with no password assumption.
            String prefix = System.getenv().containsKey("SCH004_MYSQL_URL") ? "SCH004"
                    : System.getenv().containsKey("SCH003_MYSQL_URL") ? "SCH003"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "SCH004";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(
                        prefix + "_MYSQL_URL must target a local root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
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
                        "53-Schedule-Write-Schema-v0.1.sql")) {
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
            // CCR-SCH-SLOT-002: the seeded hold uses a far-future (2030) window against the
            // 2026 fixed clock, so the store carries a permissive configured bookable range
            // (default 60/7 behaviour is covered in ReservationHoldMySqlTest).
            jdbc.update("INSERT INTO merchant_schedule_booking_window(store_id,lead_minutes,"
                    + "max_days,version,created_at,updated_at) VALUES(201,0,999999,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            // CCR-SCH-SLOT-001: store-wide unified slot (no service_id/window_kind columns).
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "start_at,end_at,configured_capacity,status,version,created_at,"
                    + "updated_at) VALUES(101,10,201,'2030-01-01 01:00:00',"
                    + "'2030-01-01 02:00:00',2,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
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
            hold(app, orderId, S, OffsetDateTime.parse("2030-01-01T01:30:00Z"));
        }

        /** Same hold on window 101 with a caller-chosen appointment interval. */
        private void hold(Components app, String orderId, OffsetDateTime start,
                OffsetDateTime end) {
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
                        "201", "301", "IN_STORE", start, end, null, null, "101",
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
