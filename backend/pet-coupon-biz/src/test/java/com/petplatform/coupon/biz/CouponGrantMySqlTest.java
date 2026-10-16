package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.CreateTemplateCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateStatusCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponGrantApi.GrantReceipt;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.biz.apiimpl.AdminCouponGrantApiImpl;
import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.application.AdminCouponService;
import com.petplatform.coupon.biz.infrastructure.persistence.CouponAdminStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
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
 * idempotency). Covers the user-ruling minimum: one AVAILABLE instance per terminal requestId
 * from a template satisfying the frozen 61 §3 precondition ④ usability fact (ACTIVE and now
 * inside the validity window), the ISSUE ledger audit row with its issued_count drawdown,
 * same/different-parameter requestId idempotency, the fail-closed authorization discipline
 * and the template guards (DRAFT/OFFLINE/out-of-window/unknown). The C-end half proves the
 * granted instance is immediately visible in the buyer's AVAILABLE bucket through the
 * existing contract-10 read side (CouponQueryApiImpl) — and invisible to a foreign user.
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
    private static final long USER_A = 9_700_000_000_000_501L;
    private static final long USER_B = 9_700_000_000_000_502L;
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-01T00:00:00.000Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2027-01-01T00:00:00.000Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

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

            // Second grant of the same template to the same user is a fresh instance (V1
            // minimum has no per-user hold cap — CCR-CPN-GRANT-001), each with its own ledger.
            GrantReceipt second = d.grant().grant(
                    new GrantCouponCommand(template.templateId(), Long.toUnsignedString(USER_A),
                            d.command("g-2"), GRANT_AUTH));
            assertNotEquals(receipt.instanceId(), second.instanceId());
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
                    "2026-01-01 00:00:00.000", "2026-02-01 00:00:00.000");
            ApiException windowBlocked = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(expired,
                            Long.toUnsignedString(USER_A), d.command("d3"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, windowBlocked.code());

            // ACTIVE but not yet valid: the same 61④ window fact fails → 409.
            var future = d.seedTemplate("未生效券", "ACTIVE",
                    "2026-11-01 00:00:00.000", "2027-01-01 00:00:00.000");
            ApiException notYetValid = assertThrows(ApiException.class,
                    () -> d.grant().grant(new GrantCouponCommand(future,
                            Long.toUnsignedString(USER_A), d.command("d4"), GRANT_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, notYetValid.code());

            // OFFLINE: never grantable again (new template required — 65 §4.1 state machine).
            var offlined = d.seedTemplate("停用券", "OFFLINE",
                    "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000");
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

    private static final class Database implements AutoCloseable {
        final String name = "cpngrant_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(9_700_000_000_000_000L);
        private final long now = System.currentTimeMillis();

        Database() throws Exception {
            String prefix = System.getenv().containsKey("CPNGRANT_MYSQL_URL") ? "CPNGRANT"
                : System.getenv().containsKey("ADMCPN_MYSQL_URL") ? "ADMCPN"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "CPNGRANT";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException(prefix + "_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
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
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminCouponService admin() {
            return new AdminCouponService(new CouponAdminStore(source, ids::incrementAndGet),
                allowPort(), CLOCK);
        }

        AdminCouponGrantApiImpl grant() {
            return grant(true, true);
        }

        AdminCouponGrantApiImpl grant(boolean allowed, boolean available) {
            return new AdminCouponGrantApiImpl(source, ids::incrementAndGet,
                allowPort(allowed, available), CLOCK);
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
                    "9007199254740993", "ADMIN_WEB");
        }

        String seedActiveTemplate() {
            return seedTemplate("在窗启用券", "ACTIVE", "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000");
        }

        String seedTemplate(String name, String status, String start, String end) {
            long id = ids.incrementAndGet() + now % 1000;
            jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                    + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                    + " VALUES(?,?,?,NULL,0,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, name, status, java.sql.Timestamp.valueOf(start.replace('T', ' ')),
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
