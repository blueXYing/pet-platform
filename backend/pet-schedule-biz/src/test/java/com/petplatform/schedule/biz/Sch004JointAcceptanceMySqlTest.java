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
import com.petplatform.merchant.api.dto.StoreStaffFactsDTO;
import com.petplatform.merchant.api.query.MerchantAdmissionQuery;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.merchant.api.query.MerchantMembershipQuery;
import com.petplatform.merchant.api.query.MerchantStoreStaffFactsApi;
import com.petplatform.merchant.api.query.StoreStaffFactsQuery;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderExpiryFactsApi;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.command.ReservationConfirmApi;
import com.petplatform.schedule.api.dto.AvailabilityPageDTO;
import com.petplatform.schedule.api.dto.AvailabilityWindowDTO;
import com.petplatform.schedule.api.dto.ReservationConfirmTypes.ConfirmReservationCommand;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.StaffWindowItem;
import com.petplatform.schedule.api.dto.ScheduleWorkbenchTypes.WindowItem;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityQuery;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CapabilityView;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateStaffWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.CreateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.ReplaceCapabilitiesCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowOpenCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.StaffWindowResult;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.UpdateWindowCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowCloseCommand;
import com.petplatform.schedule.api.dto.ScheduleWriteTypes.WindowResult;
import com.petplatform.schedule.api.dto.SelectionWindowDTO;
import com.petplatform.schedule.api.dto.SelectionWindowPageDTO;
import com.petplatform.schedule.api.error.ScheduleWriteApiCodes;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.api.query.AvailabilityQuery;
import com.petplatform.schedule.api.query.ScheduleMerchantQueryApi;
import com.petplatform.schedule.api.query.SelectionWindowQuery;
import com.petplatform.schedule.api.query.WorkbenchStaffWindowQuery;
import com.petplatform.schedule.api.query.WorkbenchWindowQuery;
import com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationRefundReleaseApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandService;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleQueryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleSelectionQueryApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.schedule.biz.infrastructure.persistence.ScheduleWriteStore;
import com.petplatform.schedule.biz.infrastructure.provider.ScheduleQualifiedStaffFactsProvider;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.dto.ServiceSnapshotPageDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.api.query.ServiceBookabilityQuery;
import com.petplatform.service.api.query.ServiceQueryApi;
import com.petplatform.service.api.query.ServiceSnapshotQuery;
import com.petplatform.service.api.query.StoreServiceSnapshotQuery;
import java.math.BigDecimal;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * SCH-004 specialised acceptance (issue acceptance walk-through on real MySQL, Contract53).
 * One harness drives the full maintenance chain end to end: merchant writes (service windows,
 * staff availability, capability sets) drive the C-side minute-granularity bookability facts
 * (API07 6.1/6.3 + the original-window selection projection), the system-derived SOLD_OUT state
 * reacts to occupancy and releases, staff schedule/capability edits move the effective capacity,
 * and the validation chain (kind x fulfillment matrix, pickup+120, overlap, half-open
 * boundaries, requestId idempotency) holds on both the write and the hold path.
 *
 * <p>Unlike the sibling contract tests, the schedule rows are not SQL-seeded: every window,
 * staff window and capability row in these scenarios is created through the merchant write
 * commands, so the acceptance proves the maintained state is exactly what consumers read.
 */
class Sch004JointAcceptanceMySqlTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:34:56.789Z");
    private static final String STORE = "201";
    private static final String SERVICE = "301";
    private static final String PICKUP_SERVICE = "302";
    private static final String MERCHANT = "10";
    private static final LocalDate DAY = LocalDate.parse("2030-01-01");
    private static final OffsetDateTime AT_1000 = OffsetDateTime.parse("2030-01-01T10:00:00Z");
    private static final OffsetDateTime AT_1030 = OffsetDateTime.parse("2030-01-01T10:30:00Z");
    private static final OffsetDateTime AT_1040 = OffsetDateTime.parse("2030-01-01T10:40:00Z");
    private static final OffsetDateTime AT_1100 = OffsetDateTime.parse("2030-01-01T11:00:00Z");
    private static final OffsetDateTime AT_1130 = OffsetDateTime.parse("2030-01-01T11:30:00Z");
    private static final OffsetDateTime AT_1140 = OffsetDateTime.parse("2030-01-01T11:40:00Z");
    private static final OffsetDateTime AT_1200 = OffsetDateTime.parse("2030-01-01T12:00:00Z");
    private static final OffsetDateTime AT_1230 = OffsetDateTime.parse("2030-01-01T12:30:00Z");
    private static final OffsetDateTime AT_1300 = OffsetDateTime.parse("2030-01-01T13:00:00Z");
    private static final OffsetDateTime AT_1359 = OffsetDateTime.parse("2030-01-01T13:59:00Z");
    private static final OffsetDateTime AT_1400 = OffsetDateTime.parse("2030-01-01T14:00:00Z");
    private static final OffsetDateTime AT_1500 = OffsetDateTime.parse("2030-01-01T15:00:00Z");
    private static final OffsetDateTime AT_1600 = OffsetDateTime.parse("2030-01-01T16:00:00Z");

    @Test
    void maintainedWindowsDriveMinuteGranularityBookabilityThroughStaffReadiness()
            throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            // Merchant maintains an odd-minute window pair: 10:07-10:43 and the directly
            // adjacent 10:43-11:00 (half-open adjacency is legal, no fixed 60-minute slots).
            WindowResult first = app.createWindow(SERVICE, "GENERAL", minute(10, 7),
                    minute(10, 43), 1);
            WindowResult second = app.createWindow(SERVICE, "GENERAL", minute(10, 43),
                    AT_1100, 1);
            assertEquals("OPEN", first.status());
            assertEquals("0", first.version());
            // An intersecting third window is rejected while the adjacent one was accepted.
            ApiException overlap = assertThrows(ApiException.class,
                    () -> app.createWindow(SERVICE, "GENERAL", AT_1030, AT_1130, 1));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP, overlap.code());
            // C-side reads the exact minute boundaries back, but no staff is scheduled yet:
            // the effective capacity stays 0 and nothing is bookable.
            AvailabilityPageDTO before = app.availability(SERVICE, DAY, DAY);
            assertEquals(2, before.items().size());
            assertMinuteBounds(before);
            for (AvailabilityWindowDTO item : before.items()) {
                assertEquals(0, item.qualifiedAvailableStaffCount());
                assertEquals(0, item.effectiveCapacity());
                assertFalse(item.available());
            }
            // Merchant schedules both employees over the interval and grants the capability;
            // the C-side effective capacity becomes min(configured=1, qualified staff=2)=1.
            app.createStaffWindow("401", AT_1000, AT_1100);
            app.createStaffWindow("402", AT_1000, AT_1100);
            app.putCapabilities("401", "0", List.of(SERVICE));
            app.putCapabilities("402", "0", List.of(SERVICE));
            AvailabilityPageDTO ready = app.availability(SERVICE, DAY, DAY);
            for (AvailabilityWindowDTO item : ready.items()) {
                assertEquals(2, item.qualifiedAvailableStaffCount());
                assertEquals(1, item.effectiveCapacity());
                assertEquals(1, item.remainingCapacity());
                assertTrue(item.available());
            }
            // Minute-level booking inside the first window: 10:15-10:22 (no fixed slots).
            HoldResult held = app.hold("501", first.windowId(), minute(10, 15), minute(10, 22));
            assertEquals(1, held.claims().size());
            AvailabilityPageDTO booked = app.availability(SERVICE, DAY, DAY);
            assertEquals(1, booked.items().get(0).occupiedCount());
            assertEquals(0, booked.items().get(0).remainingCapacity());
            assertFalse(booked.items().get(0).available());
            assertEquals(1, booked.items().get(1).remainingCapacity());
            // The workbench lists the filled window with the derived state and a bumped
            // version; the hold expiry release restores OPEN and the C-side remaining.
            assertEquals("SOLD_OUT", app.workbenchWindow(first.windowId()).status());
            assertEquals("1", app.workbenchWindow(first.windowId()).version());
            app.expire(held);
            assertEquals("OPEN", app.workbenchWindow(first.windowId()).status());
            assertEquals(1, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
        }
    }

    @Test
    void soldOutDerivationAndCapacityEditsReflectForConsumers() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            app.createWindow(SERVICE, "GENERAL", AT_1000, AT_1100, 2);
            String windowId = app.workbenchWindows(null).get(0).windowId();
            app.createStaffWindow("401", AT_1000, AT_1100);
            app.createStaffWindow("402", AT_1000, AT_1100);
            app.putCapabilities("401", "0", List.of(SERVICE));
            app.putCapabilities("402", "0", List.of(SERVICE));
            // Two claims fill capacity 2: the window derives SOLD_OUT inside the hold
            // transactions and every consumer sees remaining=0.
            HoldResult first = app.hold("511", windowId, AT_1000, AT_1030);
            HoldResult second = app.hold("512", windowId, AT_1000, AT_1040);
            assertEquals("SOLD_OUT", app.workbenchWindow(windowId).status());
            AvailabilityPageDTO page = app.availability(SERVICE, DAY, DAY);
            assertEquals(0, page.items().get(0).remainingCapacity());
            assertFalse(page.items().get(0).available());
            SelectionWindowPageDTO selection = app.selection(SERVICE, DAY, DAY, "GENERAL");
            assertEquals(1, selection.items().size());
            assertEquals(0, selection.items().get(0).remainingCapacity());
            assertFalse(selection.items().get(0).available());
            // The workbench status filter separates the derived state from OPEN.
            assertEquals(1, app.workbenchWindows("SOLD_OUT").size());
            assertTrue(app.workbenchWindows("OPEN").isEmpty());
            // Occupied windows cannot close, move or lose capacity (SCHW-D4).
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    app.closeFailure(windowId));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    app.moveFailure(windowId));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_STATE_NOT_ALLOWED,
                    app.shrinkFailure(windowId));
            // Raising passes and the same transaction re-derives the state from occupancy;
            // the remaining stays staff-capped at 0 while both claims hold.
            WindowResult raised = app.raiseCapacity(windowId, 3);
            assertEquals("OPEN", raised.status());
            assertEquals(0, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            // Releases restore capacity: expiry then refund release both re-derive.
            app.expire(first);
            assertEquals(1, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            app.confirm(second);
            app.release(second);
            assertEquals("OPEN", app.workbenchWindow(windowId).status());
            assertEquals(2, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            // A capacity-1 window goes SOLD_OUT with one claim and still blocks overlapping
            // creations: SOLD_OUT keeps the open slot exactly like an OPEN window (§3.1).
            WindowResult tight = app.createWindow(SERVICE, "GENERAL", AT_1130, AT_1200, 1);
            app.createStaffWindow("401", AT_1130, AT_1200);
            app.createStaffWindow("402", AT_1130, AT_1200);
            HoldResult third = app.hold("513", tight.windowId(), AT_1130, AT_1200);
            assertEquals("SOLD_OUT", app.workbenchWindow(tight.windowId()).status());
            ApiException blocked = assertThrows(ApiException.class,
                    () -> app.createWindow(SERVICE, "GENERAL", AT_1140, AT_1230, 1));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP, blocked.code());
            // After the release the window is OPEN again and the overlapping creation is
            // still rejected: the half-open overlap rule is state independent.
            app.expire(third);
            ApiException stillBlocked = assertThrows(ApiException.class,
                    () -> app.createWindow(SERVICE, "GENERAL", AT_1140, AT_1230, 1));
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP, stillBlocked.code());
        }
    }

    @Test
    void staffScheduleAndCapabilityEditsChangeBookabilityWithReductionProtection()
            throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            app.createWindow(SERVICE, "GENERAL", AT_1000, AT_1100, 2);
            String windowId = app.workbenchWindows(null).get(0).windowId();
            String staffWindow401 = app.createStaffWindow("401", AT_1000, AT_1100).windowId();
            String staffWindow402 = app.createStaffWindow("402", AT_1000, AT_1100).windowId();
            app.putCapabilities("401", "0", List.of(SERVICE));
            app.putCapabilities("402", "0", List.of(SERVICE));
            assertEquals(2, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            // Closing one employee's schedule halves the qualified staff and the C-side
            // effective capacity follows (min of configured and qualified staff).
            app.closeStaffWindow("402", staffWindow402, "402 休息");
            AvailabilityPageDTO halved = app.availability(SERVICE, DAY, DAY);
            assertEquals(1, halved.items().get(0).qualifiedAvailableStaffCount());
            assertEquals(1, halved.items().get(0).effectiveCapacity());
            assertEquals(1, halved.items().get(0).remainingCapacity());
            // Reopening restores the capacity (widening edits need no reduction protection).
            app.openStaffWindow("402", staffWindow402);
            assertEquals(2, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            // The ORDER protected-assignment gate blocks availability reductions for a
            // staff member with a protectRequired assignment (36号), then passes once clear.
            app.protect("401");
            ApiException protectedBlock = assertThrows(ApiException.class,
                    () -> app.closeStaffWindow("401", staffWindow401, "401 请假"));
            assertEquals(CommonApiCodes.CONFLICT, protectedBlock.code());
            app.unprotect("401");
            app.closeStaffWindow("401", staffWindow401, "401 请假");
            assertEquals(1, app.availability(SERVICE, DAY, DAY).items().get(0).remainingCapacity());
            // Capability removal passes the same reduction protection and the C-side
            // qualified staff count follows the maintained capability set.
            String capabilityVersion = app.capabilities("402").version();
            assertEquals("1", capabilityVersion);
            app.putCapabilities("402", capabilityVersion, List.of());
            assertEquals(List.of(), app.capabilities("402").serviceIds());
            assertEquals("2", app.capabilities("402").version());
            AvailabilityPageDTO unqualified = app.availability(SERVICE, DAY, DAY);
            assertEquals(0, unqualified.items().get(0).qualifiedAvailableStaffCount());
            assertEquals(0, unqualified.items().get(0).remainingCapacity());
            assertFalse(unqualified.items().get(0).available());
        }
    }

    @Test
    void validationChainFulfillmentKindPickupReturnBoundsAndIdempotency() throws Exception {
        try (Database db = new Database()) {
            App app = new App(db);
            // Kind x fulfillment matrix (SCHW-D2): IN_STORE maintains GENERAL only,
            // PICKUP_DELIVERY maintains PICKUP/RETURN only.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, app.createFailure(SERVICE, "PICKUP"));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    app.createFailure(PICKUP_SERVICE, "GENERAL"));
            // Minute precision and non-degenerate intervals are enforced on the write path.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, app.createFailure(
                    SERVICE, "GENERAL", OffsetDateTime.parse("2030-01-01T10:00:30Z"), AT_1100));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    app.createFailure(SERVICE, "GENERAL", AT_1100, AT_1000));
            // Pickup/return windows for the delivery service; the return window starts at
            // pickup start + 120 minutes exactly (the legal boundary).
            String pickup = app.createWindow(PICKUP_SERVICE, "PICKUP", AT_1200, AT_1230, 1)
                    .windowId();
            String returning = app.createWindow(PICKUP_SERVICE, "RETURN", AT_1400, AT_1500, 1)
                    .windowId();
            // Staff readiness for the delivery service: both employees scheduled over both
            // claim intervals and authorised on the service, so the holds can prove capacity.
            app.createStaffWindow("401", AT_1200, AT_1400);
            app.createStaffWindow("401", AT_1400, AT_1500);
            app.createStaffWindow("402", AT_1200, AT_1400);
            app.createStaffWindow("402", AT_1400, AT_1500);
            app.putCapabilities("401", "0", List.of(PICKUP_SERVICE));
            app.putCapabilities("402", "0", List.of(PICKUP_SERVICE));
            // Hold-level +120 chain: a returnStart before pickupStart+120 is a 400, no
            // matter how the windows themselves are shaped.
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    app.pickupHoldFailure("521", pickup, returning, AT_1200, AT_1359));
            // Exactly +120 passes and books both complete original windows as two claims.
            HoldResult exact = app.pickupHold("523", pickup, returning, AT_1200, AT_1400);
            assertEquals(2, exact.claims().size());
            app.confirm(exact);
            app.release(exact);
            // Staff windows follow the same half-open overlap rule; adjacency is legal.
            assertEquals(ScheduleWriteApiCodes.SCHEDULE_WINDOW_OVERLAP,
                    app.staffWindowFailure("401", AT_1300, AT_1500));
            app.createStaffWindow("401", AT_1500, AT_1600);
            assertEquals(3, app.staffWindows().size());
            // 23号 idempotency: the same requestId replays the first receipt without a
            // second row; the same requestId with different parameters is a 409 conflict.
            String requestId = UUID.randomUUID().toString();
            WindowResult[] created = new WindowResult[1];
            assertTrue(app.commands.createWindowOutcome(new CreateWindowCommand(
                    new CommandContext(requestId, "sch004-acc", OperatorType.USER, "601",
                            "test"),
                    MERCHANT, STORE, SERVICE, "GENERAL", AT_1000, AT_1100, 4), created));
            assertEquals("OPEN", created[0].status());
            assertEquals(1, db.countServiceWindows(SERVICE));
            WindowResult[] replay = new WindowResult[1];
            assertFalse(app.commands.createWindowOutcome(new CreateWindowCommand(
                    new CommandContext(requestId, "sch004-acc", OperatorType.USER, "601",
                            "test"),
                    MERCHANT, STORE, SERVICE, "GENERAL", AT_1000, AT_1100, 4), replay));
            assertEquals(created[0].windowId(), replay[0].windowId());
            assertEquals(1, db.countServiceWindows(SERVICE));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
                    app.createFailureWithKey(requestId, AT_1030, AT_1100));
            assertEquals(1, db.countServiceWindows(SERVICE));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static OffsetDateTime minute(int hour, int minute) {
        return OffsetDateTime.parse(String.format("2030-01-01T%02d:%02d:00Z", hour, minute));
    }

    /** The two maintained windows must survive the round trip at their exact minute bounds. */
    private static void assertMinuteBounds(AvailabilityPageDTO page) {
        String bounds = page.items().stream()
                .map(item -> item.start() + ".." + item.end() + ";")
                .reduce("", String::concat);
        assertEquals("2030-01-01T10:07Z..2030-01-01T10:43Z;"
                + "2030-01-01T10:43Z..2030-01-01T11:00Z;", bounds,
                "minute-level window boundaries must survive the round trip");
    }

    private static SnowflakeIdGenerator seq(long start) {
        AtomicLong sequence = new AtomicLong(start);
        return sequence::incrementAndGet;
    }

    private static CommandContext ctx() {
        return new CommandContext(UUID.randomUUID().toString(), "sch004-acc",
                OperatorType.USER, "601", "test");
    }

    private static QueryContext query() {
        return new QueryContext("sch004-acc", OperatorType.USER, "601");
    }

    /** One assembled application: merchant writes, reservation lifecycle and consumer reads. */
    private static final class App {
        private final Database db;
        private final ScheduleCapacityGuardApiImpl guard;
        private final ScheduleMerchantCommandApiImpl commands;
        private final ScheduleMerchantQueryApi workbench;
        private final ScheduleQueryApiImpl availabilityApi;
        private final ScheduleSelectionQueryApiImpl selectionApi;
        private final ReservationHoldApiImpl holds;
        private final ReservationConfirmApiImpl confirms;
        private final ReservationExpiryApiImpl expiry;
        private final ReservationRefundReleaseApiImpl releases;
        private final FakeOrders orders;
        private final FakePayments payments = new FakePayments();
        private final TransactionTemplate tx;

        private App(Database db) {
            this.db = db;
            guard = new ScheduleCapacityGuardApiImpl(db.source);
            ScheduleProtectionFactsApi facts = new ScheduleProtectionFactsApiImpl(db.source,
                    guard);
            FakeMerchant merchant = new FakeMerchant();
            orders = new FakeOrders(db);
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
                    FulfillmentType type = PICKUP_SERVICE.equals(query.serviceId())
                            ? FulfillmentType.PICKUP_DELIVERY : FulfillmentType.IN_STORE;
                    return new ServiceSnapshotDTO(query.serviceId(), MERCHANT, STORE, "洗澡",
                            "cat", "洗护", null, 30, type, null, null, null, null);
                }

                @Override
                public ServiceSnapshotPageDTO getStoreServiceSnapshots(
                        StoreServiceSnapshotQuery query) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ServiceBookabilityDTO checkBookable(ServiceBookabilityQuery query) {
                    return new ServiceBookabilityDTO(query.serviceId(), MERCHANT, STORE, true,
                            List.of());
                }

                @Override
                public ServiceSnapshotDTO getVisibleService(ServiceSnapshotQuery query) {
                    return getServiceSnapshot(query);
                }

            @Override
            public com.petplatform.service.api.dto.ServiceSnapshotPageDTO searchVisibleServices(
                    com.petplatform.service.api.query.CatalogServiceSearchQuery query) {
                throw new UnsupportedOperationException("catalog search not used by this slice");
            }
            };
            ScheduleCapacityProofApiImpl proof = new ScheduleCapacityProofApiImpl(db.source,
                    guard, facts, merchant, orders, Clock.fixed(NOW, ZoneOffset.UTC), 1000);
            ScheduleAdmissionGate gate = new ScheduleAdmissionGate(admission);
            ScheduleWriteStore store = new ScheduleWriteStore(db.source, seq(15000));
            commands = new ScheduleMerchantCommandApiImpl(new ScheduleMerchantCommandService(
                    store, gate, guard, facts, merchant, orders, services, proof,
                    Clock.fixed(NOW, ZoneOffset.UTC)));
            workbench = new ScheduleMerchantQueryApiImpl(db.source, gate);
            ScheduleQualifiedStaffFactsProvider qualified =
                    new ScheduleQualifiedStaffFactsProvider(new FakeStoreStaff(), db.source);
            availabilityApi = new ScheduleQueryApiImpl(db.source, services, qualified);
            selectionApi = new ScheduleSelectionQueryApiImpl(db.source, services, qualified);
            holds = new ReservationHoldApiImpl(db.source, seq(12000), guard, facts, proof,
                    orders, Clock.fixed(NOW, ZoneOffset.UTC));
            confirms = new ReservationConfirmApiImpl(db.source, seq(12500), guard, facts,
                    payments);
            expiry = new ReservationExpiryApiImpl(db.source, seq(13000), guard,
                    new FakeExpiry(), facts);
            releases = new ReservationRefundReleaseApiImpl(db.source, seq(14000), guard,
                    new FakeRefunds());
            tx = new TransactionTemplate(new DataSourceTransactionManager(db.source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(30);
        }

        // writes

        private WindowResult createWindow(String service, String kind, OffsetDateTime start,
                OffsetDateTime end, int capacity) {
            WindowResult[] out = new WindowResult[1];
            assertTrue(commands.createWindowOutcome(new CreateWindowCommand(ctx(), MERCHANT,
                    STORE, service, kind, start, end, capacity), out));
            return out[0];
        }

        private String createFailure(String service, String kind) {
            return createFailure(service, kind, AT_1000, AT_1100);
        }

        private String createFailure(String service, String kind, OffsetDateTime start,
                OffsetDateTime end) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> createWindow(service, kind, start, end, 1));
            return failure.code();
        }

        private String createFailureWithKey(String requestId, OffsetDateTime start,
                OffsetDateTime end) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> commands.createWindowOutcome(new CreateWindowCommand(
                            new CommandContext(requestId, "sch004-acc", OperatorType.USER,
                                    "601", "test"),
                            MERCHANT, STORE, SERVICE, "GENERAL", start, end, 4),
                            new WindowResult[1]));
            return failure.code();
        }

        private StaffWindowResult createStaffWindow(String staffId, OffsetDateTime start,
                OffsetDateTime end) {
            StaffWindowResult[] out = new StaffWindowResult[1];
            assertTrue(commands.createStaffWindowOutcome(new CreateStaffWindowCommand(ctx(),
                    MERCHANT, STORE, staffId, start, end), out));
            return out[0];
        }

        private String staffWindowFailure(String staffId, OffsetDateTime start,
                OffsetDateTime end) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> createStaffWindow(staffId, start, end));
            return failure.code();
        }

        private void putCapabilities(String staffId, String expectedVersion,
                List<String> serviceIds) {
            commands.replaceCapabilities(new ReplaceCapabilitiesCommand(ctx(), MERCHANT, STORE,
                    staffId, serviceIds, expectedVersion,
                    serviceIds.isEmpty() ? "撤销授权" : null));
        }

        private CapabilityView capabilities(String staffId) {
            return commands.getCapabilities(new CapabilityQuery(ctx(), MERCHANT, STORE,
                    staffId));
        }

        private void closeStaffWindow(String staffId, String windowId, String reason) {
            commands.closeStaffWindow(new StaffWindowCloseCommand(ctx(), windowId, MERCHANT,
                    STORE, staffId, staffWindowVersion(windowId), reason));
        }

        private void openStaffWindow(String staffId, String windowId) {
            commands.openStaffWindow(new StaffWindowOpenCommand(ctx(), windowId, MERCHANT,
                    STORE, staffId, staffWindowVersion(windowId)));
        }

        private String closeFailure(String windowId) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> commands.closeWindow(new WindowCloseCommand(ctx(), windowId,
                            MERCHANT, STORE, windowVersion(windowId), "临时停业")));
            return failure.code();
        }

        private String moveFailure(String windowId) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> commands.updateWindow(new UpdateWindowCommand(ctx(), windowId,
                            MERCHANT, STORE, AT_1030, AT_1130, null, windowVersion(windowId),
                            null)));
            return failure.code();
        }

        private String shrinkFailure(String windowId) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> commands.updateWindow(new UpdateWindowCommand(ctx(), windowId,
                            MERCHANT, STORE, AT_1000, AT_1100, 1, windowVersion(windowId),
                            null)));
            return failure.code();
        }

        private WindowResult raiseCapacity(String windowId, int capacity) {
            return commands.updateWindow(new UpdateWindowCommand(ctx(), windowId, MERCHANT,
                    STORE, AT_1000, AT_1100, capacity, windowVersion(windowId), null));
        }

        // reads

        private List<WindowItem> workbenchWindows(String status) {
            return workbench.listWindows(new WorkbenchWindowQuery(MERCHANT, STORE, null, null,
                    status, null, null, query())).items();
        }

        private WindowItem workbenchWindow(String windowId) {
            return workbenchWindows(null).stream()
                    .filter(item -> item.windowId().equals(windowId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("window missing in workbench"));
        }

        private AvailabilityPageDTO availability(String service, LocalDate from, LocalDate to) {
            return availabilityApi.queryAvailability(new AvailabilityQuery(service, STORE,
                    from, to, query()));
        }

        private SelectionWindowPageDTO selection(String service, LocalDate from, LocalDate to,
                String kind) {
            return selectionApi.querySelectableWindows(new SelectionWindowQuery(service, STORE,
                    from, to, kind, query()));
        }

        private List<StaffWindowItem> staffWindows() {
            return workbench.listStaffWindows(new WorkbenchStaffWindowQuery(MERCHANT, STORE,
                    "401", query())).items();
        }

        // reservation lifecycle

        private HoldResult hold(String orderId, String windowId, OffsetDateTime start,
                OffsetDateTime end) {
            HoldResult result = tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                HoldResult held = holds.hold(new HoldCommand(ctx(), orderId, "601", MERCHANT,
                        STORE, SERVICE, "IN_STORE", start, end, null, null, windowId, null,
                        null));
                insertOrder(held, SERVICE, "IN_STORE", start, end);
                return held;
            });
            if (result == null) throw new IllegalStateException("hold failed");
            return result;
        }

        private String pickupHoldFailure(String orderId, String pickupWindowId,
                String returnWindowId, OffsetDateTime pickupStart, OffsetDateTime returnStart) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> pickupHold(orderId, pickupWindowId, returnWindowId, pickupStart,
                            returnStart));
            return failure.code();
        }

        private HoldResult pickupHold(String orderId, String pickupWindowId,
                String returnWindowId, OffsetDateTime pickupStart, OffsetDateTime returnStart) {
            HoldResult result = tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                HoldResult held = holds.hold(new HoldCommand(ctx(), orderId, "601", MERCHANT,
                        STORE, PICKUP_SERVICE, "PICKUP_DELIVERY", null, null, pickupStart,
                        returnStart, null, pickupWindowId, returnWindowId));
                insertOrder(held, PICKUP_SERVICE, "PICKUP_DELIVERY", pickupStart, returnStart);
                return held;
            });
            if (result == null) throw new IllegalStateException("pickup hold failed");
            return result;
        }

        /** Payment confirmation precedes a refund release: only CONFIRMED claims release. */
        private void confirm(HoldResult held) {
            payments.proven.add(held.reservationId());
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                confirms.confirm(new ConfirmReservationCommand(
                        new CommandContext("EVENT:PAYMENT_SUCCEEDED:" + held.orderId() + ":"
                                + held.reservationId(), "sch004-acc", OperatorType.SYSTEM, null,
                                "OUTBOX"),
                        held.orderId(), held.reservationId(), STORE, 0, held.holdExpireAt()));
                return null;
            });
        }

        private void expire(HoldResult held) {
            OffsetDateTime observedNow = OffsetDateTime.now(ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                // The expiry worker pins the requestId to the reservation and attempt zero
                // (durable-task idempotency), mirrored here exactly.
                expiry.expire(new ExpireHoldCommand(new CommandContext("TASK:RESERVATION_HOLD_"
                                + "EXPIRE:" + held.reservationId() + ":0", "sch004-acc",
                        OperatorType.SYSTEM, null, "OUTBOX"),
                        held.orderId(), held.reservationId(), STORE, 0, held.holdExpireAt(),
                        observedNow));
                return null;
            });
        }

        private void release(HoldResult held) {
            tx.execute(status -> {
                guard.acquire(List.of(STORE), query());
                releases.release(held.orderId(), held.reservationId(), STORE,
                        "R" + held.orderId(),
                        new QueryContext("sch004-acc", OperatorType.SYSTEM, null));
                return null;
            });
        }

        private void insertOrder(HoldResult held, String service, String fulfillment,
                OffsetDateTime start, OffsetDateTime end) {
            db.jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,"
                    + "store_id,service_id,pet_id,reservation_id,order_stage,payment_status,"
                    + "verification_status,fulfillment_type,original_amount,discount_amount,"
                    + "pay_amount,refunded_amount,appointment_start_at,appointment_end_at,"
                    + "created_at,updated_at) VALUES(?,?,601,10,201,?,701,?,"
                    + "'PENDING_PAYMENT','INIT','UNVERIFIED',?,100.00,0.00,100.00,"
                    + "0.00,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    Long.parseLong(held.orderId()), Long.parseLong(held.orderId()),
                    Long.parseLong(service), Long.parseLong(held.reservationId()), fulfillment,
                    java.sql.Timestamp.from(start.toInstant()),
                    java.sql.Timestamp.from(end.toInstant()));
        }

        private void protect(String staffId) {
            orders.protectedStaff.add(staffId);
        }

        private void unprotect(String staffId) {
            orders.protectedStaff.remove(staffId);
        }

        private long windowVersion(String windowId) {
            return Long.parseLong(db.windowVersion(windowId));
        }

        private long staffWindowVersion(String windowId) {
            return Long.parseLong(db.staffWindowVersion(windowId));
        }
    }

    private static final class FakeMerchant implements MerchantCurrentStaffFactsApi {
        @Override
        public CurrentStoreStaffFacts readStore(String storeId, QueryContext context) {
            return new CurrentStoreStaffFacts(MERCHANT, storeId, true, List.of(
                    new CurrentStaffFact("401", MERCHANT, storeId, "ACTIVE", true, "0"),
                    new CurrentStaffFact("402", MERCHANT, storeId, "ACTIVE", true, "0")));
        }
    }

    private static final class FakeStoreStaff implements MerchantStoreStaffFactsApi {
        @Override
        public StoreStaffFactsDTO listActiveStoreStaffFacts(StoreStaffFactsQuery query) {
            return new StoreStaffFactsDTO(query.storeId(), List.of("401", "402"));
        }
    }

    private static final class FakeOrders implements OrderProtectionFactsApi {
        private final Database db;
        private final Set<String> protectedStaff = new HashSet<>();

        private FakeOrders(Database db) {
            this.db = db;
        }

        @Override
        public OrderProtectionSnapshot readStore(String store, QueryContext query) {
            return snapshot(store, null);
        }

        @Override
        public OrderProtectionSnapshot getByReservations(String store,
                List<String> reservationIds, QueryContext query) {
            List<OrderProtectionFact> found = new ArrayList<>();
            for (String reservationId : reservationIds) {
                found.addAll(rows(store, reservationId));
            }
            return new OrderProtectionSnapshot(store, true, found.size(),
                    (int) found.stream().filter(fact -> fact.currentStaffId() != null).count(),
                    found);
        }

        @Override
        public OrderProtectionSnapshot getCurrentAssignments(String store,
                List<String> staffIds, QueryContext query) {
            List<OrderProtectionFact> found = new ArrayList<>();
            for (String staffId : staffIds) {
                if (protectedStaff.contains(staffId)) {
                    found.add(new OrderProtectionFact("900", "900", "601", MERCHANT, store,
                            SERVICE, "IN_STORE", staffId, "CONFIRMED", "UNVERIFIED", "1",
                            "9001", "1", true));
                }
            }
            return new OrderProtectionSnapshot(store, true, found.size(),
                    (int) found.stream().filter(fact -> fact.currentStaffId() != null).count(),
                    found);
        }

        private OrderProtectionSnapshot snapshot(String store, String reservationId) {
            List<OrderProtectionFact> found = rows(store, reservationId);
            return new OrderProtectionSnapshot(store, true, found.size(),
                    (int) found.stream().filter(fact -> fact.currentStaffId() != null).count(),
                    found);
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
    }

    private static final class FakePayments implements OrderPaymentFactsApi {
        private final List<String> proven = new ArrayList<>();

        @Override
        public void requirePayableForPreparation(String orderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String locateStore(String orderId, QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderPaymentFact readForPayment(String orderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void assertPaymentCommitted(String orderId, String reservationId, String storeId,
                QueryContext context) {
            if (!proven.contains(reservationId)) {
                throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                        "ORDER payment proof absent");
            }
        }
    }

    private static final class FakeExpiry implements OrderExpiryFactsApi {
        @Override
        public void assertExpiryCommitted(String orderId, String reservationId, String storeId,
                QueryContext context) {
            // The ORDER side of the expiry proof is not this acceptance's subject.
        }
    }

    private static final class FakeRefunds implements RefundExecutionFactsApi {
        @Override
        public RefundExecutionFact requireForChannel(String refundOrderId, String storeId,
                QueryContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundSuccessFact requireSucceeded(String refundOrderId, String orderId,
                String storeId, QueryContext context) {
            return new RefundSuccessFact(refundOrderId, "R" + refundOrderId, orderId, "9",
                    storeId, new BigDecimal("100.00"), new BigDecimal("100.00"), "CH1",
                    OffsetDateTime.parse("2026-09-27T13:00:00Z"), "E1", "MERCHANT_APPROVED",
                    "FULL");
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "sch004acc_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            // Same local-MySQL harness contract as the sibling schedule tests: the
            // <prefix>_MYSQL_URL environment wins, defaults target a local root only.
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
                        "13-Async-Infra-Schema-v0.1.sql",
                        "14-Command-Idempotency-Schema-v0.1.sql",
                        "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
                        "38-Booking-Create-Schema-v0.1.sql",
                        "39-Booking-Expiry-Schema-v0.1.sql",
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

        private int countServiceWindows(String serviceId) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_availability_window WHERE service_id=?",
                    Integer.class, Long.parseLong(serviceId));
        }

        private String windowVersion(String windowId) {
            return jdbc.queryForObject(
                    "SELECT version FROM schedule_availability_window WHERE id=?", String.class,
                    Long.parseLong(windowId));
        }

        private String staffWindowVersion(String windowId) {
            return jdbc.queryForObject(
                    "SELECT version FROM staff_availability_window WHERE id=?", String.class,
                    Long.parseLong(windowId));
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
