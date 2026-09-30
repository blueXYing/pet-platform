package com.petplatform.payment.biz.application;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderLatePaymentFact;
import com.petplatform.order.api.dto.OrderRefundOriginFact;
import com.petplatform.order.api.query.OrderLatePaymentFactsApi;
import com.petplatform.order.api.query.OrderMerchantRejectFactsApi;
import com.petplatform.order.api.query.OrderRefundApplicationFactsApi;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;
import com.petplatform.payment.api.dto.PaymentRefundTypes.CoordinationState;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.refund.api.dto.RefundExecutionFact;
import com.petplatform.refund.api.dto.RefundSuccessFact;
import com.petplatform.refund.api.query.RefundExecutionFactsApi;
import com.petplatform.refund.api.query.RefundApplicationApprovalFactsApi;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String APPLICATION = "7601020";
    private static final String DECISION = "7601021";

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

    @ParameterizedTest
    @ValueSource(strings = {"MERCHANT_APPROVED", "MERCHANT_TIMEOUT_AUTO"})
    void ordinarySourcesQueryOriginalNumberAfterReversalWithoutRepeatingFirstSendAdmission(String source)
            throws Exception {
        try (Fixture f = new Fixture(source)) {
            f.channel.submitThrows = true;
            assertEquals(CoordinationState.QUERY_PENDING, f.service.submitRefund(f.submit()).state());
            assertEquals(source, f.channel.lastReason);
            assertEquals(1, f.channel.submits.get());
            assertEquals(2, f.approvalReads.get(), "prepare and sole-send preflight verify the source");
            f.db.jdbc.update("UPDATE payment_order SET status='REFUND'");
            f.db.jdbc.update("UPDATE payment_refund_dispatch SET query_not_before=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
            f.defect = "SOURCE_UNAVAILABLE";
            f.channel.queryResult = f.channel.success();
            // A real reversal must not force a second PAID admission or a new submit.
            assertEquals(CoordinationState.VERIFIED_SUCCESS, f.service.queryRefund(f.query()).state());
            assertEquals(2, f.approvalReads.get());
            assertEquals(1, f.channel.submits.get());
            assertEquals(1, f.channel.queries.get());
            assertEquals(source, f.channel.lastReason);
            assertEquals(CoordinationState.VERIFIED_SUCCESS, f.service.submitRefund(f.submit()).state());
            assertEquals(1L, f.db.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DECISION", "PAYMENT", "AMOUNT", "ACTOR",
            "SOURCE_EVENT", "ORDER_DECISION", "SOURCE_UNAVAILABLE", "MISSING_SOURCE_ID", "UNKNOWN_SOURCE"})
    void forgedOrIncompleteOrdinarySourcesNeverReachTheChannel(String defect) throws Exception {
        try (Fixture f = new Fixture("MERCHANT_APPROVED")) {
            f.defect = defect;
            assertThrows(RuntimeException.class, () -> f.service.submitRefund(f.submit()), defect);
            assertEquals(0, f.channel.submits.get(), defect);
            assertEquals(0L, f.db.count("SELECT COUNT(*) FROM payment_refund_dispatch"), defect);
        }
    }

    @Test void automaticApprovalCannotUseOwnerDecisionIdentity() throws Exception {
        try (Fixture f = new Fixture("MERCHANT_TIMEOUT_AUTO")) {
            f.defect = "ACTOR";
            assertThrows(RuntimeException.class, () -> f.service.submitRefund(f.submit()));
            assertEquals(0, f.channel.submits.get());
        }
    }

    @Test void ordinarySourceWithoutBothNewProvidersFailsClosed() throws Exception {
        try (Fixture f = new Fixture("MERCHANT_APPROVED", false)) {
            assertThrows(RuntimeException.class, () -> f.service.submitRefund(f.submit()));
            assertEquals(0, f.channel.submits.get());
            assertEquals(0L, f.db.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
        }
    }

    @Test void sourceIsCheckedAgainAfterMayHaveSentBeforeTheOnlyNetworkCall() throws Exception {
        try (Fixture f = new Fixture("MERCHANT_APPROVED")) {
            f.defect = "PREFLIGHT_DECISION";
            assertThrows(RuntimeException.class, () -> f.service.submitRefund(f.submit()));
            assertEquals(2, f.approvalReads.get());
            assertEquals(0, f.channel.submits.get());
            assertEquals("MAY_HAVE_SENT", f.db.text("SELECT state FROM payment_refund_dispatch"));
            // The ambiguous dispatch is never submitted again, even after its source recovers.
            f.defect = "";
            assertEquals(CoordinationState.QUERY_PENDING, f.service.submitRefund(f.submit()).state());
            assertEquals(0, f.channel.submits.get());
        }
    }

    @Test void merchantRejectionSourceRetainsItsEventBinding() throws Exception {
        try (Fixture f = new Fixture("MERCHANT_REJECT_ORDER")) {
            f.channel.submitResult = f.channel.success();
            assertEquals(CoordinationState.VERIFIED_SUCCESS, f.service.submitRefund(f.submit()).state());
            assertEquals(1, f.channel.submits.get());
            assertEquals("MERCHANT_REJECT_ORDER", f.channel.lastReason);
            assertEquals(0, f.approvalReads.get());
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
        final AtomicInteger approvalReads = new AtomicInteger();
        String defect = "";

        Fixture() throws Exception { this("LATE_PAYMENT_TIMEOUT"); }
        Fixture(String refundSource) throws Exception { this(refundSource, true); }
        Fixture(String refundSource, boolean ordinaryProviders) throws Exception {
            db.setup();
            OffsetDateTime paid = db.paidAt();
            OffsetDateTime appliedAt = paid.plusSeconds(1);
            OffsetDateTime decidedAt = refundSource.equals("MERCHANT_TIMEOUT_AUTO")
                    ? appliedAt.plusHours(24) : appliedAt.plusSeconds(1);
            OffsetDateTime createdAt = decidedAt.plusSeconds(1);
            boolean ordinary = refundSource.equals("MERCHANT_APPROVED") || refundSource.equals("MERCHANT_TIMEOUT_AUTO");
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
                            STORE, MERCHANT, USER, EVENT,
                            refundSource.equals("LATE_PAYMENT_TIMEOUT") ? "7601011" : null,
                            TRADE, AMOUNT, AMOUNT, paid, "CNY", "CREATED", 0, "7601012", createdAt,
                            defect.equals("UNKNOWN_SOURCE") ? "AFTERSALE_DECISION" : refundSource,
                            ordinary && !defect.equals("SOURCE_EVENT") ? null : "7601011",
                            ordinary && !defect.equals("MISSING_SOURCE_ID") ? APPLICATION : null,
                            ordinary ? DECISION : null);
                }
                @Override public RefundSuccessFact requireSucceeded(String refundOrderId,
                        String orderId, String storeId, QueryContext context) {
                    throw new UnsupportedOperationException();
                }
            };
            OrderMerchantRejectFactsApi rejectedOrders = new OrderMerchantRejectFactsApi() {
                @Override public String locateStore(String id, QueryContext ctx) { return STORE; }
                @Override public OrderRefundOriginFact requireRejected(String id, String payment,
                        String store, QueryContext ctx) {
                    return new OrderRefundOriginFact(ORDER, STORE, MERCHANT, USER, "7601010",
                            PAYMENT, EVENT, TRADE, AMOUNT, paid, "MERCHANT_REJECT_ORDER", "7601011", REFUND);
                }
            };
            OrderRefundApplicationFactsApi ordinaryOrders = (id, payment, store, ctx) -> {
                guard.requireHeld(store, db.source);
                if (defect.equals("SOURCE_UNAVAILABLE")) throw new IllegalStateException("source unavailable");
                return new OrderRefundOriginFact(ORDER, STORE, MERCHANT, USER, "7601010",
                        PAYMENT, EVENT, TRADE, AMOUNT, paid, refundSource, null, REFUND,
                        APPLICATION, defect.equals("ORDER_DECISION") ? "7601999" : DECISION);
            };
            RefundApplicationApprovalFactsApi approvals = new RefundApplicationApprovalFactsApi() {
                @Override public ApprovalFact requireApproved(String app, String decision, String store,
                        QueryContext ctx) {
                    guard.requireHeld(store, db.source);
                    assertEquals(APPLICATION, app); assertEquals(DECISION, decision);
                    int reads = approvalReads.incrementAndGet();
                    if (defect.equals("SOURCE_UNAVAILABLE")) throw new IllegalStateException("approval unavailable");
                    boolean manual = refundSource.equals("MERCHANT_APPROVED");
                    var application = new ApplicationFact(APPLICATION, ORDER, STORE, MERCHANT, USER,
                            "7601010", defect.equals("PAYMENT") ? "7601999" : PAYMENT, PAYMENT_NO,
                            EVENT, TRADE, defect.equals("AMOUNT") ? new BigDecimal("127.00") : AMOUNT,
                            paid, defect.equals("PENDING") ? "PENDING_MERCHANT" : manual ? "APPROVED" : "AUTO_APPROVED",
                            1, appliedAt, appliedAt.plusHours(24), "7601022", DECISION, REFUND);
                    boolean wrongDecision = defect.equals("DECISION")
                            || defect.equals("PREFLIGHT_DECISION") && reads > 1;
                    boolean actualManual = defect.equals("ACTOR") ? !manual : manual;
                    return new ApprovalFact(application, wrongDecision ? "7601999" : DECISION,
                            refundSource, actualManual ? "USER" : "SYSTEM", actualManual ? USER : null,
                            decidedAt, "7601023", "7601024");
                }
                @Override public ApplicationFact requireApplication(String app, String store, QueryContext ctx) { throw new UnsupportedOperationException(); }
                @Override public DecisionFact requireDecision(String app, String decision, String store, QueryContext ctx) { throw new UnsupportedOperationException(); }
                @Override public CreatedFact requireCreated(String app, String decision, String refund, String store, QueryContext ctx) { throw new UnsupportedOperationException(); }
            };
            var paymentFacts = new PaymentSuccessFactsApiImpl(db.source, guard);
            var settings = new PaymentRefundService.Settings("127.0.0.1", null, ZoneId.of("UTC"));
            var clock = Clock.fixed(db.now().toInstant(), ZoneOffset.UTC);
            if (refundSource.equals("LATE_PAYMENT_TIMEOUT")) {
                service = new PaymentRefundService(db.source, IDS::incrementAndGet, guard, orders,
                        paymentFacts, business, channel, settings, clock);
            } else if (refundSource.equals("MERCHANT_REJECT_ORDER")) {
                service = new PaymentRefundService(db.source, IDS::incrementAndGet, guard, orders,
                        paymentFacts, business, channel, settings, clock, rejectedOrders);
            } else {
                service = new PaymentRefundService(db.source, IDS::incrementAndGet, guard, orders,
                        paymentFacts, business, channel, settings, clock, rejectedOrders,
                        ordinaryProviders ? ordinaryOrders : null, ordinaryProviders ? approvals : null);
            }
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
        volatile String lastReason;
        @Override public VerifiedResult submit(RefundRequest request) {
            submits.incrementAndGet();
            lastReason = request.reason();
            assertEquals(REFUND_NO, request.refundNo());
            assertEquals(TRADE, request.originalChannelTradeNo());
            if (submitThrows) throw new IllegalStateException("offline timeout");
            return submitResult;
        }
        @Override public VerifiedResult query(RefundRequest request) {
            queries.incrementAndGet();
            lastReason = request.reason();
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
            OffsetDateTime paid = now.minusHours(26).truncatedTo(ChronoUnit.MILLIS);
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
