package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.biz.apiimpl.OrderExpiryApiImpl;
import com.petplatform.order.biz.apiimpl.OrderExpiryFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl;
import com.petplatform.payment.api.command.PaymentExpiryCoordinationApi.ExpiryEvidence;
import com.petplatform.payment.api.command.PaymentExpiryCoordinationApi.ReconcilePaymentExpiryCommand;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparePaymentCommand;
import com.petplatform.payment.biz.application.PaymentChannel;
import com.petplatform.payment.biz.application.PaymentDispatchService;
import com.petplatform.payment.biz.apiimpl.PaymentPreparationApiImpl;
import com.petplatform.payment.biz.application.PaymentMerchantBindings;
import com.petplatform.payment.biz.application.PaymentNotificationService;
import com.petplatform.payment.biz.apiimpl.BookingPaymentExposureApiImpl;
import com.petplatform.payment.biz.infrastructure.provider.LakalaHttpClient.RequestNonce;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseAcknowledgement;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.CloseInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.ExpectedPayment;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.PreorderResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryInput;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.QueryResult;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol.TradeState;
import com.petplatform.user.biz.apiimpl.PaymentIdentityApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Real isolated MySQL and real owner APIs; only the external channel is scripted. */
class PaymentCoordinationAcceptanceTest {
    private static final AtomicLong IDS = new AtomicLong(9_910_000_000_000_000L);
    private static final String USER = "710100";
    private static final String STORE = "710302";
    private static final String SUB_APP = "wxQaSubApp01";
    private static final String MERCHANT_NO = "QA_MERCHANT_01";
    private static final String TERM_NO = "QA_TERM_01";
    private static final String OPEN_ID = "qa-current-payment-openid";
    private static final String PAY_SIGN = "S".repeat(344);

    @Test
    void oneOriginalPreorderStoresEncryptedShortLivedParametersAndReplaysOnlyThatResult()
            throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            String requestId = UUID.randomUUID().toString();
            var first = fixture.dispatch.create(userCommand(booking.orderId(), requestId));
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_order"));
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch"));
            assertEquals("PARAMETERS_READY", fixture.foundation.text(
                    "SELECT state FROM payment_dispatch WHERE payment_id=?",
                    Long.parseLong(first.paymentId())));
            assertEquals(PAY_SIGN, first.wechatPayParameters().paySign());
            assertEquals("prepay_id=qa-prepay-1", first.wechatPayParameters().packageValue());
            assertFalse(first.toString().contains(PAY_SIGN));
            assertFalse(first.wechatPayParameters().toString().contains(PAY_SIGN));
            byte[] ciphertext = fixture.foundation.db.jdbc.queryForObject(
                    "SELECT parameters_ciphertext FROM payment_dispatch WHERE payment_id=?",
                    byte[].class, Long.parseLong(first.paymentId()));
            byte[] iv = fixture.foundation.db.jdbc.queryForObject(
                    "SELECT parameters_iv FROM payment_dispatch WHERE payment_id=?",
                    byte[].class, Long.parseLong(first.paymentId()));
            assertNotNull(ciphertext);
            assertNotNull(iv);
            assertEquals(12, iv.length);
            assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains(PAY_SIGN));
            assertFalse(first.parameterValidUntil().isAfter(booking.paymentExpireAt()));
            assertTrue(fixture.foundation.db.jdbc.queryForObject(
                    "SELECT timeout_express_minutes FROM payment_dispatch WHERE payment_id=?",
                    Integer.class, Long.parseLong(first.paymentId())) >= 1);

            var replay = fixture.dispatch.create(userCommand(booking.orderId(), requestId));
            assertEquals(first.paymentNo(), replay.paymentNo());
            assertEquals(first.wechatPayParameters(), replay.wechatPayParameters());
            assertEquals(1, fixture.channel.preorders.get(), "the original number must never be submitted twice");
        }
    }

    @Test
    void uncertainSendKeepsOriginalNumberAndNeverSubmitsAnotherPreorder() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            String requestId = UUID.randomUUID().toString();
            fixture.channel.failPreorder = true;
            assertThrows(RuntimeException.class,
                    () -> fixture.dispatch.create(userCommand(booking.orderId(), requestId)));
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_order"));
            fixture.channel.failPreorder = false;
            assertThrows(RuntimeException.class,
                    () -> fixture.dispatch.create(userCommand(booking.orderId(), requestId)));
            assertEquals(1, fixture.channel.preorders.get());
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            assertThrows(RuntimeException.class, () -> fixture.expireThroughOrder(
                    booking.orderId(), deadline));
            assertEquals("PENDING_PAYMENT", fixture.foundation.text(
                    "SELECT order_stage FROM pet_order WHERE id=?",
                    Long.parseLong(booking.orderId())));
            assertEquals("TEMP_LOCKED", fixture.foundation.text(
                    "SELECT status FROM schedule_reservation WHERE order_id=?",
                    Long.parseLong(booking.orderId())));
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch"));
            assertTrue(fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch "
                    + "WHERE state IN ('MAY_HAVE_SENT','UNKNOWN')") == 1);
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_order"));
        }
    }

    @Test
    void preparedButUnsentPaymentCanBeFencedBeforeExpiryRelease() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            var prepared = fixture.foundation.prepare(booking.orderId(), UUID.randomUUID().toString());
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            ExpiryEvidence evidence = fixture.dispatch.reconcileForExpiry(expiryCommand(
                    booking.orderId(), deadline));
            assertEquals(ExpiryEvidence.FENCED_UNSENT, evidence);
            assertEquals("FENCED_UNSENT", fixture.foundation.text(
                    "SELECT state FROM payment_dispatch WHERE payment_id=?",
                    Long.parseLong(prepared.paymentId())));
            assertEquals(0, fixture.channel.preorders.get());
            assertEquals(0, fixture.channel.queries.get());
            assertEquals(0, fixture.channel.closes.get());
            assertEquals(ExpireOrderResult.CLOSED, fixture.expireThroughOrder(
                    booking.orderId(), deadline));
            assertEquals("CANCELED", fixture.foundation.text(
                    "SELECT order_stage FROM pet_order WHERE id=?",
                    Long.parseLong(booking.orderId())));
            assertEquals("EXPIRED", fixture.foundation.text(
                    "SELECT status FROM schedule_reservation WHERE order_id=?",
                    Long.parseLong(booking.orderId())));
        }
    }

    @Test
    void lessThanOneMinuteRemainingNeverSendsPreorder() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            fixture.shortenOriginalDeadlineForTest(booking.orderId(), Duration.ofSeconds(30));
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(
                    userCommand(booking.orderId(), UUID.randomUUID().toString())));
            assertEquals(0, fixture.channel.preorders.get());
            assertEquals(0, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch "
                    + "WHERE state IN ('MAY_HAVE_SENT','PARAMETERS_READY')"));
        }
    }

    @Test
    void wrongActorOrCurrentWechatAppIdentityCannotReachTheChannel() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            var wrongActor = new PreparePaymentCommand(new CommandContext(
                    UUID.randomUUID().toString(), "pay-wrong-actor-qa", OperatorType.USER,
                    "710101", "MINIAPP"), booking.orderId());
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(wrongActor));
            fixture.foundation.db.jdbc.update("UPDATE user_auth_identity SET app_id=? WHERE user_id=?",
                    "different-wechat-app", Long.parseLong(USER));
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(userCommand(
                    booking.orderId(), UUID.randomUUID().toString())));
            assertEquals(0, fixture.channel.preorders.get());
            assertEquals(0, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch "
                    + "WHERE may_have_sent_at IS NOT NULL"));
        }
    }

    @Test
    void expiredParametersAndChangedCurrentUserCannotReplayWechatParameters() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            String requestId = UUID.randomUUID().toString();
            var first = fixture.dispatch.create(userCommand(booking.orderId(), requestId));
            fixture.foundation.db.jdbc.update("UPDATE user_account SET status='FROZEN' WHERE id=?",
                    Long.parseLong(USER));
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(
                    userCommand(booking.orderId(), requestId)));
            assertEquals(1, fixture.channel.preorders.get());
            fixture.foundation.db.jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",
                    Long.parseLong(USER));
            fixture.foundation.db.jdbc.update("UPDATE user_auth_identity SET open_id=? WHERE user_id=?",
                    "changed-openid", Long.parseLong(USER));
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(
                    userCommand(booking.orderId(), requestId)));
            assertEquals(1, fixture.channel.preorders.get());
            fixture.foundation.db.jdbc.update("UPDATE user_auth_identity SET open_id=? WHERE user_id=?",
                    OPEN_ID, Long.parseLong(USER));
            fixture.foundation.db.jdbc.update("UPDATE payment_dispatch "
                    + "SET parameter_valid_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND "
                    + "WHERE payment_id=?", Long.parseLong(first.paymentId()));
            assertThrows(RuntimeException.class, () -> fixture.dispatch.create(
                    userCommand(booking.orderId(), requestId)));
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(first.paymentNo(), fixture.foundation.text(
                    "SELECT payment_no FROM payment_order WHERE id=?",
                    Long.parseLong(first.paymentId())));
        }
    }

    @Test
    void closeAcknowledgementAloneCannotReleaseAnExposedPayment() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            var booking = fixture.foundation.book();
            fixture.dispatch.create(userCommand(booking.orderId(), UUID.randomUUID().toString()));
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            fixture.channel.queryState = TradeState.UNKNOWN;
            assertEquals(ExpiryEvidence.HOLD, fixture.dispatch.reconcileForExpiry(
                    expiryCommand(booking.orderId(), deadline)));
            assertEquals(1, fixture.channel.preorders.get());
            assertTrue(fixture.channel.queries.get() >= 1);
            assertEquals(1, fixture.channel.closes.get());
            assertEquals(0, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch "
                    + "WHERE state='TERMINAL_CLOSED'"));
            assertThrows(RuntimeException.class, () -> fixture.expireThroughOrder(
                    booking.orderId(), deadline));
            assertEquals("PENDING_PAYMENT", fixture.foundation.text(
                    "SELECT order_stage FROM pet_order WHERE id=?",
                    Long.parseLong(booking.orderId())));
        }
    }

    @Test
    void terminalQueryNeedsExplicitCapabilityAndNoInFlightAttempt() throws Exception {
        for (boolean capability : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(capability)) {
                var booking = fixture.foundation.book();
                fixture.dispatch.create(userCommand(booking.orderId(), UUID.randomUUID().toString()));
                OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
                fixture.channel.queryState = TradeState.CLOSE;
                ExpiryEvidence evidence = fixture.dispatch.reconcileForExpiry(
                        expiryCommand(booking.orderId(), deadline));
                assertEquals(capability ? ExpiryEvidence.VERIFIED_TERMINAL_CLOSED
                        : ExpiryEvidence.HOLD, evidence);
                assertEquals(1, fixture.channel.preorders.get());
                assertTrue(fixture.channel.queries.get() >= 1);
                if (capability) {
                    assertEquals(ExpireOrderResult.CLOSED, fixture.expireThroughOrder(
                            booking.orderId(), deadline));
                    assertEquals("CANCELED", fixture.foundation.text(
                            "SELECT order_stage FROM pet_order WHERE id=?",
                            Long.parseLong(booking.orderId())));
                } else {
                    assertThrows(RuntimeException.class, () -> fixture.expireThroughOrder(
                            booking.orderId(), deadline));
                    assertEquals("PENDING_PAYMENT", fixture.foundation.text(
                            "SELECT order_stage FROM pet_order WHERE id=?",
                            Long.parseLong(booking.orderId())));
                }
            }
        }
    }

    @Test
    void queryUsesOriginalTradeRequestDateAfterLocalMidnight() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            var payment = fixture.dispatch.create(userCommand(booking.orderId(),
                    UUID.randomUUID().toString()));
            // A persisted previous-day request models a resumed query after local midnight.
            LocalDate originalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
            fixture.foundation.db.jdbc.update("UPDATE payment_dispatch SET trade_req_date=? "
                    + "WHERE payment_id=?", originalDate, Long.parseLong(payment.paymentId()));
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            fixture.dispatch.reconcileForExpiry(expiryCommand(
                    booking.orderId(), deadline));
            assertEquals(originalDate, fixture.channel.tradeRequestDate.get());
        }
    }

    @Test
    void aCurrentNonCloseQueryCannotReuseAnOlderCloseReceipt() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            var booking = fixture.foundation.book();
            fixture.dispatch.create(userCommand(booking.orderId(), UUID.randomUUID().toString()));
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            fixture.channel.querySequence = List.of(TradeState.CLOSE, TradeState.UNKNOWN);
            assertEquals(ExpiryEvidence.HOLD, fixture.dispatch.reconcileForExpiry(
                    expiryCommand(booking.orderId(), deadline)));
            assertEquals(2, fixture.channel.queries.get());
            assertEquals(1, fixture.channel.closes.get());
            assertEquals(0, fixture.foundation.count("SELECT COUNT(*) FROM payment_dispatch "
                    + "WHERE state='TERMINAL_CLOSED'"));
            assertThrows(RuntimeException.class, () -> fixture.expireThroughOrder(
                    booking.orderId(), deadline));
            assertEquals("PENDING_PAYMENT", fixture.foundation.text(
                    "SELECT order_stage FROM pet_order WHERE id=?",
                    Long.parseLong(booking.orderId())));
        }
    }

    @Test
    void signedCallbackRacingVerifiedSuccessQueryCommitsOnePaymentSuccess() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            var booking = fixture.foundation.book();
            String requestId = UUID.randomUUID().toString();
            fixture.dispatch.create(userCommand(booking.orderId(), requestId));
            var prepared = fixture.foundation.prepare(booking.orderId(), requestId);
            var notice = fixture.foundation.notice(prepared, "SUCCESS", "qa-channel-trade-1",
                    prepared.amount(), prepared.amount());
            String rawTradeTime = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(notice.body()).path("trade_time").asText();
            fixture.channel.successfulTradeTime = LocalDateTime.parse(rawTradeTime,
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            fixture.channel.queryState = TradeState.SUCCESS;
            fixture.channel.queryEntered = new CountDownLatch(1);
            fixture.channel.releaseQuery = new CountDownLatch(1);
            OffsetDateTime deadline = fixture.expireOriginalDeadlineForTest(booking.orderId());
            try (var threads = Executors.newSingleThreadExecutor()) {
                var lookup = threads.submit(() -> fixture.dispatch.reconcileForExpiry(
                        expiryCommand(booking.orderId(), deadline)));
                assertTrue(fixture.channel.queryEntered.await(5, TimeUnit.SECONDS));
                try {
                    assertTrue(fixture.foundation.notification.receive(
                            notice.headers(), notice.body()).paid());
                } finally {
                    fixture.channel.releaseQuery.countDown();
                }
                assertEquals(ExpiryEvidence.HOLD, lookup.get(10, TimeUnit.SECONDS));
            }
            assertEquals("PAID", fixture.foundation.text(
                    "SELECT status FROM payment_order WHERE order_id=?",
                    Long.parseLong(booking.orderId())));
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM integration_event_outbox "
                    + "WHERE event_type='PaymentSucceededEvent.v1'"));
            assertEquals(1, fixture.channel.preorders.get());
            assertThrows(RuntimeException.class,
                    () -> fixture.dispatch.create(userCommand(booking.orderId(), requestId)));
            assertThrows(RuntimeException.class, () -> fixture.expireThroughOrder(
                    booking.orderId(), deadline));
            assertEquals("PENDING_PAYMENT", fixture.foundation.text(
                    "SELECT order_stage FROM pet_order WHERE id=?",
                    Long.parseLong(booking.orderId())));
        }
    }

    @Test
    void lostDatabaseCommitAckAfterVerifiedPreorderRecoversTheOriginalParameters()
            throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var booking = fixture.foundation.book();
            AtomicBoolean failNextCommit = new AtomicBoolean();
            AtomicInteger lostAcks = new AtomicInteger();
            DataSource source = new DelegatingDataSource(fixture.foundation.db.source) {
                @Override public Connection getConnection() throws SQLException {
                    return loseAckAfterRealCommit(super.getConnection(), failNextCommit, lostAcks);
                }
                @Override public Connection getConnection(String username, String password)
                        throws SQLException {
                    return loseAckAfterRealCommit(super.getConnection(username, password),
                            failNextCommit, lostAcks);
                }
            };
            var guard = new ScheduleCapacityGuardApiImpl(source);
            var orderFacts = new OrderPaymentFactsApiImpl(source, guard);
            var preparation = new PaymentPreparationApiImpl(source, IDS::incrementAndGet,
                    guard, orderFacts, new BookingUserFactsApiImpl(source, guard),
                    (merchantId, storeId) -> new PaymentMerchantBindings.Binding(
                            MERCHANT_NO, TERM_NO, SUB_APP));
            var publisher = new TransactionalOutboxPublisher(source,
                    IDS::incrementAndGet, new ObjectMapper());
            var notification = new PaymentNotificationService(source, IDS::incrementAndGet,
                    guard, fixture.foundation.verifier, publisher, ZoneId.of("Asia/Shanghai"));
            var dispatch = new PaymentDispatchService(source, IDS::incrementAndGet, guard,
                    orderFacts, new PaymentIdentityApiImpl(source, guard), preparation,
                    fixture.channel, notification, fixture.settings, fixture.clock);
            fixture.channel.beforePreorderReturn = () -> failNextCommit.set(true);
            String key = UUID.randomUUID().toString();

            var first = dispatch.create(userCommand(booking.orderId(), key));

            assertEquals(1, lostAcks.get(), "the database committed before its ACK was lost");
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(PAY_SIGN, first.wechatPayParameters().paySign());
            assertEquals("PARAMETERS_READY", fixture.foundation.text(
                    "SELECT state FROM payment_dispatch WHERE payment_id=?",
                    Long.parseLong(first.paymentId())));
            var replay = dispatch.create(userCommand(booking.orderId(), key));
            assertEquals(first.paymentNo(), replay.paymentNo());
            assertEquals(first.wechatPayParameters(), replay.wechatPayParameters());
            assertEquals(1, fixture.channel.preorders.get());
            assertEquals(1, fixture.foundation.count("SELECT COUNT(*) FROM payment_order"));
        }
    }

    private static Connection loseAckAfterRealCommit(Connection actual, AtomicBoolean armed,
            AtomicInteger lostAcks) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(actual, args);
                        if ("commit".equals(method.getName())
                                && armed.compareAndSet(true, false)) {
                            lostAcks.incrementAndGet();
                            throw new SQLException("QA commit ACK lost after actual commit");
                        }
                        return value;
                    } catch (InvocationTargetException delegated) {
                        throw delegated.getCause();
                    }
                });
    }

    private static PreparePaymentCommand userCommand(String orderId, String requestId) {
        return new PreparePaymentCommand(new CommandContext(requestId, "pay-coordination-qa",
                OperatorType.USER, USER, "MINIAPP"), orderId);
    }

    private static ReconcilePaymentExpiryCommand expiryCommand(String orderId,
            OffsetDateTime deadline) {
        return new ReconcilePaymentExpiryCommand(new CommandContext(
                "PAYMENT_EXPIRY_ORDER:" + orderId + ":0",
                "pay-coordination-expiry-qa", OperatorType.SYSTEM, null, "ASYNC_TASK"),
                orderId, STORE, deadline);
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock;
        final PaymentFoundationAcceptanceTest.Fixture foundation;
        final ScriptedChannel channel = new ScriptedChannel();
        final PaymentDispatchService dispatch;
        final OrderExpiryApiImpl orderExpiry;
        final PaymentDispatchService.Settings settings;

        Fixture(boolean terminalCloseCapability) throws Exception {
            this(terminalCloseCapability, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }

        Fixture(boolean terminalCloseCapability, Instant initialTime) throws Exception {
            clock = new MutableClock(initialTime);
            foundation = new PaymentFoundationAcceptanceTest.Fixture(clock);
            try {
                foundation.db.jdbc.update("INSERT INTO user_auth_identity"
                                + " (id,user_id,identity_type,app_id,open_id,created_at,updated_at)"
                                + " VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        9_810_100L, Long.parseLong(USER), "WECHAT_MINI", SUB_APP, OPEN_ID);
                byte[] rawKey = new byte[32];
                new SecureRandom().nextBytes(rawKey);
                SecretKey key = new SecretKeySpec(rawKey, "AES");
                settings = new PaymentDispatchService.Settings("OP123", "Pet service",
                        "127.0.0.1", "https://example.test/payment-notify",
                        ZoneId.of("Asia/Shanghai"), key, terminalCloseCapability);
                dispatch = new PaymentDispatchService(foundation.db.source, IDS::incrementAndGet,
                        foundation.guard,
                        new OrderPaymentFactsApiImpl(foundation.db.source, foundation.guard),
                        new PaymentIdentityApiImpl(foundation.db.source, foundation.guard),
                        foundation.preparation, channel, foundation.notification, settings, clock);
                var scheduleFacts = new ScheduleProtectionFactsApiImpl(foundation.db.source,
                        foundation.guard);
                var reservationExpiry = new ReservationExpiryApiImpl(foundation.db.source,
                        IDS::incrementAndGet, foundation.guard,
                        new OrderExpiryFactsApiImpl(foundation.db.source, foundation.guard),
                        scheduleFacts);
                orderExpiry = new OrderExpiryApiImpl(foundation.db.source, IDS::incrementAndGet,
                        foundation.guard, reservationExpiry,
                        new BookingPaymentExposureApiImpl(foundation.db.source,
                                foundation.guard, true),
                        new BookingCouponExposureApiImpl(foundation.db.source, foundation.guard),
                        dispatch);
            } catch (Exception failure) {
                foundation.close();
                throw failure;
            }
        }

        // Fixture time travel replaces waiting ten minutes. All original ORDER/SCH/PAYMENT
        // deadline facts move together, so production guards still read consistent rows.
        OffsetDateTime expireOriginalDeadlineForTest(String orderId) {
            return moveOriginalDeadlineForTest(orderId, Duration.ofSeconds(-1));
        }

        OffsetDateTime shortenOriginalDeadlineForTest(String orderId, Duration remaining) {
            return moveOriginalDeadlineForTest(orderId, remaining);
        }

        private OffsetDateTime moveOriginalDeadlineForTest(String orderId, Duration fromDatabaseNow) {
            LocalDateTime databaseNow = foundation.db.jdbc.queryForObject(
                    "SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
            LocalDateTime deadline = databaseNow.plus(fromDatabaseNow);
            foundation.db.jdbc.update("UPDATE pet_order SET payment_expire_at=? WHERE id=?",
                    deadline, Long.parseLong(orderId));
            foundation.db.jdbc.update("UPDATE schedule_reservation SET lock_expire_at=? "
                    + "WHERE order_id=?", deadline, Long.parseLong(orderId));
            foundation.db.jdbc.update("UPDATE payment_order SET expire_at=? WHERE order_id=?",
                    deadline, Long.parseLong(orderId));
            return deadline.atOffset(ZoneOffset.UTC);
        }

        ExpireOrderResult expireThroughOrder(String orderId, OffsetDateTime deadline) {
            Long reservationId = foundation.db.jdbc.queryForObject(
                    "SELECT reservation_id FROM pet_order WHERE id=?", Long.class,
                    Long.parseLong(orderId));
            String reservation = Long.toString(reservationId);
            return orderExpiry.expire(new ExpireOrderCommand(new CommandContext(
                    "TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0",
                    "pay-coordination-order-expiry-qa", OperatorType.SYSTEM, null,
                    "ASYNC_TASK"), orderId, reservation, 0, deadline));
        }

        @Override public void close() { foundation.close(); }
    }

    private static final class ScriptedChannel implements PaymentChannel {
        final AtomicInteger preorders = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<LocalDate> tradeRequestDate = new AtomicReference<>();
        volatile boolean failPreorder;
        volatile Runnable beforePreorderReturn;
        volatile TradeState queryState = TradeState.UNKNOWN;
        volatile List<TradeState> querySequence = List.of();
        volatile LocalDateTime successfulTradeTime;
        volatile CountDownLatch queryEntered;
        volatile CountDownLatch releaseQuery;

        @Override public VerifiedPreorder submitPreorder(PreorderInput input, RequestNonce nonce) {
            preorders.incrementAndGet();
            if (failPreorder) throw new IllegalStateException("QA channel result unknown");
            PreorderResult result = new PreorderResult(input.merchantNo(), input.outTradeNo(),
                    "qa-channel-trade-1", input.subAppId(), "qa-prepay-1", PAY_SIGN,
                    nonce.timestampSeconds(), nonce.nonce(), "prepay_id=qa-prepay-1", "RSA");
            if (beforePreorderReturn != null) beforePreorderReturn.run();
            return new VerifiedPreorder(result, "a".repeat(64));
        }

        @Override public VerifiedQuery lookup(QueryInput input, ExpectedPayment expected,
                RequestNonce nonce) {
            int call = queries.incrementAndGet();
            tradeRequestDate.set(input.tradeRequestDate());
            if (queryEntered != null) queryEntered.countDown();
            if (releaseQuery != null) {
                try {
                    if (!releaseQuery.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("QA query release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("QA query interrupted", interrupted);
                }
            }
            TradeState state = call <= querySequence.size()
                    ? querySequence.get(call - 1) : queryState;
            QueryResult result = new QueryResult(input.merchantNo(), input.outTradeNo(),
                    "qa-channel-trade-1", state, expected.expectedTotalCents(),
                    state == TradeState.SUCCESS ? expected.expectedTotalCents() : null,
                    state == TradeState.SUCCESS ? successfulTradeTime : null, "WECHAT");
            return new VerifiedQuery(result, state == TradeState.CLOSE
                    ? "b".repeat(64) : "d".repeat(64));
        }

        @Override public VerifiedClose requestClose(CloseInput input, RequestNonce nonce) {
            closes.incrementAndGet();
            return new VerifiedClose(new CloseAcknowledgement(input.originOutTradeNo(),
                    "qa-channel-trade-1", LocalDateTime.of(2026, 9, 28, 12, 0)),
                    "c".repeat(64));
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        void advance(Duration duration) { instant.updateAndGet(at -> at.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
