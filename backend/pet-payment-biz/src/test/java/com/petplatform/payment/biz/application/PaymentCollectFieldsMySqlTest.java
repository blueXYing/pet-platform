package com.petplatform.payment.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.event.api.IntegrationEvent;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.order.api.dto.OrderPaymentFact;
import com.petplatform.order.api.query.OrderPaymentFactsApi;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;
import com.petplatform.payment.biz.apiimpl.PaymentPreparationApiImpl;
import com.petplatform.payment.biz.infrastructure.persistence.PaymentFoundationStore;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.BookingUserFactsApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * CCR-PAY-CENTER-001 stage 1 (WP-B): settle_type persistence, log_no/log_date collect trace, and the
 * SQL75 constraints, against an isolated local MySQL database (schemas 06/40/41/75).
 *
 * <p>NOT RUN in the authoring environment: it has no MySQL. Run with PAYMENT_REFUND_MYSQL_URL
 * (jdbc:mysql://127.0.0.1:PORT/) or PAYMENT_COLLECT_MYSQL_URL plus optional _USER/_PASSWORD.
 */
class PaymentCollectFieldsMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(9_170_000_000_000_000L);
    private static final String USER = "7701008", MERCHANT = "7701007", STORE = "7701006";
    private static final String MERCHANT_NO = "QA_MERCHANT_01", TERM_NO = "QA001", SUB_APP = "wxQaSubApp";
    private static final BigDecimal AMOUNT = new BigDecimal("128.00");
    private static final ScheduleCapacityGuardApi GUARD = new ScheduleCapacityGuardApi() {
        @Override public void acquire(List<String> storeIds, com.petplatform.common.QueryContext context) {}
        @Override public void requireHeld(String storeId, DataSource callerSource) {}
    };

    // ---- settle_type written at prepare time -------------------------------------------------

    @Test void prepareStoresTheConfiguredSettleTypeAndLeavesCenterColumnsNull() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            String orderOne = "7701101", orderTwo = "7701102", orderThree = "7701103";
            prepare(db, orderOne, "req-settle-1", new PaymentPreparationApiImpl(db.source, IDS::incrementAndGet,
                    GUARD, orders(orderOne), users(), bindings(), "1"));
            prepare(db, orderTwo, "req-settle-0", new PaymentPreparationApiImpl(db.source, IDS::incrementAndGet,
                    GUARD, orders(orderTwo), users(), bindings(), "0"));
            // The pre-existing six-argument constructor keeps ordinary settlement.
            prepare(db, orderThree, "req-settle-legacy", new PaymentPreparationApiImpl(db.source,
                    IDS::incrementAndGet, GUARD, orders(orderThree), users(), bindings()));
            assertEquals("1", db.text("SELECT settle_type FROM payment_order WHERE order_id=" + orderOne));
            assertEquals("0", db.text("SELECT settle_type FROM payment_order WHERE order_id=" + orderTwo));
            assertEquals("0", db.text("SELECT settle_type FROM payment_order WHERE order_id=" + orderThree));
            assertEquals(3L, db.count("SELECT COUNT(*) FROM payment_order WHERE biz_type IS NULL"
                    + " AND biz_no IS NULL AND log_no IS NULL AND log_date IS NULL"));
        }
    }

    @Test void preparationRejectsAnUnknownSettleType() {
        for (String bad : new String[] {null, "", "2", " 1"}) {
            assertThrows(IllegalArgumentException.class, () -> new PaymentPreparationApiImpl(
                    mock(DataSource.class), IDS::incrementAndGet, GUARD, mock(OrderPaymentFactsApi.class),
                    mock(BookingUserFactsApi.class), bindings(), bad), "settleType=" + bad);
        }
    }

    /** Runs without MySQL: MyBatis maps the record by column ORDER, so list and record must stay aligned. */
    @Test void rowRecordAndMapperColumnListStayAlignedAtTheTail() throws Exception {
        String xml;
        try (var in = PaymentFoundationStore.class.getResourceAsStream("/mapper/PaymentFoundationMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = java.util.regex.Pattern.compile("<sql id=\"paymentColumns\">([^<]+)</sql>").matcher(xml);
        assertTrue(matcher.find());
        String[] columns = matcher.group(1).trim().split(",");
        var components = PaymentFoundationStore.Row.class.getRecordComponents();
        assertEquals(components.length, columns.length);
        String[] tail = {"biz_type", "biz_no", "settle_type", "log_no", "log_date"};
        String[] names = {"bizType", "bizNo", "settleType", "logNo", "logDate"};
        for (int i = 0; i < tail.length; i++) {
            assertEquals(tail[i], columns[columns.length - tail.length + i]);
            assertEquals(names[i], components[components.length - tail.length + i].getName());
        }
        assertEquals(LocalDate.class, components[components.length - 1].getType());
        // The ORDER insert persists settle_type but never invents biz_type/biz_no/log columns.
        String insert = xml.substring(xml.indexOf("<insert id=\"insertPayment\">"), xml.indexOf("</insert>", xml.indexOf("<insert id=\"insertPayment\">")));
        assertTrue(insert.contains("settle_type") && insert.contains("#{settleType}"));
        assertFalse(insert.contains("biz_type") || insert.contains("log_no"));
        String fill = xml.substring(xml.indexOf("<update id=\"fillCollectTrace\">"), xml.indexOf("</update>", xml.indexOf("<update id=\"fillCollectTrace\">")));
        assertTrue(fill.contains("log_no IS NULL AND log_date IS NULL") && fill.contains("status='PAID'"));
    }

    // ---- historical rows ---------------------------------------------------------------------

    @Test void historicalRowsReadNullForEveryNewColumn() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            // The row was inserted exactly as pre-SQL75 code did, without any new column.
            assertNull(db.jdbc.queryForObject("SELECT settle_type FROM payment_order WHERE id=?", String.class, id));
            PaymentFoundationStore.Row row = new PaymentFoundationStore(db.source).byId(id, false);
            assertNotNull(row);
            assertNull(row.bizType());
            assertNull(row.bizNo());
            assertNull(row.settleType());
            assertNull(row.logNo());
            assertNull(row.logDate());
            assertEquals(AMOUNT, row.amount());
            assertEquals("LAKALA_WECHAT", row.channel());
        }
    }

    // ---- collect trace -----------------------------------------------------------------------

    @Test void successNotificationWithTraceWritesLogNoAndLogDateWithPaid() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", "20241010123456", LocalDate.of(2024, 10, 10)));
            var result = fixture.service.receive(Map.of("k", "v"), fixture.body());
            assertTrue(result.paid());
            assertFalse(result.replayed());
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertEquals("20241010123456", db.text("SELECT log_no FROM payment_order WHERE id=" + id));
            assertEquals("2024-10-10", db.text("SELECT CAST(log_date AS CHAR) FROM payment_order WHERE id=" + id));
            assertEquals(1, fixture.events.size());
            assertEquals("PaymentSucceededEvent.v1", fixture.events.getFirst().eventType());
        }
    }

    @Test void successWithoutTraceStillPaysAndLeavesTraceNull() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", null, null));
            assertTrue(fixture.service.receive(Map.of(), fixture.body()).paid());
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertNull(db.jdbc.queryForObject("SELECT log_no FROM payment_order WHERE id=" + id, String.class));
            assertNull(db.jdbc.queryForObject("SELECT log_date FROM payment_order WHERE id=" + id, LocalDate.class));
        }
    }

    @Test void halfPresentTraceFromTheChannelIsIgnoredNotAConstraintFailure() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", "20241010123456", null));
            assertTrue(fixture.service.receive(Map.of(), fixture.body()).paid());
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertNull(db.jdbc.queryForObject("SELECT log_no FROM payment_order WHERE id=" + id, String.class));
        }
    }

    @Test void laterQueryFillsTheTraceExactlyOnceWithoutASecondEvent() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", null, null));
            fixture.service.receive(Map.of(), fixture.body());
            long versionBefore = db.count("SELECT version FROM payment_order WHERE id=" + id);

            var replay = fixture.service.receiveQuery(Long.toString(id), fixture.query("TRADE-1", "A1B2C3", LocalDate.of(2024, 10, 10), "a"));
            assertTrue(replay.replayed());
            assertTrue(replay.paid());
            assertEquals("A1B2C3", db.text("SELECT log_no FROM payment_order WHERE id=" + id));
            assertEquals(versionBefore + 1, db.count("SELECT version FROM payment_order WHERE id=" + id));

            // A different value later must not overwrite the first accepted trace.
            fixture.service.receiveQuery(Long.toString(id), fixture.query("TRADE-1", "ZZZZZZ", LocalDate.of(2024, 10, 11), "b"));
            assertEquals("A1B2C3", db.text("SELECT log_no FROM payment_order WHERE id=" + id));
            assertEquals("2024-10-10", db.text("SELECT CAST(log_date AS CHAR) FROM payment_order WHERE id=" + id));
            assertEquals(1, fixture.events.size());
        }
    }

    @Test void traceInAConflictingSuccessReceiptIsNotWritten() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", null, null));
            fixture.service.receive(Map.of(), fixture.body());
            // Same payment, different channel trade number: the consistency check rejects it first.
            assertThrows(com.petplatform.common.ApiException.class, () -> fixture.service.receiveQuery(
                    Long.toString(id), fixture.query("TRADE-OTHER", "A1B2C3", LocalDate.of(2024, 10, 10), "c")));
            assertNull(db.jdbc.queryForObject("SELECT log_no FROM payment_order WHERE id=" + id, String.class));
        }
    }

    @Test void nonSuccessReceiptsNeverWriteATrace() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.service.receiveQuery(Long.toString(id), fixture.queryWithState(LakalaProtocol.TradeState.DEAL,
                    "A1B2C3", LocalDate.of(2024, 10, 10), "d"));
            assertEquals("PAYING", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertNull(db.jdbc.queryForObject("SELECT log_no FROM payment_order WHERE id=" + id, String.class));
            assertEquals(0, fixture.events.size());
        }
    }

    @Test void failOrDealAfterPaidDoesNotWriteATrace() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            var fixture = new Fixture(db);
            fixture.notice.set(fixture.success("TRADE-1", null, null));
            fixture.service.receive(Map.of(), fixture.body());
            fixture.service.receiveQuery(Long.toString(id), fixture.queryWithState(LakalaProtocol.TradeState.FAIL,
                    "A1B2C3", LocalDate.of(2024, 10, 10), "e"));
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertNull(db.jdbc.queryForObject("SELECT log_no FROM payment_order WHERE id=" + id, String.class));
        }
    }

    // ---- SQL75 constraints -------------------------------------------------------------------

    @Test void checkConstraintsRejectInvalidCombinations() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            long id = db.insertOrderPayment("INIT", "PREPARED");
            assertThrows(DataAccessException.class, () -> db.jdbc.update(
                    "UPDATE payment_order SET log_no='A1' WHERE id=?", id), "log_no without log_date");
            assertThrows(DataAccessException.class, () -> db.jdbc.update(
                    "UPDATE payment_order SET log_date='2024-10-10' WHERE id=?", id), "log_date without log_no");
            assertThrows(DataAccessException.class, () -> db.jdbc.update(
                    "UPDATE payment_order SET settle_type='2' WHERE id=?", id), "settle_type outside 0/1");
            assertThrows(DataAccessException.class, () -> db.jdbc.update(
                    "UPDATE payment_order SET biz_type='DEMO' WHERE id=?", id), "biz_type without biz_no");
            assertThrows(DataAccessException.class, () -> db.jdbc.update(
                    "UPDATE payment_order SET order_id=NULL WHERE id=?", id), "ORDER row without order_id and biz pair");
            db.jdbc.update("UPDATE payment_order SET settle_type='1',log_no='A1',log_date='2024-10-10' WHERE id=?", id);
        }
    }

    @Test void paymentCenterRowsMayOmitOrderIdButBizPairIsUnique() throws Exception {
        try (Db db = new Db()) {
            db.setup();
            db.insertCenterPayment("DEMO", "BIZ-1");
            assertThrows(DataAccessException.class, () -> db.insertCenterPayment("DEMO", "BIZ-1"));
            db.insertCenterPayment("DEMO", "BIZ-2");
            assertEquals(2L, db.count("SELECT COUNT(*) FROM payment_order WHERE order_id IS NULL"));
        }
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static PaymentMerchantBindings bindings() {
        return (merchantId, storeId) -> new PaymentMerchantBindings.Binding(MERCHANT_NO, TERM_NO, SUB_APP);
    }

    private static BookingUserFactsApi users() { return mock(BookingUserFactsApi.class); }

    private static OrderPaymentFactsApi orders(String orderId) {
        OrderPaymentFactsApi orders = mock(OrderPaymentFactsApi.class);
        when(orders.locateStore(eq(orderId), any())).thenReturn(STORE);
        when(orders.readForPayment(eq(orderId), eq(STORE), any())).thenReturn(new OrderPaymentFact(orderId,
                USER, MERCHANT, STORE, "7701200", "PENDING_PAYMENT", "UNPAID", "NONE", AMOUNT,
                BigDecimal.ZERO, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS)));
        return orders;
    }

    private static void prepare(Db db, String orderId, String requestId, PaymentPreparationApiImpl api) {
        var prepared = api.prepare(new PreparePaymentCommand(new CommandContext(requestId, "trace-" + requestId,
                OperatorType.USER, USER, "TEST"), orderId));
        assertFalse(prepared.replayed());
        assertEquals(orderId, prepared.orderId());
    }

    private static final class Fixture {
        final Db db;
        final List<IntegrationEvent<?>> events = new ArrayList<>();
        final AtomicReference<PaymentReceiptVerifier.VerifiedNotice> notice = new AtomicReference<>();
        final PaymentNotificationService service;

        Fixture(Db db) {
            this.db = db;
            IntegrationEventPublisher publisher = events::add;
            // UTC keeps the offline fixture independent of the (still unconfirmed) production channel zone.
            service = new PaymentNotificationService(db.source, IDS::incrementAndGet, GUARD,
                    (headers, body, expected) -> notice.get(), publisher, ZoneId.of("UTC"));
        }

        byte[] body() {
            return ("{\"out_trade_no\":\"" + Db.PAYMENT_NO + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        PaymentReceiptVerifier.VerifiedNotice success(String tradeNo, String logNo, LocalDate logDate) {
            return new PaymentReceiptVerifier.VerifiedNotice(MERCHANT_NO, Long.toString(Db.PAYMENT_NO), tradeNo,
                    "SUCCESS", AMOUNT, AMOUNT, tradeTime(), "WECHAT", logNo, logDate);
        }

        PaymentChannel.VerifiedQuery query(String tradeNo, String logNo, LocalDate logDate, String shaSeed) {
            return queryWithState(LakalaProtocol.TradeState.SUCCESS, tradeNo, logNo, logDate, shaSeed);
        }

        PaymentChannel.VerifiedQuery queryWithState(LakalaProtocol.TradeState state, String logNo,
                LocalDate logDate, String shaSeed) {
            return queryWithState(state, "TRADE-1", logNo, logDate, shaSeed);
        }

        PaymentChannel.VerifiedQuery queryWithState(LakalaProtocol.TradeState state, String tradeNo, String logNo,
                LocalDate logDate, String shaSeed) {
            boolean success = state == LakalaProtocol.TradeState.SUCCESS;
            var result = new LakalaProtocol.QueryResult(MERCHANT_NO, Long.toString(Db.PAYMENT_NO), tradeNo, state,
                    12_800L, success ? 12_800L : null, success ? tradeTime() : null, "WECHAT", logNo, logDate);
            return new PaymentChannel.VerifiedQuery(result, shaSeed.repeat(64).substring(0, 64));
        }

        /** Fixed per fixture: notification and query must report the same channel payment time. */
        private final LocalDateTime tradeTime = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)
                .truncatedTo(ChronoUnit.SECONDS);
        private LocalDateTime tradeTime() { return tradeTime; }
    }

    private static final class Db implements AutoCloseable {
        static final long PAYMENT_ID = 7_701_002L, PAYMENT_NO = 7_701_003L, ORDER_ID = 7_701_001L;
        private final String name = "payment_collect_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = env("PAYMENT_COLLECT_MYSQL_URL", env("PAYMENT_REFUND_MYSQL_URL", null));
        private final String user = env("PAYMENT_COLLECT_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
        private final String password = env("PAYMENT_COLLECT_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", ""));
        final DataSource source;
        final JdbcTemplate jdbc;
        private final JdbcTemplate admin;
        private boolean created;
        private long nextBiz = 100;

        Db() {
            if (url == null || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException("dedicated local MySQL URL required");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
        }

        private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }

        void setup() throws Exception {
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try (Connection connection = source.getConnection()) {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")))
                    root = root.getParent();
                if (root == null) throw new IllegalStateException("schema root absent");
                for (String file : List.of("06-核心数据库Schema-v0.1.sql", "40-Payment-Foundation-Schema-v0.1.sql",
                        "41-Payment-Dispatch-Schema-v0.1.sql", "75-Payment-Center-Schema-v0.1.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                            root.resolve("docs/03-database").resolve(file)), StandardCharsets.UTF_8));
                }
            }
        }

        /** A payment row written with the pre-SQL75 column list only (no biz/settle/log column). */
        long insertOrderPayment(String status, String dispatchState) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,expire_at,version,"
                            + "created_at,updated_at,store_id,merchant_id,user_id,merchant_no,term_no,sub_appid,"
                            + "currency,dispatch_state) VALUES(?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,?)",
                    PAYMENT_ID, PAYMENT_NO, ORDER_ID, AMOUNT, status, "LAKALA_WECHAT", now.plusMinutes(10), now, now,
                    Long.parseLong(STORE), Long.parseLong(MERCHANT), Long.parseLong(USER), MERCHANT_NO, TERM_NO,
                    SUB_APP, "CNY", dispatchState);
            return PAYMENT_ID;
        }

        void insertCenterPayment(String bizType, String bizNo) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            long id = ++nextBiz + 8_000_000L;
            jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,biz_type,biz_no,amount,status,channel,"
                            + "expire_at,version,created_at,updated_at,user_id,merchant_no,term_no,sub_appid,currency,"
                            + "dispatch_state,settle_type) VALUES(?,?,NULL,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?)",
                    id, id + 1_000L, bizType, bizNo, AMOUNT, "INIT", "LAKALA_WECHAT", now.plusMinutes(10), now, now,
                    Long.parseLong(USER), MERCHANT_NO, TERM_NO, SUB_APP, "CNY", "PREPARED", "1");
        }

        long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
        String text(String sql) { return jdbc.queryForObject(sql, String.class); }

        private static DataSource dataSource(String url, String user, String password) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC", user, password);
        }

        @Override public void close() {
            if (created) admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
