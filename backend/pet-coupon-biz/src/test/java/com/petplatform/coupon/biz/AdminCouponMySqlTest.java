package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.CreateTemplateCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.TemplateStatusCommand;
import com.petplatform.coupon.api.command.AdminCouponCommandApi.UpdateTemplateCommand;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminInstanceListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateListQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminTemplateQuery;
import com.petplatform.coupon.api.query.AdminCouponQueryApi.TrialCalculationQuery;
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
 * Contract-65 coupon governance kernel over a real MySQL (SQL06 coupon tables + 14 shared
 * idempotency): template lifecycle (create DRAFT → enable ACTIVE → offline OFFLINE), the
 * issuedCount edit guard, the threshold>amountOff rule, the trial-calculation eligibility
 * matrix, the instance ledger with its append-only timeline, requestId idempotency and the
 * fail-closed authorization discipline. No test touches coupon_instance rows through the
 * write face — the face has no instance write path by contract.
 */
class AdminCouponMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-16T00:00:00.000Z");
    private static final com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization AUTH =
            new com.petplatform.coupon.api.query.AdminCouponQueryApi.AdminAuthorization("9007199254740993", 1);
    private static final com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization WRITE_AUTH =
            new com.petplatform.coupon.api.command.AdminCouponCommandApi.AdminAuthorization("9007199254740993", 1);
    private static final QueryContext QUERY =
            new QueryContext("t0", OperatorType.PLATFORM_OPERATOR, "9007199254740993");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-01T00:00:00.000Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2027-01-01T00:00:00.000Z");

    @Test void templateLifecycleEditGuardAndTrialMatrix() throws Exception {
        try (Database d = new Database()) {
            var created = d.api().createTemplate(new CreateTemplateCommand("新人满减券",
                    new BigDecimal("20"), new BigDecimal("100"), START, END, "全场通用", null,
                    command("c1"), WRITE_AUTH));
            assertEquals("DRAFT", created.status());
            long templateId = Long.parseLong(created.templateId());
            assertEquals("满减券", d.jdbc.queryForObject(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(rule_json,'$.typeLabel')) FROM coupon_template WHERE id=?",
                    String.class, templateId));

            // Editable while issuedCount=0.
            var updated = d.api().updateTemplate(new UpdateTemplateCommand(created.templateId(),
                    "新人满减券二", new BigDecimal("25"), new BigDecimal("120"), START, END,
                    "全场通用", null, 0, command("c2"), WRITE_AUTH));
            assertEquals(1L, updated.version());

            // Threshold must exceed the discount.
            ApiException badRule = assertThrows(ApiException.class,
                    () -> d.api().createTemplate(new CreateTemplateCommand("坏规则券",
                            new BigDecimal("100"), new BigDecimal("50"), START, END, null, null,
                            command("c3"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, badRule.code());

            var enabled = d.api().enableTemplate(new TemplateStatusCommand(created.templateId(), 1, command("c4"), WRITE_AUTH));
            assertEquals("ACTIVE", enabled.status());
            // Re-enable from ACTIVE → 409 (fromStatus guard misses).
            ApiException repeat = assertThrows(ApiException.class,
                    () -> d.api().enableTemplate(new TemplateStatusCommand(created.templateId(), 2, command("c5"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, repeat.code());

            // Trial matrix: eligible path.
            var ok = d.api().trialCalculation(new TrialCalculationQuery(created.templateId(),
                    new BigDecimal("200"), QUERY, AUTH));
            assertTrue(ok.eligible());
            assertEquals(0, ok.discountAmount().compareTo(new BigDecimal("25")));
            assertEquals(0, ok.payAmount().compareTo(new BigDecimal("175")));
            // Below threshold.
            var low = d.api().trialCalculation(new TrialCalculationQuery(created.templateId(),
                    new BigDecimal("99.99"), QUERY, AUTH));
            assertFalse(low.eligible());
            assertEquals("BELOW_THRESHOLD", low.ineligibleReason());
            // Below-threshold wins first (threshold 120 > order 20).
            var below = d.api().trialCalculation(new TrialCalculationQuery(created.templateId(),
                    new BigDecimal("20.00"), QUERY, AUTH));
            assertEquals("BELOW_THRESHOLD", below.ineligibleReason());

            // No-threshold template: a discount swallowing the whole order stays ineligible
            // (61 precondition ⑦, payable must stay positive).
            var flat = d.api().createTemplate(new CreateTemplateCommand("无门槛试算券",
                    new BigDecimal("20"), null, START, END, null, null, command("c8"), WRITE_AUTH));
            var flatEnabled = d.api().enableTemplate(new TemplateStatusCommand(flat.templateId(), 0, command("c9"), WRITE_AUTH));
            assertEquals("ACTIVE", flatEnabled.status());
            var swallow = d.api().trialCalculation(new TrialCalculationQuery(flat.templateId(),
                    new BigDecimal("20.00"), QUERY, AUTH));
            assertEquals("DISCOUNT_EXCEEDS_AMOUNT", swallow.ineligibleReason());

            var offlined = d.api().offlineTemplate(new TemplateStatusCommand(created.templateId(), 2, command("c6"), WRITE_AUTH));
            assertEquals("OFFLINE", offlined.status());
            // Editing an OFFLINE template updates zero rows → 409.
            ApiException frozen = assertThrows(ApiException.class,
                    () -> d.api().updateTemplate(new UpdateTemplateCommand(created.templateId(),
                            "再改", new BigDecimal("25"), new BigDecimal("120"), START, END,
                            null, null, 3, command("c7"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, frozen.code());
            // Not-active trial reason.
            var inactive = d.api().trialCalculation(new TrialCalculationQuery(created.templateId(),
                    new BigDecimal("200"), QUERY, AUTH));
            assertEquals("NOT_ACTIVE", inactive.ineligibleReason());
        }
    }

    @Test void issuedTemplateIsImmutableAndInstanceLedgerReadsBack() throws Exception {
        try (Database d = new Database()) {
            var created = d.api().createTemplate(new CreateTemplateCommand("已发放券",
                    new BigDecimal("10"), null, START, END, null, null, command("i1"), WRITE_AUTH));
            long templateId = Long.parseLong(created.templateId());
            d.jdbc.update("UPDATE coupon_template SET issued_count=1 WHERE id=?", templateId);
            ApiException immutable = assertThrows(ApiException.class,
                    () -> d.api().updateTemplate(new UpdateTemplateCommand(created.templateId(),
                            "改不动", new BigDecimal("10"), null, START, END, null, null, 0,
                            command("i2"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.CONFLICT, immutable.code());

            d.seedInstance(8201L, templateId, 501L, "AVAILABLE");
            d.jdbc.update("""
                INSERT INTO coupon_ledger(id, coupon_instance_id, user_id, order_id, action,
                    from_status, to_status, request_id, remark, created_at)
                VALUES (9201, 8201, 501, NULL, 'ISSUE', NULL, 'AVAILABLE', 'CPN:ISSUE:t', NULL, UTC_TIMESTAMP(3))
                """);
            var page = d.api().listInstances(new AdminInstanceListQuery(created.templateId(),
                    "AVAILABLE", null, null, null, 1, 20, QUERY, AUTH));
            assertEquals(1, page.total());
            assertEquals(0, page.items().get(0).amountOff().compareTo(new BigDecimal("10")));
            assertNull(page.items().get(0).thresholdAmount());

            var detail = d.api().getInstance(new AdminInstanceQuery("8201", QUERY, AUTH));
            assertEquals("AVAILABLE", detail.status());
            assertEquals(1, detail.ledger().size());
            assertEquals("ISSUE", detail.ledger().get(0).action());
            assertEquals("AVAILABLE", detail.ledger().get(0).toStatus());
        }
    }

    @Test void requestIdIdempotencyAndFailClosedAuthorization() throws Exception {
        try (Database d = new Database()) {
            var first = d.api().createTemplate(new CreateTemplateCommand("幂等券",
                    new BigDecimal("8"), null, START, END, null, null, command("same-9"), WRITE_AUTH));
            var replay = d.api().createTemplate(new CreateTemplateCommand("幂等券",
                    new BigDecimal("8"), null, START, END, null, null, command("same-9"), WRITE_AUTH));
            assertEquals(first, replay);
            ApiException conflict = assertThrows(ApiException.class,
                    () -> d.api().createTemplate(new CreateTemplateCommand("幂等券",
                            new BigDecimal("9"), null, START, END, null, null, command("same-9"), WRITE_AUTH)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());
            assertEquals(1, d.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_template WHERE name='幂等券'", Integer.class));

            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true).listTemplates(new AdminTemplateListQuery(null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, false).listTemplates(new AdminTemplateListQuery(null, 1, 20, QUERY, AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
        }
    }

    private static CommandContext command(String requestId) {
        return new CommandContext(requestId, "t-" + requestId, OperatorType.PLATFORM_OPERATOR,
                "9007199254740993", "ADMIN_WEB");
    }

    private static final class Database implements AutoCloseable {
        final String name = "admcpn_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate jdbc;
        final AtomicLong ids = new AtomicLong(9_600_000_000_000_000L);

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADMCPN_MYSQL_URL") ? "ADMCPN"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADMCPN";
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

        AdminCouponService api() { return api(true, true); }

        AdminCouponService api(boolean collectionAllowed, boolean available) {
            return new AdminCouponService(
                new CouponAdminStore(source, ids::incrementAndGet),
                new AdminCouponAuthorizationPort.Port() {
                    @Override
                    public AdminCouponAuthorizationPort.Decision check(
                            AdminCouponAuthorizationPort.Check check) {
                        if (!available) return null;
                        return new AdminCouponAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                    }

                    @Override
                    public AdminCouponAuthorizationPort.Decision checkCollection(
                            AdminCouponAuthorizationPort.CollectionCheck check) {
                        if (!available) return null;
                        if (!collectionAllowed) {
                            return new AdminCouponAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                        }
                        return new AdminCouponAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                    }
                },
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
        }

        void seedInstance(long id, long templateId, long userId, String status) {
            jdbc.update("""
                INSERT INTO coupon_instance(id, coupon_template_id, user_id, status, order_id,
                    original_expire_at, expire_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, '2027-01-01 00:00:00.000', '2027-01-01 00:00:00.000',
                    0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, id, templateId, userId, status);
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
