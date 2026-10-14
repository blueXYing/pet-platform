package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminAuthorization;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderDetailQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminOrderListQuery;
import com.petplatform.order.api.query.AdminOrderQueryApi.AdminStatusLogQuery;
import com.petplatform.order.biz.apiimpl.AdminOrderQueryApiImpl;
import com.petplatform.order.biz.application.AdminTradeAuthorizationPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Contract-63 platform order read face over a real MySQL (schema 06 + 46): display truth-table
 * filters, snapshot keyword search, reschedule filter, anti-enumeration detail, ascending
 * status timeline and the kernel-side authorization discipline (collection entry, resource
 * scope, fail-closed decisions, operator identity). Test-only JDBC here; production SQL stays
 * in AdminOrderQueryMapper.xml.
 */
class AdminOrderQueryMySqlTest {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-10-01T00:00:00.000Z");
    private static final AdminAuthorization AUTH = new AdminAuthorization("9007199254740993", 1);

    @Test void listDetailAndFiltersReadTheSeededPlatformRows() throws Exception {
        try (Database d = new Database()) {
            d.seedOrder(1, 1001L, 7L, 8L, "PENDING_SERVICE", "PAID", "0.00", 0);
            d.seedOrder(2, 1002L, 7L, 8L, "COMPLETED", "PAID", "128.00", 0);
            d.sql.update("UPDATE pet_order SET aftersale_status='PENDING' WHERE id=2");
            var page = d.api().listOrders(list(null, null, null, false));
            assertEquals(2, page.total());
            // Fixed sort created_at DESC, id DESC: the higher id first.
            assertEquals(2L, Long.parseLong(page.items().get(0).orderId()));
            assertEquals("AFTERSALE", page.items().get(0).displayStatus());
            assertEquals("PENDING_SERVICE", page.items().get(1).displayStatus());
            assertEquals("可爱宠物店", page.items().get(1).merchantName());
            assertEquals("中型犬洗护", page.items().get(1).serviceName());

            assertEquals(1, d.api().listOrders(list("PENDING_SERVICE", null, null, false)).total());
            assertEquals(0, d.api().listOrders(list("COMPLETED", null, null, false)).total());
            // Baseline §5: an active aftersale projection is the AFTERSALE tab regardless of stage.
            var aftersale = d.api().listOrders(list("AFTERSALE", null, null, false));
            assertEquals(1, aftersale.total());
            assertEquals(2L, Long.parseLong(aftersale.items().get(0).orderId()));

            assertEquals(2, d.api().listOrders(list(null, "PAID", null, false)).total());
            assertEquals(0, d.api().listOrders(list(null, "INIT", null, false)).total());

            // keyword: name substring over the service snapshot, or exact order_no for digits.
            assertEquals(2, d.api().listOrders(list(null, null, "可爱", false)).total());
            assertEquals(1, d.api().listOrders(list(null, null, "1002", false)).total());
            ApiException bad = assertThrows(ApiException.class,
                () -> d.api().listOrders(list(null, null, "可%爱", false)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, bad.code());
        }
    }

    @Test void rescheduleFilterAndSnapshotJoinFollowTheSeededFacts() throws Exception {
        try (Database d = new Database()) {
            d.seedOrder(1, 1001L, 7L, 8L, "PENDING_SERVICE", "PAID", "0.00", 0);
            d.seedOrder(2, 1002L, 7L, 8L, "PENDING_SERVICE", "PAID", "0.00", 1);
            d.seedReschedule(2);
            assertEquals(1, d.api().listOrders(list(null, null, null, true)).total());
            assertEquals(2L, Long.parseLong(d.api().listOrders(list(null, null, null, true)).items().get(0).orderId()));
            var detail = d.api().getOrder(new AdminOrderDetailQuery("2", operator(), AUTH));
            assertEquals(1, detail.rescheduleRecords().size());
            assertEquals("128.00", detail.serviceSnapshot().servicePrice().toPlainString());
            assertEquals("recorded value", detail.serviceSnapshot().storeAddress().trim());
        }
    }

    @Test void detailIsAntiEnumeratedAndTheTimelineIsAscending() throws Exception {
        try (Database d = new Database()) {
            d.seedOrder(1, 1001L, 7L, 8L, "PENDING_SERVICE", "PAID", "0.00", 0);
            d.seedStatusLog(1, 11, "ORDER_STAGE", "PENDING_PAYMENT", "PENDING_CONFIRM", "ORDER_CONFIRMED", 55);
            d.seedStatusLog(1, 10, "PAYMENT", "INIT", "PAID", "PAYMENT_SUCCEEDED", 30);
            var detail = d.api().getOrder(new AdminOrderDetailQuery("1", operator(), AUTH));
            assertEquals("1", detail.orderId());
            assertEquals("PAID", detail.paymentStatus());
            ApiException unknown = assertThrows(ApiException.class,
                () -> d.api().getOrder(new AdminOrderDetailQuery("999", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, unknown.code());
            ApiException invalid = assertThrows(ApiException.class,
                () -> d.api().getOrder(new AdminOrderDetailQuery("abc", operator(), AUTH)));
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, invalid.code());
            var logs = d.api().listStatusLogs(new AdminStatusLogQuery("1", operator(), AUTH));
            assertEquals(2, logs.total());
            assertEquals("PAYMENT", logs.items().get(0).dimension());
            assertEquals("ORDER_STAGE", logs.items().get(1).dimension());
        }
    }

    @Test void authorizationIsRecheckedInsideTheKernelAndFailsClosed() throws Exception {
        try (Database d = new Database()) {
            d.seedOrder(1, 1001L, 7L, 8L, "PENDING_SERVICE", "PAID", "0.00", 0);
            // Action denied at the collection entry: no rows, COMMON_FORBIDDEN.
            ApiException denied = assertThrows(ApiException.class,
                () -> d.api(false, true, true).listOrders(list(null, null, null, false)));
            assertEquals(CommonApiCodes.FORBIDDEN, denied.code());
            // Resource scope denied on a single read must read as NOT_FOUND (anti-enumeration).
            ApiException scope = assertThrows(ApiException.class,
                () -> d.api(true, false, true).getOrder(new AdminOrderDetailQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.NOT_FOUND, scope.code());
            // An unavailable decision never reads.
            ApiException unavailable = assertThrows(ApiException.class,
                () -> d.api(true, true, false).getOrder(new AdminOrderDetailQuery("1", operator(), AUTH)));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, unavailable.code());
            // A non-platform operator never reads, even with an allowing port.
            ApiException identity = assertThrows(ApiException.class,
                () -> d.api().getOrder(new AdminOrderDetailQuery("1",
                    new QueryContext("trace", OperatorType.USER, "42"), AUTH)));
            assertEquals(CommonApiCodes.FORBIDDEN, identity.code());
        }
    }

    private static QueryContext operator() {
        return new QueryContext("trace", OperatorType.PLATFORM_OPERATOR, "42");
    }

    private static AdminOrderListQuery list(String display, String payment, String keyword, boolean rescheduled) {
        return new AdminOrderListQuery(display, payment, null, null, null, keyword, null, null,
            rescheduled, 1, 20, operator(), AUTH);
    }

    private static final class Database implements AutoCloseable {
        final String name = "admorder_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate admin;
        final DataSource source;
        final JdbcTemplate sql;

        Database() throws Exception {
            String prefix = System.getenv().containsKey("ADM_ORDER_MYSQL_URL") ? "ADM_ORDER"
                : System.getenv().containsKey("ORDER_CREATE_MYSQL_URL") ? "ORDER_CREATE"
                : System.getenv().containsKey("AUTH_MYSQL_URL") ? "AUTH" : "ADM_ORDER";
            String url = System.getenv().getOrDefault(prefix + "_MYSQL_URL", "jdbc:mysql://127.0.0.1:33459/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("ADM_ORDER_MYSQL_URL must target local server root");
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
                        "45-Merchant-Order-Actions-Schema-v0.1.sql", "46-Order-Reschedule-Schema-v0.1.sql",
                        "48-Verification-Completion-Schema-v0.1.sql", "49-Refund-Application-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new FileSystemResource(root.resolve("docs/03-database/" + file)),
                            StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        AdminOrderQueryApiImpl api() { return api(true, true, true); }

        /** Collection entry / resource scope / availability switches for the authorization port. */
        AdminOrderQueryApiImpl api(boolean collectionAllowed, boolean scopeAllowed, boolean available) {
            return new AdminOrderQueryApiImpl(source, new AdminTradeAuthorizationPort.OrderAuthorizationPort() {
                @Override public AdminTradeAuthorizationPort.Decision check(AdminTradeAuthorizationPort.Check check) {
                    if (!available) return null;
                    if (!scopeAllowed) return new AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "RESOURCE_SCOPE_DENIED");
                    return new AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }

                @Override public AdminTradeAuthorizationPort.Decision checkCollection(AdminTradeAuthorizationPort.CollectionCheck check) {
                    if (!available) return null;
                    if (!collectionAllowed) return new AdminTradeAuthorizationPort.Decision(false, CHECKED, "1:1", "ACTION_NOT_GRANTED");
                    return new AdminTradeAuthorizationPort.Decision(true, CHECKED, "1:1", "ALLOWED");
                }
            });
        }

        void seedOrder(int id, long orderNo, long merchant, long store, String stage, String payment,
                String refunded, int reschedules) {
            sql.update("""
                INSERT INTO pet_order(id, order_no, user_id, merchant_id, store_id, service_id, pet_id,
                    reservation_id, fulfillment_type, order_stage, payment_status, verification_status,
                    original_amount, pay_amount, refunded_amount, reschedule_count,
                    appointment_start_at, appointment_end_at, created_at, updated_at, version)
                VALUES(?, ?, 42, ?, ?, 9, 5, ?, 'IN_STORE', ?, ?, 'UNVERIFIED',
                    '128.00', '128.00', ?, ?, '2026-09-01 08:00:00', '2026-09-01 09:00:00',
                    '2026-09-01 07:00:00', '2026-09-01 07:00:00', 0)
                """, (long) id, orderNo, merchant, store, (long) (100 + id), stage, payment, refunded, reschedules);
            sql.update("""
                INSERT INTO order_service_snapshot(id, order_id, service_id, service_name, service_price,
                    service_duration_minutes, merchant_name, store_name, store_address, created_at)
                VALUES(?, ?, 9, '中型犬洗护', '128.00', 60, '可爱宠物店', '朝阳店',
                    ' recorded value', '2026-09-01 07:00:00')
                """, (long) (900 + id), (long) id);
        }

        void seedReschedule(int orderId) {
            sql.update("""
                INSERT INTO order_reschedule_record(id, order_id, command_id, event_id, user_id, store_id,
                    reservation_id, old_order_version, new_order_version, old_reservation_version,
                    new_reservation_version, old_order_stage, old_confirm_deadline, old_start, old_end,
                    new_start, new_end, rescheduled_at, new_confirm_deadline, from_round, to_round,
                    schedule_change_id, verification_fence_id, old_task_outcome)
                VALUES(900, ?, 901, 902, 42, 8, 103, 3, 4, 5, 6, 'PENDING_CONFIRM',
                    '2026-09-01 07:30:00', '2026-09-01 08:00:00', '2026-09-01 09:00:00',
                    '2026-09-02 08:00:00', '2026-09-02 09:00:00', '2026-09-01 07:10:00',
                    '2026-09-01 07:40:00', 0, 1, 903, 904, 'OK')
                """, (long) orderId);
        }

        void seedStatusLog(int orderId, long id, String dimension, String from, String to, String event, int minute) {
            sql.update("""
                INSERT INTO order_status_log(id, order_id, dimension, from_status, to_status, event_type,
                    operator_type, operator_id, remark, created_at)
                VALUES(?, ?, ?, ?, ?, ?, 'SYSTEM', NULL, NULL, ?)
                """, id, (long) orderId, dimension, from, to, event,
                LocalDateTime.of(2026, 9, 1, 7, minute).atOffset(ZoneOffset.UTC).toLocalDateTime());
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
