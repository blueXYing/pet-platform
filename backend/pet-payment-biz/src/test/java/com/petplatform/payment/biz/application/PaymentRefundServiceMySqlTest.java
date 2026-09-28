package com.petplatform.payment.biz.application;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderLatePaymentFact;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;
import com.petplatform.payment.api.dto.PaymentRefundTypes.CoordinationState;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Isolated MySQL state-machine test. The channel is an explicit offline fixture. */
class PaymentRefundServiceMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(9_090_000_000_000_000L);
    private static final String ORDER = "7601001";
    private static final String PAYMENT = "7601002";
    private static final String PAYMENT_NO = "7601003";
    private static final String REFUND = "7601004";
    private static final String REFUND_NO = "7601005";
    private static final String STORE = "7601006";
    private static final String MERCHANT = "7601007";
    private static final String USER = "7601008";
    private static final String EVENT = "7601009";
    private static final String TRADE = "QA_TRADE_001";
    private static final BigDecimal AMOUNT = new BigDecimal("128.00");

    @Test void unknownIsQueriedUnderOriginalNumberAfterDatabaseDeadline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.channel.submitThrows = true;
            var first = f.service.submitRefund(f.submit());
            assertEquals(CoordinationState.QUERY_PENDING, first.state());
            assertEquals(1, f.channel.submits.get());
            assertEquals(0, f.channel.queries.get());
            assertTrue(first.queryNotBefore().isAfter(f.db.now()));
            assertEquals("QUERY_PENDING", f.db.text("SELECT state FROM payment_refund_dispatch"));
            assertEquals(1L, f.db.count("SELECT COUNT(*) FROM payment_refund_dispatch"));

            var early = f.service.submitRefund(f.submit());
            assertEquals(CoordinationState.QUERY_PENDING, early.state());
            assertEquals(1, f.channel.submits.get());
            assertEquals(0, f.channel.queries.get());
            assertEquals(CoordinationState.QUERY_PENDING, f.service.queryRefund(f.query()).state());
            assertEquals(0, f.channel.queries.get());

            // Simulate elapsed time without waiting or trusting the process Clock: the service
            // still compares against database UTC time before allowing the original-number query.
            f.db.jdbc.update("UPDATE payment_refund_dispatch SET query_not_before=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
            f.channel.submitThrows = false;
            f.channel.queryResult = f.channel.success();
            var finalProgress = f.service.queryRefund(f.query());
            assertEquals(CoordinationState.VERIFIED_SUCCESS, finalProgress.state());
            assertEquals(1, f.channel.submits.get());
            assertEquals(1, f.channel.queries.get());
            assertEquals(1L, f.db.count("SELECT COUNT(*) FROM payment_refund_receipt"));
            var fact = new TransactionTemplate(new DataSourceTransactionManager(f.db.source))
                    .execute(status -> f.results.requireVerified(REFUND, REFUND_NO, PAYMENT,
                            STORE, f.context));
            assertNotNull(fact);
            assertEquals(0, fact.refundAmount().compareTo(AMOUNT));
            assertEquals("QA_CHANNEL_REFUND", fact.channelRefundNo());
            assertEquals(TRADE, fact.originalChannelTradeNo());
        }
    }

    @Test void signedSuccessWithWrongActualAmountCannotAuthorizeBusinessSuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            f.channel.queryResult = null;
            f.channel.submitResult = new PaymentRefundChannel.VerifiedResult("SUCCESS", REFUND_NO,
                    "QA_CHANNEL_REFUND", 12_800, 12_700L,
                    LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS), "a".repeat(64));
            var progress = f.service.submitRefund(f.submit());
            assertEquals(CoordinationState.RECONCILIATION_REQUIRED, progress.state());
            assertEquals(1, f.channel.submits.get());
            assertEquals(0, f.channel.queries.get());
            assertEquals("RECONCILIATION_REQUIRED", f.db.text("SELECT state FROM payment_refund_dispatch"));
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(
                    new DataSourceTransactionManager(f.db.source)).execute(status ->
                    f.results.requireVerified(REFUND, REFUND_NO, PAYMENT, STORE, f.context)));
            assertEquals(CoordinationState.RECONCILIATION_REQUIRED,
                    f.service.submitRefund(f.submit()).state());
            assertEquals(1, f.channel.submits.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Database db = new Database();
        final FakeChannel channel = new FakeChannel();
        final QueryContext context = new QueryContext("QA", OperatorType.SYSTEM, null);
        final ScheduleCapacityGuardApi guard = new ScheduleCapacityGuardApi() {
            @Override public void acquire(List<String> stores, QueryContext context) {
                assertEquals(List.of(STORE), stores);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            }
            @Override public void requireHeld(String store, DataSource caller) {
                assertEquals(STORE, store);
                assertSame(db.source, caller);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            }
        };
        final PaymentRefundService service;
        final PaymentRefundResultFactsApiImpl results;

        Fixture() throws Exception {
            db.setup();
            OffsetDateTime paid = db.paidAt();
            OrderLatePaymentFactsApi orders = new OrderLatePaymentFactsApi() {
                @Override public String locateStore(String orderId, QueryContext ctx) { return STORE; }
                @Override public OrderLatePaymentFact requireLatePayment(String orderId,
                        String paymentId, String storeId, QueryContext ctx) {
                    guard.requireHeld(storeId, db.source);
                    return new OrderLatePaymentFact(ORDER, STORE, MERCHANT, USER, "7601010",
                            PAYMENT, EVENT, TRADE, AMOUNT, paid);
                }
            };
            RefundExecutionFactsApi business = new RefundExecutionFactsApi() {
                @Override public RefundExecutionFact requireForChannel(String refundOrderId,
                        String storeId, QueryContext ctx) {
                    guard.requireHeld(storeId, db.source);
                    return new RefundExecutionFact(REFUND, REFUND_NO, ORDER, PAYMENT, PAYMENT_NO,
                            STORE, MERCHANT, USER, EVENT, "7601011", TRADE, AMOUNT, AMOUNT,
                            paid, "CNY", "CREATED", 0, "7601012", db.now());
                }
                @Override public RefundSuccessFact requireSucceeded(String refundOrderId,
                        String orderId, String storeId, QueryContext context) {
                    throw new UnsupportedOperationException();
                }
            };
            service = new PaymentRefundService(db.source, IDS::incrementAndGet, guard, orders,
                    new PaymentSuccessFactsApiImpl(db.source, guard), business, channel,
                    new PaymentRefundService.Settings("127.0.0.1", null, ZoneId.of("UTC")),
                    Clock.fixed(db.now().toInstant(), ZoneOffset.UTC));
            results = new PaymentRefundResultFactsApiImpl(db.source, guard);
        }

        ChannelRefundSubmitCommand submit() {
            return new ChannelRefundSubmitCommand(new CommandContext(
                    "TASK:REFUND_SUBMIT:" + REFUND + ":0", "QA", OperatorType.SYSTEM, null,
                    "ASYNC_TASK"), REFUND, REFUND_NO, PAYMENT, STORE, 0);
        }
        ChannelRefundQuery query() {
            return new ChannelRefundQuery(new CommandContext(
                    "TASK:REFUND_CHANNEL_QUERY:" + REFUND + ":0", "QA", OperatorType.SYSTEM,
                    null, "ASYNC_TASK"), REFUND, REFUND_NO, PAYMENT, STORE, 0);
        }
        @Override public void close() { db.close(); }
    }

    private static final class FakeChannel implements PaymentRefundChannel {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        volatile boolean submitThrows;
        volatile VerifiedResult submitResult;
        volatile VerifiedResult queryResult;
        @Override public VerifiedResult submit(RefundRequest request) {
            submits.incrementAndGet();
            assertEquals(REFUND_NO, request.refundNo());
            assertEquals(TRADE, request.originalChannelTradeNo());
            if (submitThrows) throw new IllegalStateException("offline timeout");
            return submitResult;
        }
        @Override public VerifiedResult query(RefundRequest request) {
            queries.incrementAndGet();
            assertEquals(REFUND_NO, request.refundNo());
            return queryResult;
        }
        VerifiedResult success() {
            return new VerifiedResult("SUCCESS", REFUND_NO, "QA_CHANNEL_REFUND", 12_800,
                    12_800L, LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
                    "b".repeat(64));
        }
    }

    private static final class Database implements AutoCloseable {
        private final String name = "payment_refund_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
        private final String user = System.getenv().getOrDefault("PAYMENT_REFUND_MYSQL_USER", "root");
        private final String password = System.getenv().getOrDefault("PAYMENT_REFUND_MYSQL_PASSWORD", "");
        final DataSource source;
        final JdbcTemplate jdbc;
        private final JdbcTemplate admin;
        private boolean created;

        Database() {
            if (url == null || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException("dedicated local MySQL URL required");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
        }

        void setup() throws Exception {
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try (Connection connection = source.getConnection()) {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve(
                        "docs/03-database/06-核心数据库Schema-v0.1.sql"))) root = root.getParent();
                if (root == null) throw new IllegalStateException("schema root absent");
                for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                        "40-Payment-Foundation-Schema-v0.1.sql",
                        "43-Payment-Refund-Dispatch-Schema-v0.1.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                            root.resolve("docs/03-database").resolve(file)), StandardCharsets.UTF_8));
                }
            }
            OffsetDateTime now = now();
            OffsetDateTime paid = now.minusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
            jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,"
                            + "expire_at,paid_at,version,created_at,updated_at,store_id,merchant_id,"
                            + "user_id,merchant_no,term_no,sub_appid,currency,dispatch_state,"
                            + "channel_trade_no,channel_paid_amount,success_event_id)"
                            + " VALUES(?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Long.parseLong(PAYMENT), Long.parseLong(PAYMENT_NO), Long.parseLong(ORDER),
                    AMOUNT, "PAID", "LAKALA_WECHAT", now.plusMinutes(10), paid, now, now,
                    Long.parseLong(STORE), Long.parseLong(MERCHANT), Long.parseLong(USER),
                    "QA_MERCHANT_01", "QA001", "wxQaSubApp", "CNY", "OBSERVED", TRADE,
                    AMOUNT, Long.parseLong(EVENT));
            jdbc.update("INSERT INTO payment_channel_receipt(id,payment_id,receipt_sha256,"
                            + "channel_trade_no,channel_status,total_amount,paid_amount,paid_at,received_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                    IDS.incrementAndGet(), Long.parseLong(PAYMENT), "c".repeat(64), TRADE,
                    "SUCCESS", AMOUNT, AMOUNT, paid, now);
        }

        OffsetDateTime now() {
            return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class)
                    .atOffset(ZoneOffset.UTC);
        }
        OffsetDateTime paidAt() {
            return jdbc.queryForObject("SELECT paid_at FROM payment_order WHERE id=?",
                    LocalDateTime.class, Long.parseLong(PAYMENT)).atOffset(ZoneOffset.UTC);
        }
        long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
        String text(String sql) { return jdbc.queryForObject(sql, String.class); }
        private static DataSource dataSource(String url, String user, String password) {
            return new DriverManagerDataSource(url
                    + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password);
        }
        @Override public void close() {
            if (created) admin.execute("DROP DATABASE `" + name + "`");
            created = false;
        }
    }
}
