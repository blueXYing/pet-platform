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
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidCouponCommand;
import com.petplatform.coupon.api.command.AdminCouponVoidApi.VoidReceipt;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponQuery;
import com.petplatform.coupon.api.query.CouponQueryApi.MyCouponListQuery;
import com.petplatform.coupon.biz.apiimpl.AdminCouponGrantApiImpl;
import com.petplatform.coupon.biz.apiimpl.AdminCouponVoidApiImpl;
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
 * Contract-65 §4.4 coupon void kernel over a real MySQL (SQL06 coupon tables + 14号 shared
 * idempotency). Covers the doc-36 minimal authorization: exactly one AVAILABLE instance voids
 * per terminal requestId into the new VOID terminal state with the CPN:VOID ledger audit row
 * (operator+reason remark), same/different-parameter requestId idempotency, the status guards
 * (USED/FROZEN/EXPIRED/unknown → 409/404, zero writes), the fail-closed authorization
 * discipline, and the C-end halves: the voided instance disappears from every bucket of the
 * contract-10 read side immediately (list and detail 404) while used/expired buckets of other
 * instances stay untouched.
 */
class CouponVoidMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-16T00:00:00.000Z");
    private static final com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization
            WRITE_AUTH = new com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final com.petplatform.coupon.api.command.AdminCouponGrantApi.AdminAuthorization
            GRANT_AUTH = new com.petplatform.coupon.api.command.AdminCouponGrantApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final com.petplatform.coupon.api.command.AdminCouponVoidApi.AdminAuthorization
            VOID_AUTH = new com.petplatform.coupon.api.command.AdminCouponVoidApi.AdminAuthorization(
                    "9007199254740993", 1);
    private static final long OPERATOR = 9_007_199_254_740_993L;
    private static final long USER_A = 9_700_000_000_000_601L;
    private static final long USER_B = 9_700_000_000_000_602L;
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-01T00:00:00.000Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2027-01-01T00:00:00.000Z");

    /** Deterministic clock; tick() jumps 61s so the ruled 60s grant-frequency window opens. */
    static final class TickClock extends java.time.Clock {
        private final java.util.concurrent.atomic.AtomicLong seconds;

        TickClock(Instant start) {
            this.seconds = new java.util.concurrent.atomic.AtomicLong(start.getEpochSecond());
        }

        void tick() {
            seconds.addAndGet(61);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }

    @Test void voidAvailableInstanceLandsVoidRowLedgerAndCBucketInvisibility() throws Exception {
        try (Database d = new Database()) {
            String template = d.seedActiveTemplate();
            String instanceId = d.grant(template, "g-1");
            assertEquals("AVAILABLE", d.statusOf(instanceId));

            VoidReceipt receipt = d.voidKernel().voidInstance(new VoidCouponCommand(
                    instanceId, "风控核实为异常渠道券，予以作废", d.command("v-1"), VOID_AUTH));
            assertEquals("VOID", receipt.status());
            assertEquals(instanceId, receipt.instanceId());
            assertEquals(template, receipt.templateId());
            assertEquals(Long.toUnsignedString(USER_A), receipt.userId());

            // Instance row: terminal VOID, historical facts (expire/order) untouched.
            assertEquals("VOID", d.statusOf(instanceId));
            assertEquals("2027-01-01 00:00:00.000", d.jdbc.queryForObject(
                    "SELECT CAST(expire_at AS CHAR) FROM coupon_instance WHERE id=?",
                    String.class, Long.parseLong(instanceId)));
            // Audit: the append-only VOID ledger row with the CPN:VOID uk and operator/reason.
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_ledger WHERE coupon_instance_id=? AND action='VOID'",
                    Integer.class, Long.parseLong(instanceId)));
            assertEquals("CPN:VOID:" + instanceId, d.jdbc.queryForObject(
                    "SELECT request_id FROM coupon_ledger WHERE coupon_instance_id=? AND action='VOID'",
                    String.class, Long.parseLong(instanceId)));
            String remark = d.jdbc.queryForObject(
                    "SELECT remark FROM coupon_ledger WHERE coupon_instance_id=? AND action='VOID'",
                    String.class, Long.parseLong(instanceId));
            assertTrue(remark.contains("operator=" + OPERATOR));
            assertTrue(remark.contains("source=ADMIN_WEB"));
            assertTrue(remark.contains("reason=风控核实为异常渠道券"));
            // issued_count keeps the issuance history; void never rolls it back.
            assertEquals(1L, d.jdbc.queryForObject(
                    "SELECT issued_count FROM coupon_template WHERE id=?",
                    Long.class, Long.parseLong(template)));

            // C-end: gone from every bucket (list AVAILABLE/USED/EXPIRED) and the detail 404s.
            for (String bucket : new String[] {"AVAILABLE", "USED", "EXPIRED"}) {
                var mine = d.cQueries().listMyCoupons(new MyCouponListQuery(bucket, 1, 20,
                        new QueryContext("t-c", OperatorType.USER, Long.toUnsignedString(USER_A))));
                assertEquals(0, mine.total(), bucket);
            }
            ApiException gone = assertThrows(ApiException.class, () -> d.cQueries().getMyCoupon(
                    new MyCouponQuery(instanceId,
                            new QueryContext("t-c", OperatorType.USER, Long.toUnsignedString(USER_A)))));
            assertEquals(CommonApiCodes.NOT_FOUND, gone.code());
            // A foreign user never saw it anyway.
            var foreign = d.cQueries().listMyCoupons(new MyCouponListQuery("AVAILABLE", 1, 20,
                    new QueryContext("t-c", OperatorType.USER, Long.toUnsignedString(USER_B))));
            assertEquals(0, foreign.total());
        }
    }

    @Test void requestIdIdempotencyReplaysReceiptAndRejectsForeignPayload() throws Exception {
        try (Database d = new Database()) {
            String template = d.seedActiveTemplate();
            String first = d.grant(template, "g-2");
            String second = d.grant(template, "g-3");

            VoidReceipt receipt = d.voidKernel().voidInstance(new VoidCouponCommand(
                    first, "实名投诉核实成立作废该券", d.command("same-4"), VOID_AUTH));
            VoidReceipt replay = d.voidKernel().voidInstance(new VoidCouponCommand(
                    first, "实名投诉核实成立作废该券", d.command("same-4"), VOID_AUTH));
            assertEquals(receipt, replay);
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_ledger WHERE coupon_instance_id=? AND action='VOID'",
                    Integer.class, Long.parseLong(first)));
            // Same requestId + same scope with a different payload answers 409 and writes
            // nothing (the module storeKey scopes by resource, mirroring the grant channel).
            ApiException conflict = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(first, "实名投诉驳回后改口作废该券", d.command("same-4"), VOID_AUTH)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
            // A different instance voids independently under its own terminal requestId.
            d.voidKernel().voidInstance(new VoidCouponCommand(
                    second, "另一张券的独立作废请求原因", d.command("v-other"), VOID_AUTH));
            assertEquals("VOID", d.statusOf(second));
        }
    }

    @Test void statusGuardsRejectNonAvailableAndUnknownInstances() throws Exception {
        try (Database d = new Database()) {
            String template = d.seedActiveTemplate();

            // USED: spent value is not voidable.
            String used = d.seedInstance(template, "USED");
            ApiException usedBlocked = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(used, "已核销券不允许作废操作原因", d.command("s-1"), VOID_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, usedBlocked.code());

            // FROZEN: locked by an in-flight order — the doc-36 hard rule.
            String frozen = d.seedInstance(template, "FROZEN");
            ApiException frozenBlocked = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(frozen, "在途订单冻结券不允许作废原因", d.command("s-2"), VOID_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, frozenBlocked.code());

            // RISK_FROZEN: frozen vocabulary is equally locked.
            String riskFrozen = d.seedInstance(template, "RISK_FROZEN");
            ApiException riskBlocked = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(riskFrozen, "风控冻结券不允许作废操作原因", d.command("s-3"), VOID_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, riskBlocked.code());

            // EXPIRED: already terminal.
            String expired = d.seedInstance(template, "EXPIRED");
            ApiException expiredBlocked = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(expired, "已过期券不允许作废操作原因", d.command("s-4"), VOID_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, expiredBlocked.code());

            // Unknown instance: 404 anti-enumeration, zero writes.
            ApiException missing = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand("970000000000001", "不存在的券编号作废请求原因", d.command("s-5"), VOID_AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, missing.code());

            assertEquals(4, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_instance WHERE status IN ('USED','FROZEN','RISK_FROZEN','EXPIRED')",
                    Integer.class));
            assertEquals(0, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_ledger WHERE action='VOID'", Integer.class));
            assertEquals(0, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency WHERE status='SUCCEEDED'", Integer.class));
        }
    }

    @Test void reasonAndSubjectDiscipline() throws Exception {
        try (Database d = new Database()) {
            String template = d.seedActiveTemplate();
            String instanceId = d.grant(template, "g-4");
            // Missing/short/overlong reason → 400, no write.
            ApiException noReason = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(instanceId, "   ", d.command("r-1"), VOID_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, noReason.code());
            ApiException shortReason = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(instanceId, "原因不明", d.command("r-2"), VOID_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, shortReason.code());
            ApiException longReason = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(instanceId, "长".repeat(201), d.command("r-3"), VOID_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, longReason.code());
            // Malformed id → 400.
            ApiException badId = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand("not-an-id", "原因充分的一段作废说明文字", d.command("r-4"), VOID_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badId.code());
            assertEquals("AVAILABLE", d.statusOf(instanceId));

            // Denied decision → 403; unavailable decision → 503; both write nothing.
            ApiException denied = assertThrows(ApiException.class, () -> d.voidKernel(false, true).voidInstance(
                    new VoidCouponCommand(instanceId, "授权拒绝场景下的作废原因", d.command("r-5"), VOID_AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException unavailable = assertThrows(ApiException.class, () -> d.voidKernel(true, false).voidInstance(
                    new VoidCouponCommand(instanceId, "授权不可用场景的作废原因", d.command("r-6"), VOID_AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
            // Missing authorization reference → 401.
            ApiException noAuth = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(instanceId, "缺少授权引用场景作废原因", d.command("r-7"),
                            new com.petplatform.coupon.api.command.AdminCouponVoidApi.AdminAuthorization(" ", 0))));
            assertEquals(CommonApiCodes.UNAUTHORIZED, noAuth.code());
            // Non-operator subject → 403.
            ApiException wrongSubject = assertThrows(ApiException.class, () -> d.voidKernel().voidInstance(
                    new VoidCouponCommand(instanceId, "非运营主体场景的作废原因",
                            new CommandContext("r-8", "t-r8", OperatorType.USER,
                                    Long.toUnsignedString(USER_A), "MINIAPP"), VOID_AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, wrongSubject.code());

            assertEquals("AVAILABLE", d.statusOf(instanceId));
            assertEquals(0, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_ledger WHERE action='VOID'", Integer.class));
        }
    }

    private static final class Database implements AutoCloseable {
        final String name = "cpnvoid_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(9_700_000_000_000_700L);
        final TickClock clock = new TickClock(Instant.parse("2026-10-16T00:00:00Z"));
        private final long now = System.currentTimeMillis();

        Database() throws Exception {
            String prefix = System.getenv().containsKey("CPNVOID_MYSQL_URL") ? "CPNVOID"
                : System.getenv().containsKey("CPNGRANT_MYSQL_URL") ? "CPNGRANT"
                : System.getenv().containsKey("ADMCPN_MYSQL_URL") ? "ADMCPN"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "CPNVOID";
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
                seedUser(USER_A);
                seedUser(USER_B);
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminCouponService admin() {
            return new AdminCouponService(new CouponAdminStore(source, ids::incrementAndGet),
                allowPort(), clock);
        }

        AdminCouponGrantApiImpl grantKernel() {
            return new AdminCouponGrantApiImpl(source, ids::incrementAndGet, allowPort(),
                userFactsPort(), com.petplatform.coupon.biz.application.CouponGrantService
                        .Settings.defaults(), clock);
        }

        /** In-module stub of the user owner's existence port, backed by the seeded user_account rows. */
        com.petplatform.coupon.biz.application.AdminCouponUserFactsPort.CouponPort userFactsPort() {
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

        AdminCouponVoidApiImpl voidKernel() {
            return voidKernel(true, true);
        }

        AdminCouponVoidApiImpl voidKernel(boolean allowed, boolean available) {
            return new AdminCouponVoidApiImpl(source, ids::incrementAndGet,
                allowPort(allowed, available), clock);
        }

        CouponQueryApiImpl cQueries() {
            return new CouponQueryApiImpl(source);
        }

        String grant(String templateId, String requestId) {
            clock.tick();
            return grantKernel().grant(new GrantCouponCommand(templateId,
                    Long.toUnsignedString(USER_A), command(requestId), GRANT_AUTH)).instanceId();
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
            long id = ids.incrementAndGet() + now % 1000;
            jdbc.update("INSERT INTO coupon_template(id,name,status,total_stock,issued_count,"
                    + "valid_start_at,valid_end_at,rule_json,version,created_at,updated_at)"
                    + " VALUES(?,?,'ACTIVE',NULL,0,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, "作废测试券", "2026-01-01 00:00:00.000", "2027-01-01 00:00:00.000",
                    "{\"amountOff\":\"10\",\"typeLabel\":\"无门槛券\"}");
            return Long.toUnsignedString(id);
        }

        /** Directly seeds a non-AVAILABLE instance (the guarded states are unreachable via grant). */
        String seedInstance(String templateId, String status) {
            long id = ids.incrementAndGet() + now % 1000;
            jdbc.update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,"
                    + "frozen_at,freeze_expire_at,used_at,original_expire_at,expire_at,version,created_at,updated_at)"
                    + " VALUES(?,?,?,?,NULL,NULL,NULL,NULL,?, ?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id, Long.parseLong(templateId), USER_A, status,
                    "2027-01-01 00:00:00.000", "2027-01-01 00:00:00.000");
            return Long.toUnsignedString(id);
        }

        String statusOf(String instanceId) {
            return jdbc.queryForObject("SELECT status FROM coupon_instance WHERE id=?",
                    String.class, Long.parseLong(instanceId));
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
