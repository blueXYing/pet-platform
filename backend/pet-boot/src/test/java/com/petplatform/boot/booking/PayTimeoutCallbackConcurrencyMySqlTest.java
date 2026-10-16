package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.biz.apiimpl.OrderExpiryApiImpl;
import com.petplatform.order.biz.apiimpl.OrderExpiryFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderLatePaymentFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl;
import com.petplatform.payment.api.command.PaymentExpiryCoordinationApi.ExpiryEvidence;
import com.petplatform.payment.api.command.PaymentExpiryCoordinationApi.ReconcilePaymentExpiryCommand;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparedPayment;
import com.petplatform.payment.biz.apiimpl.BookingPaymentExposureApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.payment.biz.application.PaymentRefundService;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseAcknowledgement;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.TradeState;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA-003 specialised concurrency matrix on real MySQL (CON-014 / CON-015 / FLT-005).
 *
 * <p>Race 1 drives the real payment-timeout closure (the full ORDER expiry path, including
 * PAYMENT expiry coordination with terminal-close evidence) against a real signature-verifying
 * channel callback on the same order, over repeated barrier-synchronised rounds. Every round
 * must land in exactly one consistent terminal outcome: either the order is closed with
 * PAYMENT_TIMEOUT while no NORMAL fulfillment exists, or the proven callback wins and the
 * order cannot close. Scenario 2 walks the deterministic late-payment chain (FLT-005): after
 * the closure the late signed SUCCESS callback becomes a LATE payment result, one
 * LatePaymentSucceededAfterTimeoutEvent, and exactly one FULL original-route refund_order that
 * duplicate callbacks or replayed events can never duplicate. Scenario 3 pins the PAY-004
 * behaviour inside the timeout window: an in-flight UNKNOWN dispatch is queried, and a
 * verified SUCCESS found by that query keeps the order open for the outbox to mark paid.
 */
class PayTimeoutCallbackConcurrencyMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(8_760_000_000_000_000L);
    private static final String STORE = "710302";
    private static final String USER = "710100";
    /** Same binding value the shared payment-foundation fixture provisions per payment. */
    private static final String SUB_APP = "wxQaSubApp01";
    private static final int RACE_ROUNDS = 8;

    /** CON-014: the timeout close and the signed success callback race on the same order. */
    @Test
    void timeoutCloseAndSignedCallbackRaceLandInOneConsistentOutcomeEveryRound()
            throws Exception {
        int resolved = 0;
        for (int round = 0; round < RACE_ROUNDS; round++) {
            try (Fixture f = new Fixture()) {
                String orderId = f.book();
                PreparedPayment payment = f.prepare(orderId);
                var notice = f.successNotice(payment);
                String reservation = f.reservationOf(orderId);
                OffsetDateTime deadline = f.moveDeadlineToPast(orderId);
                CountDownLatch ready = new CountDownLatch(2);
                try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                    Future<String> timeout = pool.submit(
                        () -> f.raceExpire(orderId, reservation, deadline, ready));
                    Future<PaymentNotificationService.ReceiptResult> callback = pool.submit(
                        () -> f.raceCallback(notice, ready));
                    ready.await(10, TimeUnit.SECONDS);
                    timeout.get(60, TimeUnit.SECONDS);
                    callback.get(60, TimeUnit.SECONDS);
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
                }
                assertUniqueConsistentOutcome(f, orderId, reservation);
                resolved++;
            }
        }
        assertEquals(RACE_ROUNDS, resolved, "every round must resolve to a consistent winner");
    }

    /** CON-014 loser branch made deterministic: the closed order keeps the payment LATE. */
    @Test
    void lateSuccessAfterVerifiedClosureBecomesOneLateRefundAndIsIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            String orderId = f.book();
            PreparedPayment payment = f.prepare(orderId);
            String reservation = f.reservationOf(orderId);
            OffsetDateTime deadline = f.moveDeadlineToPast(orderId);
            assertEquals(ExpireOrderResult.CLOSED, f.expire(orderId, reservation, deadline));
            assertEquals("CANCELED", f.stage(orderId));
            assertEquals("PAYMENT_TIMEOUT", f.cancelReason(orderId));

            // The late callback arrives after closure: a durable signed channel fact.
            var lateNotice = f.successNotice(payment);
            assertTrue(f.receive(lateNotice).paid());
            assertTrue(f.receive(lateNotice).replayed(), "duplicate callback replays");
            f.dispatchPaymentSucceeded();
            assertEquals("LATE", f.text(
                    "SELECT result_type FROM order_payment_result WHERE order_id=?",
                    Long.parseLong(orderId)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM order_payment_result "
                    + "WHERE order_id=?", Long.parseLong(orderId)));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='OrderPaidEvent.v1'"));

            // The late event creates exactly one FULL original-route refund, idempotent on replay.
            LateRefundService consumer = f.lateRefundConsumer();
            DispatchedEvent late = f.event("LatePaymentSucceededAfterTimeoutEvent.v1");
            consumer.consume(late);
            // An outbox re-delivery after a lost consume ACK is a silent idempotent NOOP.
            consumer.consume(late);
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_order"));
            assertEquals("LATE_PAYMENT_TIMEOUT", f.text("SELECT source_type FROM refund_order"));
            assertEquals("FULL", f.text("SELECT refund_type FROM refund_order"));
            assertEquals(0, payment.amount().compareTo(f.amount(
                    "SELECT refund_amount FROM refund_order")));

            // The refund reaches the channel exactly once; replays never resubmit.
            String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
            RefundExecutionService execution = f.refundExecution();
            assertTrue(execution.execute(refundId, STORE, false, "qa-payref-channel",
                    "LATE_PAYMENT_TIMEOUT").done());
            assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
            assertEquals(1, f.channelRefund.sends.get());
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_transaction"));
            assertTrue(execution.execute(refundId, STORE, false, "qa-payref-channel-replay",
                    "LATE_PAYMENT_TIMEOUT").done());
            assertEquals(1, f.channelRefund.sends.get(), "replay never resubmits the refund");
            assertEquals(1, f.count("SELECT COUNT(*) FROM refund_transaction"));
        }
    }

    /** PAY-004 inside the timeout window: UNKNOWN in-flight dispatch is queried, never failed. */
    @Test
    void unknownDispatchAtDeadlineIsQueriedAndAProvenSuccessKeepsTheOrderOpen() throws Exception {
        try (Fixture f = new Fixture()) {
            String orderId = f.book();
            String key = UUID.randomUUID().toString();
            PreparedPayment payment = f.prepare(orderId, key);
            // The dispatch request may have reached the channel; the outcome stays unknown.
            f.channel.failPreorder = true;
            assertThrows(ApiException.class, () -> f.createDispatch(orderId, key));
            f.channel.failPreorder = false;
            assertEquals("UNKNOWN", f.text("SELECT state FROM payment_dispatch WHERE payment_id=?",
                    Long.parseLong(payment.paymentId())));
            OffsetDateTime deadline = f.moveDeadlineToPast(orderId);
            // The channel query proves the payment actually succeeded: the close must hold.
            f.channel.queryState = TradeState.SUCCESS;
            f.channel.successfulTradeTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
            assertEquals(ExpiryEvidence.HOLD, f.reconcileForExpiry(orderId, deadline));
            assertEquals("PAID", f.text("SELECT status FROM payment_order WHERE id=?",
                    Long.parseLong(payment.paymentId())));
            assertThrows(ApiException.class, () -> f.expire(orderId,
                    f.reservationOf(orderId), deadline));
            assertEquals("PENDING_PAYMENT", f.stage(orderId));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='PaymentSucceededEvent.v1'"));
            f.dispatchPaymentSucceeded();
            assertEquals("PENDING_CONFIRM", f.stage(orderId));
            assertEquals("PAID", f.text("SELECT payment_status FROM pet_order WHERE id=?",
                    Long.parseLong(orderId)));
        }
    }

    /** Invariants that every race round must satisfy, whichever side won. */
    private static void assertUniqueConsistentOutcome(Fixture f, String orderId,
            String reservation) {
        String stage = f.stage(orderId);
        String paymentStatus = f.text(
                "SELECT status FROM payment_order WHERE order_id=?", Long.parseLong(orderId));
        if ("CANCELED".equals(stage)) {
            assertEquals("PAYMENT_TIMEOUT", f.cancelReason(orderId),
                    "a closed order must carry the timeout reason");
            // A paid-after-close fact is only legal as a LATE projection: never a NORMAL
            // fulfillment, never an order-paid event.
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_payment_result "
                            + "WHERE order_id=? AND result_type='NORMAL'",
                    Long.parseLong(orderId)));
            assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='OrderPaidEvent.v1'"));
            assertEquals("EXPIRED", f.text(
                    "SELECT status FROM schedule_reservation WHERE id=?",
                    Long.parseLong(reservation)));
        } else {
            // The proven callback won: the order stays open and only the outbox marks it paid;
            // the reservation must still be held for the paid booking.
            assertEquals("PENDING_PAYMENT", stage, "an unexpired non-closed order stays pending");
            assertEquals("PAID", paymentStatus, "an open race order needs the proven payment");
            assertEquals("TEMP_LOCKED", f.text(
                    "SELECT status FROM schedule_reservation WHERE id=?",
                    Long.parseLong(reservation)));
            f.dispatchPaymentSucceeded();
            assertEquals("PENDING_CONFIRM", f.stage(orderId));
        }
        assertEquals(1, f.count("SELECT COUNT(*) FROM payment_order WHERE order_id=?",
                Long.parseLong(orderId)), "exactly one payment order per booking");
        assertTrue(f.count("SELECT COUNT(*) FROM integration_event_outbox "
                + "WHERE event_type='PaymentSucceededEvent.v1'") <= 1,
                "the payment success fact is published at most once");
        assertTrue(f.count("SELECT COUNT(*) FROM order_payment_result WHERE order_id=?",
                Long.parseLong(orderId)) <= 1, "at most one ORDER payment result");
    }

    /** Isolated real-MySQL fixture over the shared payment-foundation harness. */
    private static final class Fixture implements AutoCloseable {
        final PaymentFoundationAcceptanceTest.Fixture foundation;
        final ScriptedChannel channel = new ScriptedChannel();
        final ScriptedRefundChannel channelRefund = new ScriptedRefundChannel();
        final PaymentDispatchService dispatch;
        final OrderExpiryApiImpl orderExpiry;
        final JdbcTemplate jdbc;

        Fixture() throws Exception {
            foundation = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC());
            jdbc = foundation.db.jdbc;
            try {
                // The dispatch identity chain reads the current WECHAT identity of the buyer.
                jdbc.update("INSERT INTO user_auth_identity"
                        + " (id,user_id,identity_type,app_id,open_id,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        IDS.incrementAndGet(), 710100L, "WECHAT_MINI", SUB_APP, "qa-payref-openid");
                byte[] rawKey = new byte[32];
                new SecureRandom().nextBytes(rawKey);
                SecretKey key = new SecretKeySpec(rawKey, "AES");
                var settings = new PaymentDispatchService.Settings("OP123", "Pet service",
                        "127.0.0.1", "https://qa.invalid/payment-notify",
                        ZoneId.of("Asia/Shanghai"), key, true);
                DataSource source = foundation.db.source;
                var guard = foundation.guard;
                dispatch = new PaymentDispatchService(source, IDS::incrementAndGet, guard,
                        new OrderPaymentFactsApiImpl(source, guard),
                        new PaymentIdentityApiImpl(source, guard), foundation.preparation,
                        channel, foundation.notification, settings, Clock.systemUTC());
                var scheduleFacts = new ScheduleProtectionFactsApiImpl(source, guard);
                var reservationExpiry = new ReservationExpiryApiImpl(source, IDS::incrementAndGet,
                        guard, new OrderExpiryFactsApiImpl(source, guard), scheduleFacts);
                orderExpiry = new OrderExpiryApiImpl(source, IDS::incrementAndGet, guard,
                        reservationExpiry,
                        new BookingPaymentExposureApiImpl(source, guard, true),
                        new com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl(
                                source, guard),
                        dispatch);
            } catch (RuntimeException failure) {
                foundation.close();
                throw failure;
            }
        }

        String book() {
            CreateOrderResult booking = foundation.book();
            return booking.orderId();
        }

        PreparedPayment prepare(String orderId) {
            return foundation.prepare(orderId, UUID.randomUUID().toString());
        }

        PreparedPayment prepare(String orderId, String requestId) {
            return foundation.prepare(orderId, requestId);
        }

        String reservationOf(String orderId) {
            return Long.toString(jdbc.queryForObject(
                    "SELECT reservation_id FROM pet_order WHERE id=?", Long.class,
                    Long.parseLong(orderId)));
        }

        OffsetDateTime moveDeadlineToPast(String orderId) {
            OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
                    .truncatedTo(ChronoUnit.MILLIS);
            jdbc.update("UPDATE pet_order SET payment_expire_at=? WHERE id=?",
                    deadline.toLocalDateTime(), Long.parseLong(orderId));
            jdbc.update("UPDATE schedule_reservation SET lock_expire_at=? WHERE order_id=?",
                    deadline.toLocalDateTime(), Long.parseLong(orderId));
            jdbc.update("UPDATE payment_order SET expire_at=? WHERE order_id=?",
                    deadline.toLocalDateTime(), Long.parseLong(orderId));
            return deadline;
        }

        PaymentFoundationAcceptanceTest.SignedNotice successNotice(PreparedPayment payment)
                throws Exception {
            return foundation.notice(payment, "SUCCESS", "QA_RACE_" + IDS.incrementAndGet(),
                    payment.amount(), payment.amount());
        }

        PaymentNotificationService.ReceiptResult raceCallback(
                PaymentFoundationAcceptanceTest.SignedNotice notice, CountDownLatch ready) {
            ready.countDown();
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                return foundation.notification.receive(notice.headers(), notice.body());
            } catch (ApiException busy) {
                // A guarded busy retry may refuse the callback; the round invariants still hold.
                assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", busy.code());
                return new PaymentNotificationService.ReceiptResult(null, false, false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        String raceExpire(String orderId, String reservation, OffsetDateTime deadline,
                CountDownLatch ready) {
            ready.countDown();
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                try {
                    expire(orderId, reservation, deadline);
                    return "CLOSED";
                } catch (ApiException refused) {
                    assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", refused.code(),
                            "a fenced close is refused, never silently wrong");
                    return "REFUSED";
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        ExpireOrderResult expire(String orderId, String reservation, OffsetDateTime deadline) {
            return orderExpiry.expire(new ExpireOrderCommand(new CommandContext(
                    "TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0", "qa-payref-race",
                    OperatorType.SYSTEM, null, "ASYNC_TASK"), orderId, reservation, 0, deadline));
        }

        ExpiryEvidence reconcileForExpiry(String orderId, OffsetDateTime deadline) {
            return dispatch.reconcileForExpiry(new ReconcilePaymentExpiryCommand(
                    new CommandContext("PAYMENT_EXPIRY_ORDER:" + orderId + ":0",
                            "qa-payref-reconcile", OperatorType.SYSTEM, null, "ASYNC_TASK"),
                    orderId, STORE, deadline));
        }

        void createDispatch(String orderId, String requestId) {
            dispatch.create(new com.petplatform.payment.api.dto.PaymentPreparationTypes
                    .PreparePaymentCommand(new CommandContext(requestId,
                    "qa-payref-dispatch", OperatorType.USER, USER, "MINIAPP"), orderId));
        }

        PaymentNotificationService.ReceiptResult receive(
                PaymentFoundationAcceptanceTest.SignedNotice notice) {
            return foundation.notification.receive(notice.headers(), notice.body());
        }

        void dispatchPaymentSucceeded() {
            try (OutboxDispatcher dispatcher = foundation.dispatcher(foundation.result)) {
                OutboxDispatcher.Outcome outcome = dispatcher.dispatchOne();
                assertTrue(outcome == OutboxDispatcher.Outcome.COMPLETED
                        || outcome == OutboxDispatcher.Outcome.EMPTY, "dispatch: " + outcome);
            }
        }

        LateRefundService lateRefundConsumer() {
            return new LateRefundService(foundation.db.source, IDS::incrementAndGet,
                    foundation.guard,
                    new OrderLatePaymentFactsApiImpl(foundation.db.source, foundation.guard,
                            foundation.reservationExpiry),
                    new PaymentSuccessFactsApiImpl(foundation.db.source, foundation.guard),
                    foundation.publisher);
        }

        RefundExecutionService refundExecution() {
            var payment = new PaymentRefundService(foundation.db.source, IDS::incrementAndGet,
                    foundation.guard,
                    new OrderLatePaymentFactsApiImpl(foundation.db.source, foundation.guard,
                            foundation.reservationExpiry),
                    new PaymentSuccessFactsApiImpl(foundation.db.source, foundation.guard),
                    lateRefundConsumer(), channelRefund,
                    new PaymentRefundService.Settings("127.0.0.1", "https://qa.invalid/refund",
                            ZoneId.of("Asia/Shanghai")), Clock.systemUTC());
            return new RefundExecutionService(lateRefundConsumer(), payment,
                    new PaymentRefundResultFactsApiImpl(foundation.db.source, foundation.guard));
        }

        String stage(String orderId) {
            return text("SELECT order_stage FROM pet_order WHERE id=?", Long.parseLong(orderId));
        }

        String cancelReason(String orderId) {
            return text("SELECT cancel_reason FROM pet_order WHERE id=?",
                    Long.parseLong(orderId));
        }

        String text(String sql, Object arg) {
            return jdbc.queryForObject(sql, String.class, arg);
        }

        String text(String sql) {
            return jdbc.queryForObject(sql, String.class);
        }

        BigDecimal amount(String sql) {
            return jdbc.queryForObject(sql, BigDecimal.class);
        }

        long count(String sql, Object... args) {
            return args.length == 0 ? jdbc.queryForObject(sql, Long.class)
                    : jdbc.queryForObject(sql, Long.class, args);
        }

        DispatchedEvent event(String type) {
            return jdbc.queryForObject("SELECT event_id,event_version,occurred_at,"
                    + "aggregate_type,aggregate_id,trace_id,payload FROM integration_event_outbox "
                    + "WHERE event_type=?", (rs, n) -> new DispatchedEvent(
                    rs.getString("event_id"), type, rs.getInt("event_version"),
                    rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
                    rs.getString("trace_id"), rs.getString("payload")), type);
        }

        @Override public void close() {
            foundation.close();
        }
    }

    /** The only external boundary that is scripted: the payment channel HTTP calls. */
    private static final class ScriptedChannel implements PaymentChannel {
        final AtomicInteger preorders = new AtomicInteger();
        volatile boolean failPreorder;
        volatile TradeState queryState = TradeState.UNKNOWN;
        volatile LocalDateTime successfulTradeTime;

        @Override public VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce) {
            preorders.incrementAndGet();
            if (failPreorder) throw new IllegalStateException("QA channel result unknown");
            PreorderResult result = new PreorderResult(input.merchantNo(), input.outTradeNo(),
                    "qa-payref-trade", input.subAppId(), "qa-prepay-1", "S".repeat(344),
                    nonce.timestampSeconds(), nonce.nonce(), "prepay_id=qa-prepay-1", "RSA");
            return new VerifiedPreorder(result, "a".repeat(64));
        }

        @Override public VerifiedQuery lookup(QueryInput input, ExpectedPayment expected,
                RequestNonce nonce) {
            TradeState state = queryState;
            QueryResult result = new QueryResult(input.merchantNo(), input.outTradeNo(),
                    "qa-payref-trade", state, expected.expectedTotalCents(),
                    state == TradeState.SUCCESS ? expected.expectedTotalCents() : null,
                    state == TradeState.SUCCESS ? successfulTradeTime : null, "WECHAT");
            return new VerifiedQuery(result, "d".repeat(64));
        }

        @Override public VerifiedClose requestClose(CloseInput input, RequestNonce nonce) {
            throw new IllegalStateException("QA closure never reaches the scripted channel");
        }
    }

    /** Scripted refund channel: settles on the first submit and counts every send. */
    private static final class ScriptedRefundChannel implements PaymentRefundChannel {
        final AtomicInteger sends = new AtomicInteger();

        @Override public VerifiedResult submit(RefundRequest request) {
            sends.incrementAndGet();
            return new VerifiedResult("SUCCESS", request.refundNo(), "QA_PAYREF_REFUND",
                    request.amount().movePointRight(2).longValueExact(),
                    request.amount().movePointRight(2).longValueExact(),
                    LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1)
                            .truncatedTo(ChronoUnit.SECONDS), "d".repeat(64));
        }

        @Override public VerifiedResult query(RefundRequest request) {
            throw new AssertionError("a settled refund never re-queries in this matrix");
        }
    }
}
