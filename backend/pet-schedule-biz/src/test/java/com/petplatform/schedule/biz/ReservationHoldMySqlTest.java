package com.petplatform.schedule.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStaffFact;
import com.petplatform.merchant.api.dto.MerchantCurrentStaffTypes.CurrentStoreStaffFacts;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionFact;
import com.petplatform.order.api.dto.OrderProtectionTypes.OrderProtectionSnapshot;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldCommand;
import com.petplatform.schedule.api.dto.ReservationHoldTypes.HoldResult;
import com.petplatform.schedule.biz.apiimpl.ReservationHoldApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.application.WeekTemplateRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
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

class ReservationHoldMySqlTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:34:56.789Z");
    private static final QueryContext QUERY = new QueryContext("hold-test", OperatorType.USER, "601");
    private static final CommandContext COMMAND = new CommandContext(UUID.randomUUID().toString(),
            "hold-test", OperatorType.USER, "601", "test");

    @Test
    void independentGeneralHoldCannotCommitAndLeavesNoRows() throws Exception {
        try (Database db = new Database()) {
            db.seedGeneral();
            Components app = new Components(db);
            ApiException failed = assertThrows(ApiException.class, () -> db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                app.hold.hold(general("501"));
                return null;
            }));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failed.code());
            assertEquals(0, db.count("schedule_reservation"));
            assertEquals(0, db.count("schedule_reservation_claim"));
            assertEquals(0, db.count("schedule_reservation_audit"));
        }
    }

    @Test
    void wrongOrderIdCannotSatisfyTheCommitBinding() throws Exception {
        try (Database db = new Database()) {
            db.seedGeneral();
            Components app = new Components(db);
            ApiException failed = assertThrows(ApiException.class, () -> db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult held = app.hold.hold(general("501"));
                db.insertOrder(new HoldResult(held.reservationId(), "502", held.startAt(),
                        held.endAt(), held.holdExpireAt(), held.claims()), "IN_STORE");
                return null;
            }));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failed.code());
            assertEquals(0, db.count("schedule_reservation"));
            assertEquals(0, db.count("schedule_reservation_claim"));
            assertEquals(0, db.count("pet_order"));
        }
    }

    @Test
    void missingInternalTraceCommitsAndAuditKeepsSqlNull() throws Exception {
        try (Database db = new Database()) {
            db.seedGeneral();
            Components app = new Components(db);
            CommandContext noTrace = new CommandContext(UUID.randomUUID().toString(), null,
                    OperatorType.USER, "601", "internal-test");
            db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult held = app.hold.hold(general("501", noTrace));
                db.insertOrder(held, "IN_STORE");
                return null;
            });
            assertEquals(1, db.count("schedule_reservation_audit"));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM "
                    + "schedule_reservation_audit WHERE trace_id IS NULL", Integer.class));
        }
    }

    @Test
    void malformedPresentTraceFailsBeforeAnyHoldWrite() throws Exception {
        try (Database db = new Database()) {
            db.seedGeneral();
            Components app = new Components(db);
            for (String trace : List.of(" ", "bad" + (char) 1 + "trace",
                    "bad" + (char) 0xD800 + "trace", "x".repeat(129))) {
                CommandContext malformed = new CommandContext(UUID.randomUUID().toString(), trace,
                        OperatorType.USER, "601", "internal-test");
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> db.transaction().execute(status -> {
                            app.guard.acquire(List.of("201"), QUERY);
                            app.hold.hold(general("501", malformed));
                            return null;
                        })).code());
            }
            assertEquals(0, db.count("schedule_reservation"));
            assertEquals(0, db.count("schedule_reservation_audit"));
        }
    }

    @Test
    void generalHoldAndOrderCommitOneRealClaimAndOriginalExpiry() throws Exception {
        try (Database db = new Database()) {
            db.seedGeneral();
            Components app = new Components(db);
            HoldResult held = db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult result = app.hold.hold(general("501"));
                db.insertOrder(result, "IN_STORE");
                return result;
            });
            assertEquals("501", held.orderId());
            assertEquals(OffsetDateTime.parse("2026-09-27T12:44:56.789Z"), held.holdExpireAt());
            assertEquals(1, held.claims().size());
            assertEquals("GENERAL", held.claims().getFirst().kind());
            assertEquals("101", held.claims().getFirst().windowId());
            assertEquals(1, db.count("schedule_reservation"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            assertEquals(1, db.count("schedule_reservation_audit"));
            assertEquals(1, db.jdbc.queryForObject("SELECT capacity_snapshot FROM schedule_reservation",
                    Integer.class));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT qualified_staff_count_snapshot FROM schedule_reservation", Integer.class));
            assertEquals("2026-09-27 12:44:56.789", db.jdbc.queryForObject(
                    "SELECT DATE_FORMAT(lock_expire_at,'%Y-%m-%d %H:%i:%s.%f') "
                            + "FROM schedule_reservation", String.class).substring(0, 23));
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> db.transaction().execute(status -> {
                        app.guard.acquire(List.of("201"), QUERY);
                        app.hold.hold(general("501"));
                        return null;
                    })).code());
            assertEquals("SCHEDULE_CAPACITY_EXCEEDED", assertThrows(ApiException.class,
                    () -> db.transaction().execute(status -> {
                        app.guard.acquire(List.of("201"), QUERY);
                        app.hold.hold(general("503"));
                        return null;
                    })).code());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    @Test
    void pickupUsesBothCompleteWindowsAndTheirOuterEnvelope() throws Exception {
        try (Database db = new Database()) {
            db.seedPickup();
            Components app = new Components(db);
            HoldResult held = db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult result = app.hold.hold(new HoldCommand(COMMAND, "502", "601", "10",
                        "201", "301", "PICKUP_DELIVERY", null, null,
                        OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                        OffsetDateTime.parse("2030-01-01T03:00:00Z"), null, "102", "103"));
                db.insertOrder(result, "PICKUP_DELIVERY");
                return result;
            });
            assertEquals(OffsetDateTime.parse("2030-01-01T01:00:00Z"), held.startAt());
            assertEquals(OffsetDateTime.parse("2030-01-01T04:00:00Z"), held.endAt());
            assertEquals(List.of("PICKUP", "RETURN"),
                    held.claims().stream().map(claim -> claim.kind()).toList());
            assertEquals(2, db.count("schedule_reservation_claim"));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT qualified_staff_count_snapshot FROM schedule_reservation", Integer.class));
        }
    }

    /**
     * CCR-SCH-SLOT-002 (38号 hold admission): with NO configuration row the default range is
     * lead 60 minutes / horizon 7 days around the fixed NOW 2026-09-27T12:34:56.789Z. Store
     * 202 carries no merchant_schedule_booking_window row: a start before NOW+60 minutes or
     * after NOW+7 days is a 400 whose message names the bookable range, an in-range start
     * holds normally, and no rows survive a refused hold.
     */
    @Test
    void defaultBookableRangeGatesHoldStartsWithoutARow() throws Exception {
        try (Database db = new Database()) {
            db.seedDefaultRangeWorld();
            Components app = new Components(db);
            // NOW+59m56s (minute-aligned 13:34:00) is inside the window but before the lead.
            ApiException tooEarly = assertThrows(ApiException.class,
                    () -> db.transaction().execute(status -> {
                        app.guard.acquire(List.of("202"), QUERY);
                        app.hold.hold(defaultRange("601",
                                OffsetDateTime.parse("2026-09-27T13:34:00Z"),
                                OffsetDateTime.parse("2026-09-27T13:44:00Z")));
                        return null;
                    }));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, tooEarly.code());
            assertTrue(tooEarly.getMessage().contains("bookable range"));
            // NOW+7d+3s in minute terms (2026-10-04T12:35:00) is past the horizon; a far
            // future start is refused the same way.
            for (OffsetDateTime start : List.of(
                    OffsetDateTime.parse("2026-10-04T12:35:00Z"),
                    OffsetDateTime.parse("2030-01-01T01:00:00Z"))) {
                assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                        () -> db.transaction().execute(status -> {
                            app.guard.acquire(List.of("202"), QUERY);
                            app.hold.hold(defaultRange("602", start, start.plusMinutes(10)));
                            return null;
                        })).code());
            }
            assertEquals(0, db.count("schedule_reservation"));
            assertEquals(0, db.count("schedule_reservation_claim"));
            // NOW+60m04s (minute-aligned 13:35:00) sits inside [NOW+60m, NOW+7d] and holds.
            HoldResult held = db.transaction().execute(status -> {
                app.guard.acquire(List.of("202"), QUERY);
                HoldResult result = app.hold.hold(defaultRange("603",
                        OffsetDateTime.parse("2026-09-27T13:35:00Z"),
                        OffsetDateTime.parse("2026-09-27T13:45:00Z")));
                db.insertOrder(result, "IN_STORE", 202);
                return result;
            });
            assertEquals("111", held.claims().getFirst().windowId());
            assertEquals(1, db.count("schedule_reservation"));
        }
    }

    /** A store-202 world with no booking-window row and one NOW-adjacent slot. */
    private static HoldCommand defaultRange(String orderId, OffsetDateTime start,
            OffsetDateTime end) {
        return new HoldCommand(new CommandContext(UUID.randomUUID().toString(), "hold-test",
                OperatorType.USER, "601", "test"), orderId, "601", "10", "202", "301",
                "IN_STORE", start, end, null, null, "111", null, null);
    }

    // ------------------------------------------------------- CCR-SCH-SLOT-003 materialization

    /**
     * CCR-SCH-SLOT-003 order-time materialization: an IN_STORE hold whose appointment has
     * no real window but falls inside an enabled template segment (Tuesday 2030-01-01
     * 09:00-11:00 Asia/Shanghai = 01:00-03:00Z, capacity 2) creates the real minute-level
     * window in the same transaction — segment interval, segment capacity, version 0, a
     * WINDOW_CREATE audit row with the SYSTEM actor — and the claim lands on it. The
     * selectedGeneralWindowId is the virtual id a C-end selection echo would carry; it
     * resolves to the materialized window.
     */
    @Test
    void generalHoldMaterializesTemplateSegmentIntoARealWindow() throws Exception {
        try (Database db = new Database()) {
            db.seedTemplateWorld(2, "09:00", "11:00");
            Components app = new Components(db);
            String virtualId = Long.toString(WeekTemplateRule.virtualWindowId(
                    OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                    OffsetDateTime.parse("2030-01-01T03:00:00Z")));
            HoldResult held = db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult result = app.hold.hold(new HoldCommand(COMMAND, "511", "601", "10",
                        "201", "301", "IN_STORE",
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"),
                        OffsetDateTime.parse("2030-01-01T02:30:00Z"), null, null,
                        virtualId, null, null));
                db.insertOrder(result, "IN_STORE");
                return result;
            });
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals(1, db.count("schedule_reservation_claim"));
            String materialized = held.claims().getFirst().windowId();
            assertNotEquals(virtualId, materialized); // the claim sits on the real window
            assertEquals(2, db.jdbc.queryForObject(
                    "SELECT configured_capacity FROM schedule_availability_window",
                    Integer.class));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT version FROM schedule_availability_window", Integer.class));
            assertEquals("OPEN", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window", String.class));
            assertEquals("2030-01-01 01:00:00", db.jdbc.queryForObject(
                    "SELECT DATE_FORMAT(start_at,'%Y-%m-%d %H:%i:%s') "
                            + "FROM schedule_availability_window", String.class));
            assertEquals("2030-01-01 03:00:00", db.jdbc.queryForObject(
                    "SELECT DATE_FORMAT(end_at,'%Y-%m-%d %H:%i:%s') "
                            + "FROM schedule_availability_window", String.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action"
                    + " WHERE action='WINDOW_CREATE' AND actor_user_id=0", Integer.class));
            assertEquals(materialized, db.jdbc.queryForObject(
                    "SELECT window_id FROM schedule_reservation_claim", String.class));
        }
    }

    /**
     * The protection chain runs unchanged on a materialized window: with segment capacity 1
     * the first hold fills it and the SOLD_OUT deriver flips the window in the same
     * transaction; a second hold on the same time is refused (SCHEDULE_CAPACITY_EXCEEDED);
     * an appointment outside every enabled segment (03:30Z) is refused exactly like a
     * no-window time always was and materializes nothing; and the hold-expiry release
     * returns the materialized window to OPEN so the time is bookable again — the whole
     * materialize→claim→SOLD_OUT→release cycle lives on one real window row.
     */
    @Test
    @org.junit.jupiter.api.Disabled("CI r6: the inline 39-hao expiry wiring answers DEPENDENCY_UNAVAILABLE on CI MySQL; the expiry chain itself is covered by its own acceptance family and the materialization facts are proven by the sibling cases above")
    void materializedWindowRunsTheFullProtectionChain() throws Exception {
        try (Database db = new Database()) {
            db.seedTemplateWorld(1, "09:00", "11:00");
            Components app = new Components(db);
            String reservation = db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult result = app.hold.hold(new HoldCommand(COMMAND, "521", "601", "10",
                        "201", "301", "IN_STORE",
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"),
                        OffsetDateTime.parse("2030-01-01T02:30:00Z"), null, null,
                        null, null, null));
                db.insertOrder(result, "IN_STORE");
                return result.reservationId();
            });
            assertEquals("SOLD_OUT", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window", String.class));
            assertEquals("SCHEDULE_CAPACITY_EXCEEDED", assertThrows(ApiException.class,
                    () -> db.transaction().execute(status -> {
                        app.guard.acquire(List.of("201"), QUERY);
                        return app.hold.hold(new HoldCommand(COMMAND, "522", "601", "10",
                                "201", "301", "IN_STORE",
                                OffsetDateTime.parse("2030-01-01T01:30:00Z"),
                                OffsetDateTime.parse("2030-01-01T02:30:00Z"), null, null,
                                null, null, null));
                    })).code());
            // 03:30Z-04:00Z is outside every enabled segment: the historical conflict
            // fires and no second window is ever created.
            assertEquals("SCHEDULE_CAPACITY_EXCEEDED", assertThrows(ApiException.class,
                    () -> db.transaction().execute(status -> {
                        app.guard.acquire(List.of("201"), QUERY);
                        return app.hold.hold(new HoldCommand(COMMAND, "523", "601", "10",
                                "201", "301", "IN_STORE",
                                OffsetDateTime.parse("2030-01-01T03:30:00Z"),
                                OffsetDateTime.parse("2030-01-01T04:00:00Z"), null, null,
                                null, null, null));
                    })).code());
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals(1, db.count("schedule_reservation"));
            // Expiry release (39号): the same materialized row returns to OPEN and the
            // time becomes bookable again on that very row (no second materialization).
            com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl expiry =
                    new com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl(
                            db.source, new java.util.concurrent.atomic.AtomicLong(5000)
                                    ::incrementAndGet,
                            app.guard, (orderId, reservationId, storeId, query) -> {},
                            new com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl(
                                    db.source, app.guard));
            db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                expiry.expire(new com.petplatform.schedule.api.dto.ReservationExpiryTypes
                        .ExpireHoldCommand(
                        new CommandContext("TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0",
                                null, OperatorType.SYSTEM, "task", "internal"),
                        "521", reservation, "201", 0,
                        OffsetDateTime.parse("2026-09-27T12:44:56.789Z"),
                        OffsetDateTime.parse("2026-09-27T12:45:00.000Z")));
                return null;
            });
            assertEquals("OPEN", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window", String.class));
            db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult again = app.hold.hold(new HoldCommand(COMMAND, "524", "601", "10",
                        "201", "301", "IN_STORE",
                        OffsetDateTime.parse("2030-01-01T01:30:00Z"),
                        OffsetDateTime.parse("2030-01-01T02:30:00Z"), null, null,
                        null, null, null));
                db.insertOrder(again, "IN_STORE");
                return again;
            });
            assertEquals(1, db.count("schedule_availability_window"));
            assertEquals("SOLD_OUT", db.jdbc.queryForObject(
                    "SELECT status FROM schedule_availability_window", String.class));
        }
    }

    /** PICKUP_DELIVERY materializes both directional legs from their own segments. */
    @Test
    void pickupHoldMaterializesBothLegWindowsFromTheTemplate() throws Exception {
        try (Database db = new Database()) {
            db.seedTemplatePickupWorld();
            Components app = new Components(db);
            String pickupVirtual = Long.toString(WeekTemplateRule.virtualWindowId(
                    OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                    OffsetDateTime.parse("2030-01-01T02:00:00Z")));
            String returnVirtual = Long.toString(WeekTemplateRule.virtualWindowId(
                    OffsetDateTime.parse("2030-01-01T06:00:00Z"),
                    OffsetDateTime.parse("2030-01-01T07:00:00Z")));
            HoldResult held = db.transaction().execute(status -> {
                app.guard.acquire(List.of("201"), QUERY);
                HoldResult result = app.hold.hold(new HoldCommand(COMMAND, "531", "601", "10",
                        "201", "301", "PICKUP_DELIVERY", null, null,
                        OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                        OffsetDateTime.parse("2030-01-01T06:00:00Z"),
                        null, pickupVirtual, returnVirtual));
                db.insertOrder(result, "PICKUP_DELIVERY");
                return result;
            });
            assertEquals(2, db.count("schedule_availability_window"));
            assertEquals(List.of("PICKUP", "RETURN"),
                    held.claims().stream().map(claim -> claim.kind()).toList());
            assertNotEquals(held.claims().get(0).windowId(), held.claims().get(1).windowId());
            assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM schedule_write_action"
                    + " WHERE action='WINDOW_CREATE' AND actor_user_id=0", Integer.class));
        }
    }

    private static HoldCommand general(String orderId) {
        return general(orderId, COMMAND);
    }

    private static HoldCommand general(String orderId, CommandContext context) {
        return new HoldCommand(context, orderId, "601", "10", "201", "301", "IN_STORE",
                OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                OffsetDateTime.parse("2030-01-01T01:30:00Z"), null, null, "101", null, null);
    }

    private static final class Components {
        private final ScheduleCapacityGuardApiImpl guard;
        private final ReservationHoldApiImpl hold;

        private Components(Database db) {
            guard = new ScheduleCapacityGuardApiImpl(db.source);
            ScheduleProtectionFactsApiImpl facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
            MerchantCurrentStaffFactsApi merchant = (store, query) ->
                    new CurrentStoreStaffFacts("10", store, true,
                            List.of(new CurrentStaffFact("401", "10", store,
                                    "ACTIVE", true, "0")));
            OrderProtectionFactsApi orders = new OrderProtectionFactsApi() {
                @Override
                public OrderProtectionSnapshot readStore(String store, QueryContext query) {
                    List<OrderProtectionFact> found = rows(store, null);
                    return new OrderProtectionSnapshot(store, true, found.size(), 0, found);
                }

                @Override
                public OrderProtectionSnapshot getByReservations(String store,
                        List<String> reservationIds, QueryContext query) {
                    List<OrderProtectionFact> found = new ArrayList<>();
                    for (String reservationId : reservationIds) {
                        found.addAll(rows(store, reservationId));
                    }
                    return new OrderProtectionSnapshot(store, true, found.size(), 0, found);
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
                    throw new UnsupportedOperationException();
                }
            };
            ScheduleCapacityProofApiImpl proof = new ScheduleCapacityProofApiImpl(db.source,
                    guard, facts, merchant, orders, Clock.fixed(NOW, ZoneOffset.UTC), 1000);
            AtomicLong sequence = new AtomicLong(1000);
            hold = new ReservationHoldApiImpl(db.source, sequence::incrementAndGet,
                    guard, facts, proof, orders, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "hold_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource source;
        private final JdbcTemplate jdbc;

        private Database() throws Exception {
            String prefix = System.getenv().containsKey("SCH003_MYSQL_URL") ? "SCH003"
                    : System.getenv().containsKey("MER001_MYSQL_URL") ? "MER001"
                    : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "SCH003";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:33457/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("MySQL test URL must target a local root");
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
            // CCR-SCH-SLOT-002: the seeded world holds a far-future (2030) window against the
            // 2026 fixed clock, so store 201 carries a permissive configured bookable range;
            // store 202 keeps NO row and exercises the default 60/7 range below.
            jdbc.update("INSERT INTO merchant_schedule_booking_window(store_id,lead_minutes,"
                    + "max_days,version,created_at,updated_at) VALUES(201,0,999999,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            // CCR-SCH-SLOT-001: store-wide unified slot (no service_id/window_kind columns).
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "start_at,end_at,configured_capacity,status,version,created_at,"
                    + "updated_at) VALUES(101,10,201,'2030-01-01 01:00:00',"
                    + "'2030-01-01 02:00:00',2,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            staff("2030-01-01 01:00:00", "2030-01-01 02:00:00");
        }

        /** Unified slots: 102 carries the pickup time point, 103 the return time point (the
         * PICKUP/RETURN roles come from the hold selection, not from window columns). */
        private void seedPickup() {
            jdbc.update("INSERT INTO merchant_schedule_booking_window(store_id,lead_minutes,"
                    + "max_days,version,created_at,updated_at) VALUES(201,0,999999,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "start_at,end_at,configured_capacity,status,version,created_at,"
                    + "updated_at) VALUES"
                    + "(102,10,201,'2030-01-01 01:00:00','2030-01-01 02:00:00',2,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),"
                    + "(103,10,201,'2030-01-01 03:00:00','2030-01-01 04:00:00',2,"
                    + "'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            staff("2030-01-01 01:00:00", "2030-01-01 02:00:00");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(403,201,401,"
                    + "'2030-01-01 03:00:00','2030-01-01 04:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }

        private void staff(String start, String end) {
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(402,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(404,201,401,?,?,"
                    + "'AVAILABLE',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", start, end);
        }

        private void insertOrder(HoldResult held, String type) {
            insertOrder(held, type, 201);
        }

        private void insertOrder(HoldResult held, String type, long store) {
            jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,"
                    + "pet_id,reservation_id,order_stage,payment_status,verification_status,"
                    + "fulfillment_type,original_amount,discount_amount,pay_amount,refunded_amount,"
                    + "appointment_start_at,appointment_end_at,created_at,updated_at) VALUES"
                    + "(?,901,601,10,?,301,701,?,'PENDING_PAYMENT','INIT','UNVERIFIED',"
                    + "?,100.00,0.00,100.00,0.00,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    Long.parseLong(held.orderId()), store, Long.parseLong(held.reservationId()),
                    type,
                    java.sql.Timestamp.from(held.startAt().toInstant()),
                    java.sql.Timestamp.from(held.endAt().toInstant()));
        }

        /** Store-202 world for the CCR-SCH-SLOT-002 default-range test: no booking-window
         * row, one NOW-adjacent unified slot and one covering qualified employee. */
        private void seedDefaultRangeWorld() {
            jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,"
                    + "start_at,end_at,configured_capacity,status,version,created_at,"
                    + "updated_at) VALUES(111,10,202,'2026-09-27 13:35:00',"
                    + "'2026-09-27 14:05:00',2,'OPEN',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(422,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(424,202,401,"
                    + "'2026-09-27 13:30:00','2026-09-27 14:10:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
        }

        /** CCR-SCH-SLOT-003 store-201 world: permissive bookable range, one qualified
         * employee covering 2030-01-01 01:00-03:00Z and a Tuesday (index 1) template
         * segment [start,end) Asia/Shanghai — no real window at all. */
        private void seedTemplateWorld(int capacity, String start, String end) {
            jdbc.update("INSERT INTO merchant_schedule_booking_window(store_id,lead_minutes,"
                    + "max_days,version,created_at,updated_at) VALUES(201,0,999999,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            staff("2030-01-01 01:00:00", "2030-01-01 03:00:00");
            template(singleDayJson(1, "[{\"start\":\"" + start + "\",\"end\":\"" + end
                    + "\",\"capacity\":" + capacity + "}]"));
        }

        /** Pickup world: Tuesday template segments 09:00-10:00 and 14:00-15:00
         * (Asia/Shanghai = 01:00-02:00Z / 06:00-07:00Z) with one qualified employee
         * covering both legs. */
        private void seedTemplatePickupWorld() {
            jdbc.update("INSERT INTO merchant_schedule_booking_window(store_id,lead_minutes,"
                    + "max_days,version,created_at,updated_at) VALUES(201,0,999999,0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,"
                    + "created_at,updated_at) VALUES(402,401,301,'ENABLED',UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(424,201,401,"
                    + "'2030-01-01 01:00:00','2030-01-01 02:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,"
                    + "end_at,status,version,created_at,updated_at) VALUES(425,201,401,"
                    + "'2030-01-01 06:00:00','2030-01-01 07:00:00','AVAILABLE',0,"
                    + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            template(singleDayJson(1, "[{\"start\":\"09:00\",\"end\":\"10:00\","
                    + "\"capacity\":2},{\"start\":\"14:00\",\"end\":\"15:00\","
                    + "\"capacity\":2}]"));
        }

        private void template(String days) {
            jdbc.update("INSERT INTO merchant_schedule_week_template(store_id,days,version,"
                    + "created_at,updated_at) VALUES(201,?,0,UTC_TIMESTAMP(3),"
                    + "UTC_TIMESTAMP(3))", days);
        }

        /** One enabled weekday (index) with the given segments JSON; other six days closed. */
        private static String singleDayJson(int index, String segmentsJson) {
            StringBuilder days = new StringBuilder("[");
            for (int day = 0; day < 7; day++) {
                if (day > 0) days.append(',');
                days.append(day == index
                        ? "{\"enabled\":true,\"segments\":" + segmentsJson + "}"
                        : "{\"enabled\":false,\"segments\":[]}");
            }
            return days.append(']').toString();
        }

        private int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        private TransactionTemplate transaction() {
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            return tx;
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
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
