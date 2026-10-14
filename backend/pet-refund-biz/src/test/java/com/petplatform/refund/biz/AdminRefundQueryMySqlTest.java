package com.petplatform.refund.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminAuthorization;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundListQuery;
import com.petplatform.refund.api.query.AdminRefundQueryApi.AdminRefundQuery;
import com.petplatform.refund.biz.apiimpl.AdminRefundQueryApiImpl;
import com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.RefundAuthorizationPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-63 platform refund read face over a real MySQL (schemas 06 + 42 + 49): joined
 * refund_order/execution/application projection, list filters, anti-enumeration detail and the
 * kernel-side authorization discipline. The sealed reason cipher columns are never selected.
 */
class AdminRefundQueryMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-01T00:00:00.000Z");
    private static final AdminAuthorization AUTH = new AdminAuthorization("9007199254740993", 1);

    @Test void listJoinsExecutionAndApplicationFactsWithFilters() throws Exception {
        try (Database d = new Database()) {
            d.seedRefund(1, 5001L, 101L, "FULL", "MERCHANT_APPROVED", "SUCCESS", "128.00", null, null);
            d.seedApplication(10, 1, "APPROVED", "128.00");
            d.seedExecution(1, 5001L, 101L);
            d.seedRefund(2, 5002L, 102L, "PARTIAL", "AFTERSALE_DECISION", "CREATED", "50.00", null, null);

            var page = d.api().listRefunds(list(null, null, null, null));
            assertEquals(2, page.total());
            // Fixed sort created_at DESC, id DESC: the later refund first.
            assertEquals(2L, Long.parseLong(page.items().get(0).refundOrderId()));

            assertEquals(1, d.api().listRefunds(list("SUCCESS", null, null, null)).total());
            assertEquals(1, d.api().listRefunds(list("CREATED", null, null, null)).total());
            assertEquals(1, d.api().listRefunds(list(null, "AFTERSALE_DECISION", null, null)).total());
            assertEquals(1, d.api().listRefunds(list(null, null, "PARTIAL", null)).total());
            assertEquals(1, d.api().listRefunds(list(null, null, null, "101")).total());
            assertEquals(0, d.api().listRefunds(list("FAILED", null, null, null)).total());

            var detail = d.api().getRefund(new AdminRefundQuery("1", operator(), AUTH));
            assertEquals("5001", detail.refundNo());
            assertEquals("SUCCESS", detail.status());
            assertEquals("MERCHANT_APPROVED", detail.sourceType());
            assertEquals("128.00", detail.refundAmount().toPlainString());
            assertEquals("APPROVED", detail.application().status());
            assertEquals("128.00", detail.application().requestedAmount().toPlainString());
            assertEquals("CH-001", detail.execution().channelTradeNo());
            assertEquals("CNY", detail.execution().currency());

            // A refund without execution/application reads with null views, not an error.
            var bare = d.api().getRefund(new AdminRefundQuery("2", operator(), AUTH));
            assertNull(bare.application());
            assertNull(bare.execution());
        }
    }

    @Test void detailIsAntiEnumeratedAndIdentityDisciplined() throws Exception {
        try (Database d = new Database()) {
            d.seedRefund(1, 5001L, 101L, "FULL", "LATE_PAYMENT_TIMEOUT", "SUCCESS", "88.00", "CH-9", "2026-09-02 08:00:00");
            ApiException unknown = assertThrows(ApiException.class,
                () -> d.api().getRefund(new AdminRefundQuery("999", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, unknown.code());
            ApiException invalid = assertThrows(ApiException.class,
                () -> d.api().getRefund(new AdminRefundQuery("abc", operator(), AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
            ApiException identity = assertThrows(ApiException.class,
                () -> d.api().getRefund(new AdminRefundQuery("1",
                    new QueryContext("trace", OperatorType.USER, "42"), AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, identity.code());
            var detail = d.api().getRefund(new AdminRefundQuery("1", operator(), AUTH));
            assertEquals("CH-9", detail.channelRefundNo());
            assertEquals(OffsetDateTime.parse("2026-09-02T08:00:00Z").toInstant(), detail.succeededAt().toInstant());
        }
    }

    @Test void authorizationIsRecheckedInsideTheKernelAndFailsClosed() throws Exception {
        try (Database d = new Database()) {
            d.seedRefund(1, 5001L, 101L, "FULL", "MERCHANT_APPROVED", "CREATED", "128.00", null, null);
            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true, true).listRefunds(list(null, null, null, null)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            ApiException scope = assertThrows(ApiException.class,
                () -> d.api(true, false, true).getRefund(new AdminRefundQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, scope.code());
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, true, false).getRefund(new AdminRefundQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
        }
    }

    private static QueryContext operator() {
        return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, "42");
    }

    private static AdminRefundListQuery list(String status, String sourceType, String refundType, String orderId) {
        return new AdminRefundListQuery(status, sourceType, refundType, orderId, null, null, 1, 20,
            operator(), AUTH);
    }

    private static final class Database implements AutoCloseable {
        final String name = "admrefund_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate sql;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADM_REFUND_MYSQL_URL") ? "ADM_REFUND"
                : System.getenv().containsKey("ORDER_CREATE_MYSQL_URL") ? "ORDER_CREATE"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADM_REFUND";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33459/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ADM_REFUND_MYSQL_URL must target local server root");
            }
            String user = System.getenv().getOrDefault(prefix + "_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault(prefix + "_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            sql = new JdbcTemplate(source);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                    root = root.getParent();
                }
                if (root == null) throw new IllegalStateException("Schema06 not found");
                try (Connection connection = source.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql", "42-Late-Refund-Execution-Schema-v0.1.sql",
                        "45-Merchant-Order-Actions-Schema-v0.1.sql", "49-Refund-Application-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root.resolve("docs/03-database/" + file)),
                            StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminRefundQueryApiImpl api() { return api(true, true, true); }

        /** Collection entry / resource scope / availability switches for the authorization port. */
        AdminRefundQueryApiImpl api(boolean collectionAllowed, boolean scopeAllowed, boolean available) {
            return new AdminRefundQueryApiImpl(source, new RefundAuthorizationPort() {
                @Override
                public com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision check(
                        com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Check check) {
                    if (!available) return null;
                    if (!scopeAllowed) return new com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "RESOURCE_SCOPE_DENIED");
                    return new com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }

                @Override
                public com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision checkCollection(
                        com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.CollectionCheck check) {
                    if (!available) return null;
                    if (!collectionAllowed) return new com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                    return new com.petplatform.refund.biz.application.AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }
            });
        }

        void seedRefund(int id, long refundNo, long orderId, String type, String source, String status,
                String amount, String channelRefundNo, String succeededAt) {
            sql.update("""
                INSERT INTO refund_order(id, refund_no, order_id, refund_application_id, aftersale_id,
                    refund_type, source_type, refund_amount, refund_ratio, status, initiator_type,
                    initiator_id, channel, channel_refund_no, succeeded_at, version, created_at, updated_at)
                VALUES(?, ?, ?, ?, NULL, ?, ?, ?, 1.000000, ?, 'MERCHANT', NULL, 'LAKALA', ?, ?, 0,
                    '2026-09-01 07:00:00', '2026-09-01 07:00:00')
                """, (long) id, refundNo, orderId, (long) (id == 1 ? 10 : id == 2 ? 11 : 0), type, source,
                amount, status, channelRefundNo, succeededAt);
        }

        void seedApplication(int id, int refundOrderId, String status, String amount) {
            sql.update("""
                INSERT INTO refund_application(id, application_no, order_id, applicant_user_id, status,
                    reason_code, requested_amount, merchant_decision_reason, merchant_deadline,
                    decided_by, decided_at, request_id, store_id, merchant_id, reservation_id,
                    payment_id, payment_no, payment_success_event_id, channel_trade_no, paid_at,
                    created_command_id, created_event_id, version, decision_id, refund_order_id,
                    created_at, updated_at)
                VALUES(?, 700, 101, 42, ?, 'PRESTART_REFUND', ?, NULL, '2026-09-02 07:00:00',
                    42, '2026-09-01 08:00:00', 'req-1', 8, 7, 103, 55, 56, 57, 'CH-001',
                    '2026-09-01 06:30:00', 58, 59, 1, 60, ?, '2026-09-01 07:00:00', '2026-09-01 08:00:00')
                """, (long) id, status, amount, (long) refundOrderId);
        }

        void seedExecution(int refundId, long refundNo, long orderId) {
            sql.update("""
                INSERT INTO refund_execution(refund_order_id, refund_no, order_id, payment_id, payment_no,
                    store_id, merchant_id, user_id, payment_success_event_id, late_event_id,
                    channel_trade_no, channel_paid_amount, refund_amount, channel_paid_at, currency,
                    request_id, created_event_id, created_at, updated_at)
                VALUES(?, ?, ?, 55, 56, 8, 7, 42, 57, 90, 'CH-001', '128.00', '128.00',
                    '2026-09-01 06:30:00', 'CNY', 'exec-req-1', 61, '2026-09-01 07:05:00', '2026-09-01 07:05:00')
                """, (long) refundId, refundNo, orderId);
        }

        private static DataSource dataSource(String url, String user, String password) {
            DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return source;
        }

        @Override public void close() {
            try { admin.execute("DROP DATABASE IF EXISTS `" + name + "`"); } catch (RuntimeException ignored) { }
        }
    }
}
