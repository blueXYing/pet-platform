package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.CreateTemplateCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateStatusCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantItem;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.BatchGrantReceipt;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantReceipt;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.biz.apiimpl.AdminCouponGrantApiImpl;
import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.AdminCouponService;
import com.petplatform.coupon.biz.application.AdminCouponUserFactsPort;
import com.petplatform.coupon.biz.application.CouponGrantService.Settings;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-65 §4.3 coupon grant kernel over a real MySQL (SQL06 coupon tables + 14号 shared
 * idempotency). Covers the 2026-10-16 user-ruling minimum (one AVAILABLE instance per
 * terminal requestId from a template satisfying the frozen 61 §3 precondition ④ usability
 * fact, the ISSUE ledger audit row with its issued_count drawdown, same/different-parameter
 * requestId idempotency, the fail-closed authorization discipline and the template guards)
 * plus the 2026-10-19 hardening batch (user ruling "继续扩展CCR", CCR-CPN-GRANT-001):
 * recipient existence through the user-owner read port (missing 404, owner outage 503),
 * the per-user-template hold cap over AVAILABLE+FROZEN, the 60-second operator sliding-window
 * frequency control, the total_stock drawdown limit (NULL stays unlimited) and the ≤100-user
 * batch with per-user independent idempotency, guards, transactions and receipt lines —
 * a rejected user never rolls back granted users. The C-end half proves granted instances
 * are immediately visible in the buyer's AVAILABLE bucket through the existing contract-10
 * read side (CouponQueryApiImpl). New DB wiring reads only AUTH_MYSQL_* (CI prefix).
 */
class CouponGrantMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-16T00:00:00.000Z");
    private static final com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization
            WRITE_AUTH = new com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final com.petplatform.coupon.api.command.AdminCouponGrantApi.AdminAuthorization
            GRANT_AUTH = new com.petplatform.coupon.api.command.AdminCouponGrantApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final QueryContext QUERY =
            new QueryContext("t0", OperatorType.PLATFORM_OPERATOR, "9007199254740993");
    private static final long OPERATOR = 9_007_199_254_740_993L;
    private static final long USER_A = 9_700_000_000_000_501L;
    private static final long USER_B = 9_700_000_000_000_502L;
    private static final long USER_C = 9_700_000_000_000_503L;
    private static final long USER_MISSING = 9_700_000_000_000_609L;
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-01T00:00:00.000Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2027-01-01T00:00:00.000Z");

    /** Deterministic clock; tick() jumps 61s so the ruled 60s grant-frequency window opens. */
    static final class TickClock extends java.time.Clock {
        private final AtomicLong seconds;

        TickClock(Instant start) {
            this.seconds = new AtomicLong(start.getEpochSecond());
        }

        void tick() {
            seconds.addAndGet(61);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }

    @Test void grantIssuesAvailableInstanceLedgerDrawdownAndCBucketVisibility() throws Exception {
        try (Database d = new Database()) {
            var template = d.admin().createTemplate(new CreateTemplateCommand("运营发放券",
                    new BigDecimal("15"), new BigDecimal("50"), START, END, "全场通用", null,
                    d.command("t1"), WRITE_AUTH));
            d.admin().enableTemplate(new TemplateStatusCommand(template.templateId(), 0,
                    d.command("t2"), WRITE_AUTH));

            GrantReceipt receipt = d.grant().grant(
                    new GrantCouponCommand(template.templateId(), Long.toUnsignedString(USER_A),
                            d.command("g-1"), GRANT_AUTH));
            assertEquals("AVAILABLE", receipt.status());
            assertEquals(template.templateId(), receipt.templateId());
            assertEquals(END, receipt.expireAt().toInstant().atOffset(ZoneOffset.UTC));
            long instanceId = Long.parseLong(receipt.instanceId());

            // Instance row: AVAILABLE bucket, expire inherits the template window end.
            assertEquals("AVAILABLE", d.jdbc.queryForObject(
                    "SELECT status FROM coupon_instance WHERE id=?", String.class, instanceId));
            assertEquals(Long.toUnsignedString(USER_A), d.jdbc.queryForObject(
                    "SELECT user_id FROM coupon_instance WHERE id=?", String.class, instanceId));
            assertEquals("2027-01-01 00:00:00.000", d.jdbc.queryForObject(
                    "SELECT CAST(expire_at AS CHAR) FROM coupon_instance WHERE id=?",
                    String.class, instanceId));
            // Audit: exactly one ISSUE ledger row with the CPN:GRANT request id.
            assertEquals("ISSUE", d.jdbc.queryForObject(
                    "SELECT action FROM coupon_ledger WHERE coupon_instance_id=?", String.class, instanceId));
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_ledger WHERE coupon_instance_id=? AND request_id LIKE 'CPN:GRANT:%'",
                    Integer.class, instanceId));
            assertTrue(d.jdbc.queryForObject(
                    "SELECT remark FROM coupon_ledger WHERE coupon_instance_id=?", String.class, instanceId)
                    .contains("operator=9007199254740993"));
            // Drawdown: the template referenced-count moves, so the edit guard arm.
            assertEquals(1L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?",
                    Long.class, Long.parseLong(template.templateId())));

            // C-end: the granted coupon is immediately visible in the buyer's AVAILABLE bucket.
            var mine = d.cQueries().listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 20,
                    new QueryContext("t-c", OperatorType.USER, Long.toUnsignedString(USER_A))));
            assertEquals(1, mine.total());
            assertEquals(receipt.instanceId(), mine.items().get(0).couponId());
            assertEquals("15.00", mine.items().get(0).amountOff());
            // ...and never in a foreign user's bucket.
            var foreign = d.cQueries().listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 20,
                    new QueryContext("t-c", OperatorType.USER, Long.toUnsignedString(USER_B))));
            assertEquals(0, foreign.total());

            // Hardening 2026-10-19: an immediate repeat to the same user+template sits inside
            // the 60-second operator window → 409, and a different user is untouched by it.
            ApiException throttled = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(template.templateId(),
                            Long.toUnsignedString(USER_A), d.command("g-2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, throttled.code());
            d.clock.tick();
            GrantReceipt otherUser = d.grant().grant(
                    new GrantCouponCommand(template.templateId(), Long.toUnsignedString(USER_B),
                            d.command("g-3"), GRANT_AUTH));
            assertNotEquals(receipt.instanceId(), otherUser.instanceId());
            assertEquals(2L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?",
                    Long.class, Long.parseLong(template.templateId())));
        }
    }

    @Test void requestIdIdempotencyReplaysReceiptAndRejectsForeignPayload() throws Exception {
        try (Database d = new Database()) {
            var template = d.seedActiveTemplate();
            GrantReceipt first = d.grant().grant(
                    new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                            d.command("same-3"), GRANT_AUTH));
            GrantReceipt replay = d.grant().grant(
                    new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                            d.command("same-3"), GRANT_AUTH));
            assertEquals(first, replay);
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE user_id=?", Long.class, USER_A).intValue());
            ApiException conflict = assertThrows(ApiException.class,
                    () -> d.grant().grant(
                            new GrantCouponCommand(template, Long.toUnsignedString(USER_B),
                                    d.command("same-3"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance", Integer.class));
        }
    }

    @Test void templateGuardsRejectNonActiveOutOfWindowAndUnknownTemplates() throws Exception {
        try (Database d = new Database()) {
            // DRAFT template: never grantable.
            var draft = d.admin().createTemplate(new CreateTemplateCommand("草稿券",
                    new BigDecimal("5"), null, START, END, null, null, d.command("d1"), WRITE_AUTH));
            ApiException draftBlocked = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(draft.templateId(),
                            Long.toUnsignedString(USER_A), d.command("d2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, draftBlocked.code());

            // ACTIVE but the validity window has passed: usable-per-61④ fails → 409.
            var expired = d.seedTemplate("过窗券", "ACTIVE",
                    "2026-01-01 00:00:00.000", "2026-02-01 00:00:00.000", null);
            ApiException windowBlocked = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(expired,
                            Long.toUnsignedString(USER_A), d.command("d3"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, windowBlocked.code());

            // ACTIVE but not yet valid: the same 61④ window fact fails → 409.
            var future = d.seedTemplate("未生效券", "ACTIVE",
                    "2026-11-01 00:00:00.000", "2027-01-01 00:00:00.000", null);
            ApiException notYetValid = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(future,
                            Long.toUnsignedString(USER_A), d.command("d4"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, notYetValid.code());

            // OFFLINE: never grantable again (new template required — 65 §4.1 state machine).
            var offlined = d.seedTemplate("停用券", "OFFLINE",
                    "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000", null);
            ApiException offlineBlocked = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(offlined,
                            Long.toUnsignedString(USER_A), d.command("d5"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, offlineBlocked.code());

            // Unknown template: 404 anti-enumeration, zero writes.
            ApiException missing = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand("970000000000001",
                            Long.toUnsignedString(USER_A), d.command("d6"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, missing.code());

            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_instance", Integer.class));
            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_ledger", Integer.class));
        }
    }

    @Test void recipientExistenceGuardEliminatesOwnerlessGrantsAndFailsClosed() throws Exception {
        try (Database d = new Database()) {
            var template = d.seedActiveTemplate();
            // Unknown recipient: 404 before any binding or write — no ownerless coupon.
            ApiException missing = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_MISSING), d.command("u1"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, missing.code());
            // Owner read outage: 503 fail-closed, never an unconditional grant.
            ApiException outage = assertThrows(ApiException.class,
                    () -> d.grant(true, true, null, Settings.defaults()).grant(
                            new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                                    d.command("u2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, outage.code());

            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_instance", Integer.class));
            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_ledger", Integer.class));
            assertEquals(0, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency", Integer.class));
        }
    }

    @Test void holdCapCountsAvailableAndFrozenPerUserTemplate() throws Exception {
        try (Database d = new Database()) {
            var template = d.seedActiveTemplate();
            AdminCouponGrantApiImpl kernel = d.grant(new Settings(2, 100));
            kernel.grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                    d.command("h-1"), GRANT_AUTH));
            d.clock.tick();
            String second = kernel.grant(new GrantCouponCommand(template,
                    Long.toUnsignedString(USER_A), d.command("h-2"), GRANT_AUTH)).instanceId();
            // Cap reached (2 held): the third answers 409 with zero extra rows.
            ApiException capped = assertThrows(ApiException.class,
                    () -> kernel.grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A), d.command("h-3"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, capped.code());
            assertEquals(2L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE user_id=?", Long.class, USER_A));

            // USED instances leave the cap bucket (AVAILABLE+FROZEN only): granting works again.
            d.jdbc.update("UPDATE coupon_instance SET status='USED', used_at=UTC_TIMESTAMP(3)"
                    + " WHERE id=?", Long.parseLong(second));
            d.clock.tick();
            kernel.grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                    d.command("h-4"), GRANT_AUTH));
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE user_id=?", Long.class, USER_A));
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?", Long.class,
                    Long.parseLong(template)));
        }
    }

    @Test void frequencyWindowBlocksRepeatAndOpensAfterSixtySeconds() throws Exception {
        try (Database d = new Database()) {
            var template = d.seedActiveTemplate();
            d.grant().grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                    d.command("f-1"), GRANT_AUTH));
            // Same operator+user+template inside the window → 409 (fresh requestId, no replay).
            ApiException throttled = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A), d.command("f-2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, throttled.code());
            // A different user never shares the window.
            d.grant().grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_B),
                    d.command("f-3"), GRANT_AUTH));
            // A different operator for the same user+template is a separate window line.
            d.grant().grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                    new CommandContext("f-4", "t-f4", OperatorType.PLATFORM_OPERATOR,
                            "9007199254740994", "ADMIN_WEB"), GRANT_AUTH));
            // Past the 60s boundary the same operator+user+template grants again.
            d.clock.tick();
            d.grant().grant(new GrantCouponCommand(template, Long.toUnsignedString(USER_A),
                    d.command("f-5"), GRANT_AUTH));
            assertEquals(2L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE user_id=?", Long.class, USER_A));
            assertEquals(1L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE user_id=?", Long.class, USER_B));
        }
    }

    @Test void totalStockDrawdownStopsAtExhaustionAndNullStaysUnlimited() throws Exception {
        try (Database d = new Database()) {
            var limited = d.seedTemplate("限量券", "ACTIVE",
                    "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000", 1L);
            d.grant().grant(new GrantCouponCommand(limited, Long.toUnsignedString(USER_A),
                    d.command("s-1"), GRANT_AUTH));
            ApiException soldOut = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(limited,
                            Long.toUnsignedString(USER_A), d.command("s-2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, soldOut.code());
            assertEquals(1L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?", Long.class,
                    Long.parseLong(limited)));
            assertEquals(1L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance", Integer.class));

            // total_stock NULL stays unlimited (the 2026-10-16 minimum behavior).
            var unlimited = d.seedActiveTemplate();
            d.clock.tick();
            d.grant().grant(new GrantCouponCommand(unlimited, Long.toUnsignedString(USER_A),
                    d.command("s-3"), GRANT_AUTH));
            d.clock.tick();
            d.grant().grant(new GrantCouponCommand(unlimited, Long.toUnsignedString(USER_A),
                    d.command("s-4"), GRANT_AUTH));
            assertEquals(2L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE coupon_template_id=?", Long.class,
                    Long.parseLong(unlimited)));
        }
    }

    @Test void batchGrantsPerUserIndependentlyAndReplaysReceipts() throws Exception {
        try (Database d = new Database()) {
            d.seedUser(USER_C);
            var template = d.seedActiveTemplate();
            List<String> userIds = List.of(Long.toUnsignedString(USER_A),
                    Long.toUnsignedString(USER_B), Long.toUnsignedString(USER_MISSING),
                    Long.toUnsignedString(USER_C));
            BatchGrantReceipt receipt = d.grant().grantBatch(new BatchGrantCouponCommand(
                    template, userIds, d.command("b-1"), GRANT_AUTH));
            // Partial success: the unknown user is a REJECTED line, granted users stand.
            assertEquals(template, receipt.templateId());
            assertEquals(4, receipt.results().size());
            assertEquals(3, receipt.grantedCount());
            assertEquals(1, receipt.rejectedCount());
            assertGranted(receipt.results().get(0), Long.toUnsignedString(USER_A));
            assertGranted(receipt.results().get(1), Long.toUnsignedString(USER_B));
            BatchGrantItem rejected = receipt.results().get(2);
            assertEquals(Long.toUnsignedString(USER_MISSING), rejected.userId());
            assertEquals("REJECTED", rejected.status());
            assertEquals(CommonApiCodes.NOT_FOUND, rejected.code());
            assertNull(rejected.instanceId());
            assertGranted(receipt.results().get(3), Long.toUnsignedString(USER_C));
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance", Integer.class));
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?",
                    Long.class, Long.parseLong(template)));

            // Per-user independent idempotency: the same requestId replays identical lines
            // and creates no new instances.
            d.clock.tick();
            BatchGrantReceipt replay = d.grant().grantBatch(new BatchGrantCouponCommand(
                    template, userIds, d.command("b-1"), GRANT_AUTH));
            assertEquals(receipt.grantedCount(), replay.grantedCount());
            for (int i = 0; i < receipt.results().size(); i++) {
                BatchGrantItem before = receipt.results().get(i);
                BatchGrantItem after = replay.results().get(i);
                assertEquals(before.userId(), after.userId());
                assertEquals(before.status(), after.status());
                assertEquals(before.instanceId(), after.instanceId());
            }
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance", Integer.class));

            // Malformed batches never reach the kernel: empty, duplicate and oversized lists.
            ApiException empty = assertThrows(ApiException.class,
                    () -> d.grant().grantBatch(new BatchGrantCouponCommand(template, List.of(),
                            d.command("b-2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, empty.code());
            ApiException duplicate = assertThrows(ApiException.class,
                    () -> d.grant().grantBatch(new BatchGrantCouponCommand(template,
                            List.of(Long.toUnsignedString(USER_A), Long.toUnsignedString(USER_A)),
                            d.command("b-3"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, duplicate.code());
            String[] oversized = new String[101];
            for (int i = 0; i < oversized.length; i++) {
                oversized[i] = Long.toUnsignedString(9_650_000_000_000_000L + i);
            }
            ApiException tooMany = assertThrows(ApiException.class,
                    () -> d.grant().grantBatch(new BatchGrantCouponCommand(template,
                            List.of(oversized), d.command("b-4"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, tooMany.code());
            assertEquals(3L, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance", Integer.class));
        }
    }

    @Test void failClosedAuthorizationAndOperatorDiscipline() throws Exception {
        try (Database d = new Database()) {
            var template = d.seedActiveTemplate();
            // Denied decision → 403, no write.
            ApiException denied = assertThrows(ApiException.class,
                    () -> d.grant(false, true).grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A), d.command("a1"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            // Unavailable decision (null) → 503, no write.
            ApiException unavailable = assertThrows(ApiException.class,
                    () -> d.grant(true, false).grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A), d.command("a2"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
            // Missing authorization reference → 401.
            ApiException noAuth = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A), d.command("a3"),
                            new com.petplatform.coupon.api.command.AdminCouponGrantApi.AdminAuthorization(
                                    " ", 0))));
            assertEquals(CommonApiCodes.UNAUTHORIZED, noAuth.code());
            // Non-operator subject → 403.
            ApiException wrongSubject = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(template,
                            Long.toUnsignedString(USER_A),
                            new CommandContext("a4", "t-a4", OperatorType.USER,
                                    Long.toUnsignedString(USER_A), "MINIAPP"),
                            GRANT_AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, wrongSubject.code());
            // Malformed ids → 400.
            ApiException badId = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand("not-an-id",
                            Long.toUnsignedString(USER_A), d.command("a5"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badId.code());

            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_instance", Integer.class));
            assertEquals(0, d.jdbc.queryForObject("SELECT COUNT(*) FROM coupon_ledger", Integer.class));
            assertEquals(0, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency WHERE status='SUCCEEDED'", Integer.class));
        }
    }

    private static void assertGranted(BatchGrantItem item, String userId) {
        assertEquals(userId, item.userId());
        assertEquals("GRANTED", item.status());
        assertNotNull(item.instanceId());
        assertNotNull(item.expireAt());
        assertNull(item.code());
    }

    private static final class Database implements AutoCloseable {
        final String name = "cpngrant_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(9_700_000_000_000_000L);
        final TickClock clock = new TickClock(Instant.parse("2026-10-16T00:00:00Z"));
        private final long now = System.currentTimeMillis();

        Database() throws Exception {
            // CI supplies the single AUTH_MYSQL_* prefix (iron rule, no new env prefix); the
            // local default targets the 33440 developer MySQL.
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("AUTH_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/14-Command-Idempotency-Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")),
                        StandardCharsets.UTF_8));
                }
                seedUser(USER_A);
                seedUser(USER_B);
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminCouponService admin() {
            return new AdminCouponService(new CouponAdminStore(source, ids::incrementAndGet),
                allowPort(), clock);
        }

        AdminCouponGrantApiImpl grant() {
            return grant(Settings.defaults());
        }

        AdminCouponGrantApiImpl grant(Settings settings) {
            return grant(true, true, userFactsPort(), settings);
        }

        AdminCouponGrantApiImpl grant(boolean allowed, boolean available) {
            return grant(allowed, available, userFactsPort(), Settings.defaults());
        }

        /** userPort == null simulates the owner read outage (every probe fails). */
        AdminCouponGrantApiImpl grant(boolean allowed, boolean available,
                AdminCouponUserFactsPort.CouponPort userPort, Settings settings) {
            AdminCouponUserFactsPort.CouponPort facts = userPort != null ? userPort
                    : (userId, operatorId) -> {
                        throw new IllegalStateException("user owner read unavailable");
                    };
            return new AdminCouponGrantApiImpl(source, ids::incrementAndGet,
                allowPort(allowed, available), facts, settings, clock);
        }

        AdminCouponUserFactsPort.CouponPort userFactsPort() {
            return (userId, operatorId) -> {
                Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_account WHERE id=?", Long.class, userId);
                return count != null && count > 0;
            };
        }

        void seedUser(long userId) {
            jdbc.update("INSERT INTO user_account(id,status,password_enabled,version,created_at,updated_at)"
                    + " VALUES(?,'ACTIVE',0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", userId);
        }

        CouponQueryApiImpl cQueries() {
            return new CouponQueryApiImpl(source);
        }

        private AdminCouponAuthorizationPort.CouponPort allowPort() {
            return allowPort(true, true);
        }

        private AdminCouponAuthorizationPort.CouponPort allowPort(boolean allowed, boolean available) {
            return check -> {
                if (!available) return null;
                return new AdminCouponAuthorizationPort.Decision(allowed, CHECKED, "1:1",
                        allowed ? "ALLOWED" : "ACTION_NOT_GRANTED");
            };
        }

        CommandContext command(String requestId) {
            return new CommandContext(requestId, "t-" + requestId, OperatorType.PLATFORM_OPERATOR,
                    Long.toUnsignedString(OPERATOR), "ADMIN_WEB");
        }

        String seedActiveTemplate() {
            return seedTemplate("在窗启用券", "ACTIVE",
                    "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000", null);
        }

        String seedTemplate(String name, String status, String start, String end, Long totalStock) {
            long id = ids.incrementAndGet() + now % 1000;
            jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                    + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                    + " VALUES(?,?,?,?,0,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, name, status, totalStock,
                    java.sql.Timestamp.valueOf(start.replace('T', ' ')),
                    java.sql.Timestamp.valueOf(end.replace('T', ' ')),
                    "{\"amountOff\":\"10\",\"typeLabel\":\"无门槛券\"}");
            return Long.toUnsignedString(id);
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }

        private static DataSource dataSource(String url, String user, String password) {
            return new DriverManagerDataSource(
                url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
        }
    }
}
