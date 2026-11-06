package com.petplatform.payment.biz.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CenterPayment;
import com.petplatform.payment.api.dto.PaymentCenterTypes.CreateCenterPaymentCommand;
import com.petplatform.payment.api.dto.PaymentCenterTypes.QueryCenterPayment;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier;
import com.petplatform.payment.biz.application.PaymentReceiptVerifier.VerifiedNotice;
import com.petplatform.payment.biz.center.demo.DemoPaymentConfirmConsumer;
import com.petplatform.payment.biz.center.demo.DemoPaymentService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.task.PaymentCenterQueryTask.Outcome;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.task.core.JdbcAsyncTaskSubmitter;
import com.petplatform.user.api.dto.PaymentIdentity;
import com.petplatform.user.api.query.PaymentIdentityApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCR-PAY-CENTER-001 stage 1 (WP-C) payment center and DEMO loop against an isolated local MySQL database
 * (schemas 06/13/40/41/75/75-Demo). The channel, signature verifier and USER identity are in-memory fakes.
 *
 * <p><b>NOT RUN in the authoring environment</b> (no MySQL, no Docker). Run with
 * PAYMENT_CENTER_MYSQL_URL (or PAYMENT_REFUND_MYSQL_URL) = jdbc:mysql://127.0.0.1:PORT/ plus optional _USER/_PASSWORD.
 */
class PaymentCenterMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(9_175_000_000_000_000L);
    private static final String USER = "7751001", OTHER_USER = "7751002";
    private static final String MERCHANT_NO = "QA_PLATFORM_01", TERM_NO = "QA_T01", SUB_APP = "wxQaCenter";
    private static final BigDecimal AMOUNT = new BigDecimal("0.50");
    private static final ScheduleCapacityGuardApi NO_GUARD = new ScheduleCapacityGuardApi() {
        @Override public void acquire(List<String> storeIds, QueryContext context) {}
        @Override public void requireHeld(String storeId, DataSource callerSource) {}
    };

    // ---------------------------------------------------------------- create & idempotency

    @Test void createPersistsACenterRowParametersAndTheQueryTaskTogether() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            CenterPayment payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            assertFalse(payment.replayed());
            assertEquals("DEMO", payment.bizType());
            assertEquals("BIZ-1", payment.bizNo());
            assertNotNull(payment.wechatPayParameters().paySign());
            long id = Long.parseLong(payment.paymentId());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM payment_order WHERE id=" + id
                    + " AND order_id IS NULL AND store_id IS NULL AND merchant_id IS NULL AND biz_type='DEMO'"
                    + " AND biz_no='BIZ-1' AND settle_type='1' AND merchant_no='" + MERCHANT_NO + "' AND user_id=" + USER));
            assertEquals("PARAMETERS_READY", db.text("SELECT state FROM payment_dispatch WHERE payment_id=" + id));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM async_task WHERE task_key='PAYCENTER_CHANNEL_QUERY:" + id
                    + "' AND owner_module='PAYMENT' AND task_type='PAYCENTER_CHANNEL_QUERY' AND biz_id=" + id
                    + " AND retry_policy='PAYCENTER_CHANNEL_QUERY'"));
            assertEquals(id, db.count("SELECT payment_id FROM payment_center_request"));
            assertEquals(1, f.channel.preorders.get());
            assertEquals("1", f.channel.lastSettleType.get());
        }
    }

    @Test void settleTypeZeroIsPersistedAndNotSent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db, "0")) {
            CenterPayment payment = f.center.create(f.command("req-1", "BIZ-0", AMOUNT));
            assertEquals("0", db.text("SELECT settle_type FROM payment_order WHERE id=" + payment.paymentId()));
            assertNull(f.channel.lastSettleType.get());
        }
    }

    @Test void sameRequestReplaysStoredParametersWithoutASecondPreorder() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            CenterPayment first = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            CenterPayment replay = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            assertTrue(replay.replayed());
            assertEquals(first.paymentId(), replay.paymentId());
            assertEquals(first.paymentNo(), replay.paymentNo());
            assertEquals(first.wechatPayParameters(), replay.wechatPayParameters());
            assertEquals(1, f.channel.preorders.get());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM async_task"));
        }
    }

    @Test void sameRequestWithDifferentParametersIsAnIdempotencyConflict() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var original = f.command("req-1", "BIZ-1", AMOUNT);
            f.center.create(original);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> f.center.create(f.command("req-1", "BIZ-1", new BigDecimal("0.40")))).code());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> f.center.create(f.command("req-1", "BIZ-2", AMOUNT))).code());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> f.center.create(new CreateCenterPaymentCommand(original.context(), "DEMO", "BIZ-1", AMOUNT,
                            original.expireAt().plusSeconds(1)))).code());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM payment_order"));
        }
    }

    @Test void newRequestForTheSameBusinessObjectReplaysButDifferentFactsConflict() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var first = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            var again = f.center.create(new CreateCenterPaymentCommand(f.context("req-2"), "DEMO", "BIZ-1", AMOUNT,
                    f.expireAt));
            assertEquals(first.paymentId(), again.paymentId());
            assertTrue(again.replayed());
            assertEquals(CommonApiCodes.CONFLICT, assertThrows(ApiException.class,
                    () -> f.center.create(new CreateCenterPaymentCommand(f.context("req-3"), "DEMO", "BIZ-1",
                            new BigDecimal("0.40"), f.expireAt))).code());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM payment_order"));
            assertEquals(1, f.channel.preorders.get());
        }
    }

    @Test void deadlineOutsideTheBusinessWindowIsRejectedBeforeAnyRowExists() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            OffsetDateTime tooLate = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(11).truncatedTo(ChronoUnit.MILLIS);
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class, () -> f.center.create(
                    new CreateCenterPaymentCommand(f.context("req-1"), "DEMO", "BIZ-1", AMOUNT, tooLate))).code());
            assertEquals(0L, db.count("SELECT COUNT(*) FROM payment_order"));
            assertEquals(0, f.channel.preorders.get());
        }
    }

    /** A query task that cannot be registered must also roll back MAY_HAVE_SENT: nothing is sent. */
    @Test void failedTaskRegistrationRollsBackTheSendFence() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            f.channel.failPreorder.set(true);
            assertThrows(ApiException.class, () -> f.center.create(f.command("req-1", "BIZ-1", AMOUNT)));
            long id = db.count("SELECT id FROM payment_order");
            // Reset to PREPARED and plant a conflicting task generation under the same key.
            db.jdbc.update("UPDATE payment_dispatch SET state='PREPARED',preorder_req_time=NULL,trade_req_date=NULL,"
                    + "timeout_express_minutes=NULL,identity_hmac_sha256=NULL,may_have_sent_at=NULL WHERE payment_id=?", id);
            db.jdbc.update("UPDATE async_task SET payload_json='{\"paymentId\":\"1\"}' WHERE task_key=?",
                    "PAYCENTER_CHANNEL_QUERY:" + id);
            f.channel.failPreorder.set(false);
            int before = f.channel.preorders.get();
            assertThrows(ApiException.class, () -> f.center.create(f.command("req-1", "BIZ-1", AMOUNT)));
            assertEquals("PREPARED", db.text("SELECT state FROM payment_dispatch WHERE payment_id=" + id));
            assertNull(db.jdbc.queryForObject("SELECT may_have_sent_at FROM payment_dispatch WHERE payment_id=?",
                    LocalDateTime.class, id));
            assertEquals(before, f.channel.preorders.get());
        }
    }

    @Test void uncertainPreorderIsMarkedUnknownAndOnlyQueriedNeverResent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            f.channel.failPreorder.set(true);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> f.center.create(f.command("req-1", "BIZ-1", AMOUNT))).code());
            long id = db.count("SELECT id FROM payment_order");
            assertEquals("UNKNOWN", db.text("SELECT state FROM payment_dispatch WHERE payment_id=" + id));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM async_task WHERE task_key='PAYCENTER_CHANNEL_QUERY:" + id + "'"));
            f.channel.failPreorder.set(false);
            f.channel.queryState.set(LakalaProtocol.TradeState.DEAL);
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> f.center.create(f.command("req-1", "BIZ-1", AMOUNT))).code());
            assertEquals(1, f.channel.preorders.get());
            assertEquals(1, f.channel.lookups.get());
            assertEquals(Long.toString(db.count("SELECT payment_no FROM payment_order")), f.channel.lastQueriedNo.get());
        }
    }

    // ---------------------------------------------------------------- receipts, query task, events

    @Test void querySuccessMarksPaidWithTraceAndExactlyOneEvent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            long id = Long.parseLong(f.center.create(f.command("req-1", "BIZ-1", AMOUNT)).paymentId());
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            assertEquals(Outcome.DONE, f.coordinator.execute(id));
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertEquals("LOG0001", db.text("SELECT log_no FROM payment_order WHERE id=" + id));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentCenterSucceededEvent.v1'"
                    + " AND aggregate_type='PAYMENT' AND aggregate_id=" + id));
            assertEquals(0L, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
            String payload = db.text("SELECT CAST(payload AS CHAR) FROM integration_event_outbox");
            var node = new ObjectMapper().readTree(payload);
            assertEquals(Long.toString(id), node.path("paymentOrderId").textValue());
            assertEquals("DEMO", node.path("bizType").textValue());
            assertFalse(payload.contains(MERCHANT_NO) || payload.contains("openid") || payload.contains("LOG0001"));
            // The next attempt sees the terminal row and does not query again.
            int lookups = f.channel.lookups.get();
            assertEquals(Outcome.DONE, f.coordinator.execute(id));
            assertEquals(lookups, f.channel.lookups.get());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox"));
        }
    }

    @Test void notificationThenQueryAndDuplicateNotificationProduceOneEvent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            long id = Long.parseLong(payment.paymentId());
            f.notice.set(f.success(payment.paymentNo()));
            assertFalse(f.receipts.receiveNotification(Map.of(), f.body(payment.paymentNo())).replayed());
            assertTrue(f.receipts.receiveNotification(Map.of(), f.body(payment.paymentNo())).replayed());
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            assertEquals(Outcome.DONE, f.coordinator.execute(id));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox"));
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
        }
    }

    @Test void concurrentNotificationAndQueryProduceOneEvent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            long id = Long.parseLong(payment.paymentId());
            f.notice.set(f.success(payment.paymentNo()));
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                boolean viaQuery = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        if (viaQuery) f.coordinator.execute(id);
                        else f.receipts.receiveNotification(Map.of(), f.body(payment.paymentNo()));
                    } catch (ApiException busy) {
                        // a lock wait timeout is a retryable 503/409, never a second event
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox"));
            assertEquals(db.count("SELECT success_event_id FROM payment_order WHERE id=" + id),
                    Long.parseLong(db.text("SELECT event_id FROM integration_event_outbox")));
        }
    }

    @Test void lateSuccessAfterAVerifiedFailureIsStillRecordedOnce() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            long id = Long.parseLong(payment.paymentId());
            f.channel.queryState.set(LakalaProtocol.TradeState.FAIL);
            assertEquals(Outcome.DONE, f.coordinator.execute(id));
            assertEquals("FAILED", db.text("SELECT status FROM payment_order WHERE id=" + id));
            f.notice.set(f.success(payment.paymentNo()));
            assertTrue(f.receipts.receiveNotification(Map.of(), f.body(payment.paymentNo())).paid());
            assertEquals("PAID", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox"));
        }
    }

    @Test void refundStateRequiresReconciliationAndBlocksALaterSuccessEvent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            long id = Long.parseLong(payment.paymentId());
            f.channel.queryState.set(LakalaProtocol.TradeState.REFUND);
            assertEquals(Outcome.RECONCILIATION_REQUIRED, f.coordinator.execute(id));
            assertEquals("RECONCILIATION_REQUIRED", db.text("SELECT dispatch_state FROM payment_order WHERE id=" + id));
            f.notice.set(f.success(payment.paymentNo()));
            f.receipts.receiveNotification(Map.of(), f.body(payment.paymentNo()));
            assertEquals(0L, db.count("SELECT COUNT(*) FROM integration_event_outbox"));
        }
    }

    @Test void pendingKeepsQueryingUntilExpiryPlusGraceThenExhausts() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            long id = Long.parseLong(f.center.create(f.command("req-1", "BIZ-1", AMOUNT)).paymentId());
            f.channel.queryState.set(LakalaProtocol.TradeState.DEAL);
            assertEquals(Outcome.PENDING, f.coordinator.execute(id));
            f.channel.failLookup.set(true);
            assertEquals(Outcome.PENDING, f.coordinator.execute(id));
            db.jdbc.update("UPDATE payment_order SET expire_at=UTC_TIMESTAMP(3) - INTERVAL 6 MINUTE WHERE id=?", id);
            assertEquals(Outcome.EXHAUSTED, f.coordinator.execute(id));
            assertEquals("PAYING", db.text("SELECT status FROM payment_order WHERE id=" + id));
            assertEquals(1, f.channel.preorders.get());
        }
    }

    // ---------------------------------------------------------------- routing, facts, query

    @Test void routerSendsEachRowKindToItsOwnWriterAndTheOrderEntryRejectsCenterRows() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            f.notice.set(f.success(payment.paymentNo()));
            var orders = new PaymentNotificationService(db.source, IDS::incrementAndGet, NO_GUARD,
                    (h, b, e) -> f.notice.get(), event -> { throw new AssertionError("ORDER must not publish"); },
                    ZoneId.of("UTC"));
            // The unchanged ORDER entry cannot map a center row (order/store NULL) and fails closed without writing.
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> orders.receive(Map.of(), f.body(payment.paymentNo()))).code());
            assertEquals(0L, db.count("SELECT COUNT(*) FROM payment_channel_receipt"));
            var router = new PaymentCallbackRouter(db.source, f.receipts, orders);
            assertTrue(router.receive(Map.of(), f.body(payment.paymentNo())).paid());
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentCenterSucceededEvent.v1'"));
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, assertThrows(ApiException.class,
                    () -> router.receive(Map.of(), f.body("9175999999999999"))).code());
        }
    }

    @Test void factsRequireTheCallerTransactionAndAVerifiedSuccess() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            long id = Long.parseLong(f.center.create(f.command("req-1", "BIZ-1", AMOUNT)).paymentId());
            var facts = new PaymentCenterFactsApiImpl(db.source);
            var system = new QueryContext("t", OperatorType.SYSTEM, null);
            assertThrows(ApiException.class, () -> facts.requireSucceeded("DEMO", "BIZ-1", system));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.source));
            assertThrows(ApiException.class, () -> tx.execute(s -> facts.requireSucceeded("DEMO", "BIZ-1", system)));
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            f.coordinator.execute(id);
            var fact = tx.execute(s -> facts.requireSucceeded("DEMO", "BIZ-1", system));
            assertEquals(Long.toString(id), fact.paymentId());
            assertEquals(AMOUNT, fact.paidAmount());
            assertEquals(USER, fact.userId());
            assertThrows(ApiException.class, () -> tx.execute(s -> facts.requireSucceeded("DEMO", "BIZ-1",
                    new QueryContext("t", OperatorType.USER, OTHER_USER))));
        }
    }

    @Test void queryReadsCommittedFactsForTheOwnerOrSystemOnly() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var payment = f.center.create(f.command("req-1", "BIZ-1", AMOUNT));
            var view = f.center.query(new QueryCenterPayment(new QueryContext("t", OperatorType.USER, USER), "DEMO", "BIZ-1"));
            assertEquals(payment.paymentId(), view.paymentId());
            assertEquals("INIT", view.status());
            assertEquals(AMOUNT, view.amount());
            assertEquals("CNY", view.currency());
            assertEquals(payment.paymentId(), f.center.query(new QueryCenterPayment(
                    new QueryContext("t", OperatorType.SYSTEM, null), "DEMO", "BIZ-1")).paymentId());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class, () -> f.center.query(
                    new QueryCenterPayment(new QueryContext("t", OperatorType.USER, OTHER_USER), "DEMO", "BIZ-1"))).code());
            assertEquals(CommonApiCodes.NOT_FOUND, assertThrows(ApiException.class, () -> f.center.query(
                    new QueryCenterPayment(new QueryContext("t", OperatorType.SYSTEM, null), "DEMO", "BIZ-9"))).code());
        }
    }

    // ---------------------------------------------------------------- DEMO loop

    @Test void demoLoopPayQueryConfirmIsIdempotent() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var demo = new DemoPaymentService(db.source, IDS::incrementAndGet, f.center);
            var payment = demo.start(f.context("demo-1"), AMOUNT);
            assertEquals("DEMO", payment.bizType());
            assertEquals(payment.paymentId(), demo.start(f.context("demo-1"), AMOUNT).paymentId());
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, assertThrows(ApiException.class,
                    () -> demo.start(f.context("demo-1"), new BigDecimal("0.40"))).code());
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            assertEquals(Outcome.DONE, f.coordinator.execute(Long.parseLong(payment.paymentId())));
            var consumer = new DemoPaymentConfirmConsumer(db.source, new JdbcOutboxConsumeGuard(db.source,
                    IDS::incrementAndGet), new PaymentCenterFactsApiImpl(db.source));
            DispatchedEvent event = db.onlyEvent();
            consumer.consume(event);
            assertEquals("PAID", db.text("SELECT status FROM payment_demo_order WHERE demo_no=" + payment.bizNo()));
            assertEquals(Long.parseLong(event.eventId()),
                    db.count("SELECT source_event_id FROM payment_demo_order WHERE demo_no=" + payment.bizNo()));
            consumer.consume(event); // duplicate delivery: claim already held → NOOP
            assertEquals(1L, db.count("SELECT COUNT(*) FROM integration_event_consume_log"));
            assertEquals(1L, db.count("SELECT version FROM payment_demo_order WHERE demo_no=" + payment.bizNo()));
        }
    }

    @Test void demoRecordsAmountMismatchInsteadOfConfirming() throws Exception {
        try (Db db = new Db(); var f = new Fixture(db)) {
            var demo = new DemoPaymentService(db.source, IDS::incrementAndGet, f.center);
            var payment = demo.start(f.context("demo-1"), AMOUNT);
            f.channel.queryState.set(LakalaProtocol.TradeState.SUCCESS);
            f.channel.payerCents.set(40L);
            f.coordinator.execute(Long.parseLong(payment.paymentId()));
            new DemoPaymentConfirmConsumer(db.source, new JdbcOutboxConsumeGuard(db.source, IDS::incrementAndGet),
                    new PaymentCenterFactsApiImpl(db.source)).consume(db.onlyEvent());
            assertEquals("AMOUNT_MISMATCH", db.text("SELECT status FROM payment_demo_order WHERE demo_no=" + payment.bizNo()));
            assertEquals(new BigDecimal("0.40"), db.jdbc.queryForObject(
                    "SELECT paid_amount FROM payment_demo_order WHERE demo_no=" + payment.bizNo(), BigDecimal.class));
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static final class FakeChannel implements PaymentChannel {
        final AtomicInteger preorders = new AtomicInteger(), lookups = new AtomicInteger();
        final AtomicReference<Boolean> failPreorder = new AtomicReference<>(false), failLookup = new AtomicReference<>(false);
        final AtomicReference<String> lastSettleType = new AtomicReference<>(), lastQueriedNo = new AtomicReference<>();
        final AtomicReference<LakalaProtocol.TradeState> queryState = new AtomicReference<>(LakalaProtocol.TradeState.DEAL);
        final AtomicReference<Long> payerCents = new AtomicReference<>();
        final LocalDateTime tradeTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        final List<String> shas = Collections.synchronizedList(new ArrayList<>());

        @Override public VerifiedPreorder submitPreorder(LakalaProtocol.PreorderInput input, RequestNonce nonce) {
            preorders.incrementAndGet();
            lastSettleType.set(input.settleType());
            if (failPreorder.get()) throw new IllegalStateException("simulated timeout");
            return new VerifiedPreorder(new LakalaProtocol.PreorderResult(input.merchantNo(), input.outTradeNo(), null,
                    input.subAppId(), "wx-prepay", "sig", "1730700000", "nonce", "prepay_id=wx-prepay", "RSA"), sha("p"));
        }

        @Override public VerifiedQuery lookup(LakalaProtocol.QueryInput input, LakalaProtocol.ExpectedPayment expected,
                RequestNonce nonce) {
            lookups.incrementAndGet();
            lastQueriedNo.set(input.outTradeNo());
            if (failLookup.get()) throw new IllegalStateException("simulated timeout");
            var state = queryState.get();
            boolean success = state == LakalaProtocol.TradeState.SUCCESS;
            Long payer = success ? (payerCents.get() == null ? expected.expectedTotalCents() : payerCents.get()) : null;
            return new VerifiedQuery(new LakalaProtocol.QueryResult(input.merchantNo(), input.outTradeNo(),
                    "TRADE-" + input.outTradeNo(), state, expected.expectedTotalCents(), payer,
                    success ? tradeTime : null, "WECHAT", success ? "LOG0001" : null,
                    success ? LocalDate.now(ZoneOffset.UTC) : null), sha("q" + state + lookups.get()));
        }

        @Override public VerifiedClose requestClose(LakalaProtocol.CloseInput input, RequestNonce nonce) {
            throw new AssertionError("payment center does not close in stage 1");
        }

        private String sha(String seed) {
            String hex = Integer.toHexString(Math.abs((seed + UUID.randomUUID()).hashCode()));
            String value = (hex + "0".repeat(64)).substring(0, 64);
            shas.add(value);
            return value;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeChannel channel = new FakeChannel();
        final AtomicReference<VerifiedNotice> notice = new AtomicReference<>();
        final OffsetDateTime expireAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(9).truncatedTo(ChronoUnit.MILLIS);
        final PaymentCenterReceiptWriter receipts;
        final PaymentCenterService center;
        final PaymentCenterQueryCoordinator coordinator;

        Fixture(Db db) throws Exception { this(db, "1"); }

        Fixture(Db db, String settleType) throws Exception {
            db.setup();
            var settings = new PaymentDispatchService.Settings("QA_ORG", "QA subject", "127.0.0.1",
                    "https://notify.invalid/pay", ZoneId.of("UTC"), new SecretKeySpec(new byte[32], "AES"), false);
            var publisher = new TransactionalOutboxPublisher(db.source, IDS::incrementAndGet, new ObjectMapper());
            PaymentReceiptVerifier verifier = (headers, body, expected) -> notice.get();
            receipts = new PaymentCenterReceiptWriter(db.source, IDS::incrementAndGet, verifier, publisher,
                    ZoneId.of("UTC"), MERCHANT_NO);
            PaymentMerchantBindings bindings = new PaymentMerchantBindings() {
                @Override public Binding require(String merchantId, String storeId) {
                    throw new AssertionError("store binding must not be used by the payment center");
                }
                @Override public Binding requirePlatform() { return new Binding(MERCHANT_NO, TERM_NO, SUB_APP); }
            };
            PaymentIdentityApi identities = new PaymentIdentityApi() {
                @Override public PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId, String storeId,
                        QueryContext context) { throw new AssertionError("store variant must not be used"); }
                @Override public PaymentIdentity requireCurrentPaymentIdentity(String userId, String appId,
                        QueryContext context) { return new PaymentIdentity(appId, "openid-" + userId); }
            };
            center = new PaymentCenterService(db.source, IDS::incrementAndGet,
                    PaymentCenterBizPolicy.demoOnly(new BigDecimal("1.00")), bindings, identities, channel, receipts,
                    new JdbcAsyncTaskSubmitter(db.source, IDS::incrementAndGet), settings, settleType, Clock.systemUTC());
            coordinator = new PaymentCenterQueryCoordinator(db.source, channel, receipts, settings, Clock.systemUTC());
        }

        CommandContext context(String requestId) {
            return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER, USER, "TEST");
        }

        CreateCenterPaymentCommand command(String requestId, String bizNo, BigDecimal amount) {
            return new CreateCenterPaymentCommand(context(requestId), "DEMO", bizNo, amount, expireAt);
        }

        VerifiedNotice success(String paymentNo) {
            return new VerifiedNotice(MERCHANT_NO, paymentNo, "TRADE-" + paymentNo, "SUCCESS", AMOUNT, AMOUNT,
                    channel.tradeTime, "WECHAT", "LOG0001", LocalDate.now(ZoneOffset.UTC));
        }

        byte[] body(String paymentNo) {
            return ("{\"out_trade_no\":\"" + paymentNo + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        @Override public void close() {}
    }

    private static final class Db implements AutoCloseable {
        private final String name = "payment_center_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = env("PAYMENT_CENTER_MYSQL_URL", env("PAYMENT_REFUND_MYSQL_URL", null));
        private final String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
        private final String password = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", ""));
        final DataSource source;
        final JdbcTemplate jdbc;
        private final JdbcTemplate admin;
        private boolean created;

        Db() {
            if (url == null || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))
                throw new IllegalArgumentException("dedicated local MySQL URL required");
            admin = new JdbcTemplate(dataSource(url, user, password));
            source = dataSource(url + name, user, password);
            jdbc = new JdbcTemplate(source);
        }

        private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }

        void setup() throws Exception {
            if (created) return;
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            created = true;
            try (Connection connection = source.getConnection()) {
                Path root = Path.of("").toAbsolutePath();
                while (root != null && !Files.exists(root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql")))
                    root = root.getParent();
                if (root == null) throw new IllegalStateException("schema root absent");
                for (String file : List.of("06-核心数据库Schema-v0.1.sql", "13-Async-Infra-Schema-v0.1.sql",
                        "40-Payment-Foundation-Schema-v0.1.sql", "41-Payment-Dispatch-Schema-v0.1.sql",
                        "75-Payment-Center-Schema-v0.1.sql", "75-Payment-Center-Demo-Schema-v0.1.sql")) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(
                            root.resolve("docs/03-database").resolve(file)), StandardCharsets.UTF_8));
                }
            }
            // JdbcAsyncTaskSubmitter.enqueueAt needs the SQL39 column; SQL39 itself also alters schedule tables
            // this fixture does not create, so only its async_task statement is applied here (verbatim).
            jdbc.execute("ALTER TABLE async_task ADD COLUMN submitted_execute_at DATETIME(3) NULL");
        }

        long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
        String text(String sql) { return jdbc.queryForObject(sql, String.class); }

        DispatchedEvent onlyEvent() {
            return jdbc.queryForObject("SELECT event_id,event_type,event_version,occurred_at,aggregate_type,aggregate_id,"
                    + "trace_id,CAST(payload AS CHAR) AS payload FROM integration_event_outbox", (rs, n) -> new DispatchedEvent(
                    rs.getString("event_id"), rs.getString("event_type"), rs.getInt("event_version"),
                    rs.getObject("occurred_at", LocalDateTime.class).atOffset(ZoneOffset.UTC),
                    rs.getString("aggregate_type"), rs.getLong("aggregate_id"), rs.getString("trace_id"),
                    rs.getString("payload")));
        }

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
