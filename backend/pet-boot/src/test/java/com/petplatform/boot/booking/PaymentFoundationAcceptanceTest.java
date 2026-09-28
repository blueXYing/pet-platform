package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.boot.config.PaymentFoundationConfiguration;
import com.petplatform.boot.config.BookingExpiryConfiguration;
import com.petplatform.common.*;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.*;
import com.petplatform.merchant.biz.apiimpl.*;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.dto.OrderCreationTypes.*;
import com.petplatform.order.api.dto.OrderExpiryTypes.*;
import com.petplatform.order.api.dto.OrderPaymentResultTypes.*;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.*;
import com.petplatform.payment.biz.apiimpl.*;
import com.petplatform.payment.biz.application.*;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.payment.biz.apiimpl.BookingPaymentExposureApiImpl;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local MySQL acceptance. RSA keys are generated per fixture; no channel network is used. */
class PaymentFoundationAcceptanceTest {
  private static final AtomicLong IDS = new AtomicLong(8_900_000_000_000_000L);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String USER = "710100";
  private static final String STORE = "710302";
  private static final String MERCHANT = "710301";
  private static final String MERCHANT_NO = "QA_MERCHANT_01";
  private static final String TERM_NO = "QA_TERM_01";
  private static final String SUB_APP = "wxQaSubApp01";
  private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  @Test
  void verifiedBytesCommitPaymentAndOutboxThenOrderConfirmsReservationExactlyOnce() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      CreateOrderResult booking = f.book();
      String orderId = booking.orderId();
      PreparedPayment intent = f.prepare(orderId, UUID.randomUUID().toString());
      assertFalse(intent.replayed());
      assertEquals(new BigDecimal("128.00"), intent.amount());
      assertEquals(booking.paymentExpireAt().toInstant(), intent.expireAt().toInstant());
      assertEquals("PREPARED", f.text("SELECT dispatch_state FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));

      SignedNotice notice = f.notice(intent, "SUCCESS", "QA_CHANNEL_01", intent.amount(), intent.amount());
      byte[] tampered = notice.body().clone();
      tampered[tampered.length - 2] ^= 1;
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(notice.headers(), tampered));
      assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));

      // A publisher connected through another DataSource object cannot commit a PAYMENT success
      // without its outbox in the caller's exact transaction.
      var wrongPublisher = new TransactionalOutboxPublisher(
          new DelegatingDataSource(f.db.source), PaymentFoundationAcceptanceTest::id, JSON);
      var wrong = new PaymentNotificationService(f.db.source, PaymentFoundationAcceptanceTest::id,
          f.guard, f.verifier, wrongPublisher, ZoneId.of("Asia/Shanghai"));
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> wrong.receive(notice.headers(), notice.body()));
      assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_channel_receipt"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));

      var accepted = f.notification.receive(notice.headers(), notice.body());
      assertTrue(accepted.paid());
      assertFalse(accepted.replayed());
      assertEquals("PAID", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_channel_receipt"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
      assertTrue(f.notification.receive(notice.headers(), notice.body()).replayed());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_channel_receipt"));

      // The consumer commits ORDER/SCH and its consume log, then the dispatcher loses its ACK.
      AtomicInteger deliveries = new AtomicInteger();
      IntegrationEventConsumer lostAck = new IntegrationEventConsumer() {
        @Override public String consumerName() { return f.result.consumerName(); }
        @Override public Set<String> eventTypes() { return f.result.eventTypes(); }
        @Override public void consume(DispatchedEvent event) {
          f.result.consume(event);
          if (deliveries.getAndIncrement() == 0) throw new IllegalStateException("QA lost dispatch ACK");
        }
      };
      try (var dispatcher = new OutboxDispatcher(f.db.source, "qa-pay-dispatch",
          new OutboxDispatchSettings(Duration.ofSeconds(3), Duration.ofMillis(500),
              Duration.ofMillis(20), 1),
          new OutboxRetryDelays(List.of(Duration.ofMillis(100))), List.of(lostAck))) {
        assertEquals(OutboxDispatcher.Outcome.FAILED, dispatcher.dispatchOne());
        assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=?", Long.parseLong(orderId)));
        assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation WHERE order_id=?", Long.parseLong(orderId)));
        assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_PAYMENT_SUCCEEDED'"));
        f.db.jdbc.update("UPDATE integration_event_outbox SET next_retry_at=UTC_TIMESTAMP(3) "
            + "WHERE event_type='PaymentSucceededEvent.v1'");
        assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
      }
      assertEquals(1, deliveries.get(), "committed consume log skips the handler after a lost ACK");
      assertEquals(1L, f.count("SELECT COUNT(*) FROM order_payment_result WHERE order_id=" + orderId));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='CONFIRM'"));
      assertEquals("PUBLISHED", f.text("SELECT status FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
      assertEquals("PAID", f.text("SELECT payment_status FROM pet_order WHERE id=?", Long.parseLong(orderId)));
      assertEquals("2030-01-01", f.text("SELECT DATE_FORMAT(appointment_start_at,'%Y-%m-%d') FROM pet_order WHERE id=?", Long.parseLong(orderId)));
    }
  }

  @Test
  void defaultOffMissingBindingAndMissingCertificateNeverCreatePaidFacts() throws Exception {
    new ApplicationContextRunner().withUserConfiguration(PaymentFoundationConfiguration.class)
        .run(context -> {
          assertFalse(context.containsBean("paymentPreparationApi"));
          assertFalse(context.containsBean("paymentNotificationService"));
          assertFalse(context.containsBean("orderPaymentResultConsumer"));
        });
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      String orderId = f.book().orderId();
      var noBinding = new PaymentPreparationApiImpl(f.db.source, PaymentFoundationAcceptanceTest::id,
          f.guard, new OrderPaymentFactsApiImpl(f.db.source, f.guard),
          new BookingUserFactsApiImpl(f.db.source, f.guard),
          (merchantId, storeId) -> { throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
              "test merchant binding absent"); });
      String key = UUID.randomUUID().toString();
      var command = new PreparePaymentCommand(new CommandContext(key, "qa-no-binding",
          OperatorType.USER, USER, "MINIAPP"), orderId);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> noBinding.prepare(command));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_intent_request"),
          "failed eligibility retains the original request binding");
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_order"));
      assertCode("IDEMPOTENCY_KEY_CONFLICT", () -> f.prepare("710999", key));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_intent_request"));
      PreparedPayment intent = f.prepare(orderId, key);
      assertNotNull(intent.paymentId());
      SignedNotice notice = f.notice(intent, "SUCCESS", "QA_CHANNEL_NO_KEY",
          intent.amount(), intent.amount());
      var noCertificate = new PaymentNotificationService(f.db.source,
          PaymentFoundationAcceptanceTest::id, f.guard,
          (headers, raw, expected) -> { throw new ApiException(
              CommonApiCodes.DEPENDENCY_UNAVAILABLE, "notification key absent"); }, f.publisher,
          ZoneId.of("Asia/Shanghai"));
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> noCertificate.receive(notice.headers(), notice.body()));
      var noConfirmedZone = new PaymentNotificationService(f.db.source,
          PaymentFoundationAcceptanceTest::id, f.guard, f.verifier, f.publisher);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> noConfirmedZone.receive(
          notice.headers(), notice.body()));
      assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_channel_receipt"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
      // A second DataSource object cannot claim an event in the ORDER transaction either.
      var wrongClaim = new JdbcOutboxConsumeGuard(new DelegatingDataSource(f.db.source),
          PaymentFoundationAcceptanceTest::id);
      var event = new DispatchedEvent(Long.toString(id()), "PaymentSucceededEvent.v1", 1,
          OffsetDateTime.now(ZoneOffset.UTC), "PAYMENT", Long.parseLong(intent.paymentId()),
          null, "{}");
      var tx = new TransactionTemplate(new DataSourceTransactionManager(f.db.source));
      assertThrows(IllegalStateException.class, () -> tx.execute(status ->
          wrongClaim.tryClaim("ORDER_PAYMENT_SUCCEEDED", event)));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_consume_log"));
    }
  }

  @Test
  void signedUnknownAndMismatchedNoticesCannotFulfillOrRewritePayment() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      String orderId = f.book().orderId();
      PreparedPayment intent = f.prepare(orderId, UUID.randomUUID().toString());
      SignedNotice valid = f.notice(intent, "SUCCESS", "QA_CHANNEL_02", intent.amount(), intent.amount());
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(
          f.sign(new String(valid.body(), StandardCharsets.UTF_8)
              .replace(MERCHANT_NO, "OTHER_MERCHANT").getBytes(StandardCharsets.UTF_8)).headers(),
          new String(valid.body(), StandardCharsets.UTF_8)
              .replace(MERCHANT_NO, "OTHER_MERCHANT").getBytes(StandardCharsets.UTF_8)));
      SignedNotice wrongTotal = f.sign(new String(valid.body(), StandardCharsets.UTF_8)
          .replace("\"total_amount\":\"12800\"", "\"total_amount\":\"12900\"")
          .getBytes(StandardCharsets.UTF_8));
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(
          wrongTotal.headers(), wrongTotal.body()));
      SignedNotice wrongPayment = f.sign(new String(valid.body(), StandardCharsets.UTF_8)
          .replace(intent.paymentNo(), "999999999")
          .getBytes(StandardCharsets.UTF_8));
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(
          wrongPayment.headers(), wrongPayment.body()));
      assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));

      SignedNotice unknown = f.notice(intent, "UNKNOWN", "QA_CHANNEL_02", intent.amount(), intent.amount());
      var pending = f.notification.receive(unknown.headers(), unknown.body());
      assertFalse(pending.paid());
      assertEquals("PAYING", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
      assertTrue(f.notification.receive(valid.headers(), valid.body()).paid());
      assertEquals("PAID", f.text("SELECT status FROM payment_order WHERE id=?", Long.parseLong(intent.paymentId())));
      SignedNotice alteredTrade = f.notice(intent, "SUCCESS", "QA_CHANNEL_DIFFERENT", intent.amount(), intent.amount());
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(
          alteredTrade.headers(), alteredTrade.body()));
      SignedNotice alteredAmount = f.notice(intent, "SUCCESS", "QA_CHANNEL_02",
          intent.amount(), new BigDecimal("100.00"));
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> f.notification.receive(
          alteredAmount.headers(), alteredAmount.body()));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
      assertEquals(2L, f.count("SELECT COUNT(*) FROM payment_channel_receipt"),
          "UNKNOWN plus original SUCCESS are the only accepted receipts");
    }
  }

  @Test
  void lateVerifiedPaymentKeepsTimedOutOrderClosedAndEmitsActualAmountOnce() throws Exception {
    Clock beforeDeadline = Clock.fixed(Instant.now().minus(11, ChronoUnit.MINUTES)
        .truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    try (Fixture f = new Fixture(beforeDeadline)) {
      CreateOrderResult booking = f.book();
      long order = Long.parseLong(booking.orderId());
      long reservation = f.db.jdbc.queryForObject(
          "SELECT reservation_id FROM pet_order WHERE id=?", Long.class, order);
      var expiryCommand = new ExpireOrderCommand(new CommandContext(
          "TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0", "pay-qa-expire",
          OperatorType.SYSTEM, null, "ASYNC_TASK"), booking.orderId(),
          Long.toString(reservation), 0, booking.paymentExpireAt());
      assertEquals(ExpireOrderResult.CLOSED, f.expiry.expire(expiryCommand));
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?", order));
      assertEquals("EXPIRED", f.text("SELECT status FROM schedule_reservation WHERE id=?", reservation));

      // This seeded row represents an older/externally initiated channel attempt whose success
      // arrives after the already committed timeout. The current no-payment slice cannot prepare
      // a new payment against a closed order, and this test does not claim that it can.
      long paymentId = id(), paymentNo = id();
      f.db.jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,expire_at,"
              + "store_id,merchant_id,user_id,merchant_no,term_no,sub_appid,currency,dispatch_state,created_at,updated_at)"
              + " VALUES(?,?,?,128.00,'INIT','LAKALA_WECHAT',?,?,?,?,?,?,?,'CNY','PREPARED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          paymentId, paymentNo, order,
          booking.paymentExpireAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
          Long.parseLong(STORE), Long.parseLong(MERCHANT), Long.parseLong(USER),
          MERCHANT_NO, TERM_NO, SUB_APP);
      PreparedPayment historical = new PreparedPayment(Long.toString(paymentId),
          Long.toString(paymentNo), booking.orderId(), new BigDecimal("128.00"),
          booking.paymentExpireAt(), false);
      SignedNotice late = f.notice(historical, "SUCCESS", "QA_LATE_TRADE_01",
          historical.amount(), new BigDecimal("97.35"));
      assertTrue(f.notification.receive(late.headers(), late.body()).paid());
      try (var dispatcher = f.dispatcher(f.result)) {
        assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
      }
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?", order));
      assertEquals("PAYMENT_TIMEOUT", f.text("SELECT cancel_reason FROM pet_order WHERE id=?", order));
      assertEquals("PAID", f.text("SELECT payment_status FROM pet_order WHERE id=?", order));
      assertEquals("EXPIRED", f.text("SELECT status FROM schedule_reservation WHERE id=?", reservation));
      assertEquals("LATE", f.text("SELECT result_type FROM order_payment_result WHERE order_id=?", order));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'"));
      assertEquals("97.35", f.text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.channelPaidAmount')) "
          + "FROM integration_event_outbox WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_order"),
          "this slice has a durable refund intent event, not a completed refund order");
      assertTrue(f.notification.receive(late.headers(), late.body()).replayed());
      long sourceEvent = f.db.jdbc.queryForObject(
          "SELECT success_event_id FROM payment_order WHERE id=?", Long.class, paymentId);
      assertEquals(ConsumePaymentResult.NOOP, f.result.consumePaymentSucceeded(
          new ConsumePaymentSucceededCommand(new CommandContext(
              "EVENT:PAYMENT_SUCCEEDED:" + sourceEvent, "late-replay", OperatorType.SYSTEM,
              null, "OUTBOX"), Long.toString(sourceEvent), Long.toString(paymentId),
              booking.orderId(), "QA_LATE_TRADE_01", new BigDecimal("97.35"),
              f.db.jdbc.queryForObject("SELECT paid_at FROM payment_order WHERE id=?",
                  LocalDateTime.class, paymentId).atOffset(ZoneOffset.UTC))));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'"));
    }
  }

  @Test
  void callbackAndExpiryOnSeparateConnectionsCannotCommitPaidWithExpiredReservation() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      CreateOrderResult booking = f.book();
      PreparedPayment payment = f.prepare(booking.orderId(), UUID.randomUUID().toString());
      long reservation = f.db.jdbc.queryForObject("SELECT reservation_id FROM pet_order WHERE id=?",
          Long.class, Long.parseLong(booking.orderId()));
      // Move only the isolated fixture's pair of authoritative deadlines to the past. A real
      // task retains its immutable generation; this setup isolates the same-store race itself.
      OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
          .truncatedTo(ChronoUnit.MILLIS);
      LocalDateTime rawDue = due.toLocalDateTime();
      f.db.jdbc.update("UPDATE pet_order SET payment_expire_at=? WHERE id=?",
          rawDue, Long.parseLong(booking.orderId()));
      f.db.jdbc.update("UPDATE schedule_reservation SET lock_expire_at=? WHERE id=?",
          rawDue, reservation);
      SignedNotice notice = f.notice(payment, "SUCCESS", "QA_RACE_TRADE",
          payment.amount(), payment.amount());
      var expiryCommand = new ExpireOrderCommand(new CommandContext(
          "TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0", "race-expiry",
          OperatorType.SYSTEM, null, "ASYNC_TASK"), booking.orderId(), Long.toString(reservation),
          0, due);
      CountDownLatch start = new CountDownLatch(1);
      try (var threads = Executors.newFixedThreadPool(2)) {
        Future<String> timeout = threads.submit(() -> {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          try { f.expiry.expire(expiryCommand); return "UNSAFE_CLOSED"; }
          catch (ApiException rejected) { return rejected.code(); }
        });
        Future<PaymentNotificationService.ReceiptResult> callback = threads.submit(() -> {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          return f.notification.receive(notice.headers(), notice.body());
        });
        start.countDown();
        assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", timeout.get(10, TimeUnit.SECONDS));
        assertTrue(callback.get(10, TimeUnit.SECONDS).paid());
      }
      assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(booking.orderId())));
      assertEquals("TEMP_LOCKED", f.text("SELECT status FROM schedule_reservation WHERE id=?",
          reservation));
      try (var dispatcher = f.dispatcher(f.result)) {
        assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
      }
      assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(booking.orderId())));
      assertEquals("CONFIRMED", f.text("SELECT status FROM schedule_reservation WHERE id=?",
          reservation));
    }
  }

  @Test
  void signedUnderpaymentRemainsDurableChannelFactButCannotFulfillOrder() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      CreateOrderResult booking = f.book();
      PreparedPayment payment = f.prepare(booking.orderId(), UUID.randomUUID().toString());
      SignedNotice underpaid = f.notice(payment, "SUCCESS", "QA_UNDERPAID_TRADE",
          payment.amount(), new BigDecimal("100.00"));
      assertTrue(f.notification.receive(underpaid.headers(), underpaid.body()).paid());
      try (var dispatcher = f.dispatcher(f.result)) {
        assertEquals(OutboxDispatcher.Outcome.FAILED, dispatcher.dispatchOne());
      }
      assertEquals("PAID", f.text("SELECT status FROM payment_order WHERE id=?",
          Long.parseLong(payment.paymentId())));
      assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(booking.orderId())));
      assertEquals("TEMP_LOCKED", f.text("SELECT status FROM schedule_reservation WHERE order_id=?",
          Long.parseLong(booking.orderId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM order_payment_result"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_PAYMENT_SUCCEEDED'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
      assertEquals("FAILED", f.text("SELECT status FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
    }
  }

  @Test
  void reversalBeforeSuccessOrBeforeOrderConsumptionPausesFulfillment() throws Exception {
    for (String reversal : List.of("REFUND", "PART_REFUND", "REVOKED")) {
      try (Fixture f = new Fixture(Clock.systemUTC())) {
        CreateOrderResult booking = f.book();
        PreparedPayment payment = f.prepare(booking.orderId(), UUID.randomUUID().toString());
        SignedNotice reversed = f.notice(payment, reversal, "QA_REVERSED_TRADE",
            payment.amount(), payment.amount());
        assertFalse(f.notification.receive(reversed.headers(), reversed.body()).paid());
        assertEquals("RECONCILIATION_REQUIRED", f.text(
            "SELECT dispatch_state FROM payment_order WHERE id=?", Long.parseLong(payment.paymentId())));
        SignedNotice delayedSuccess = f.notice(payment, "SUCCESS", "QA_REVERSED_TRADE",
            payment.amount(), payment.amount());
        assertFalse(f.notification.receive(delayedSuccess.headers(), delayedSuccess.body()).paid());
        assertEquals("INIT", f.text("SELECT status FROM payment_order WHERE id=?",
            Long.parseLong(payment.paymentId())), reversal);
        assertEquals("RECONCILIATION_REQUIRED", f.text(
            "SELECT dispatch_state FROM payment_order WHERE id=?", Long.parseLong(payment.paymentId())));
        assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'"));
        assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?",
            Long.parseLong(booking.orderId())));
        assertEquals("TEMP_LOCKED", f.text("SELECT status FROM schedule_reservation WHERE order_id=?",
            Long.parseLong(booking.orderId())));
      }
    }
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      CreateOrderResult booking = f.book();
      PreparedPayment payment = f.prepare(booking.orderId(), UUID.randomUUID().toString());
      SignedNotice success = f.notice(payment, "SUCCESS", "QA_SUCCESS_THEN_REFUND",
          payment.amount(), payment.amount());
      assertTrue(f.notification.receive(success.headers(), success.body()).paid());
      SignedNotice refund = f.notice(payment, "REFUND", "QA_SUCCESS_THEN_REFUND",
          payment.amount(), payment.amount());
      assertTrue(f.notification.receive(refund.headers(), refund.body()).paid(),
          "historical channel success remains recorded while fulfillment is paused");
      assertEquals("PAID", f.text("SELECT status FROM payment_order WHERE id=?",
          Long.parseLong(payment.paymentId())));
      assertEquals("RECONCILIATION_REQUIRED", f.text(
          "SELECT dispatch_state FROM payment_order WHERE id=?", Long.parseLong(payment.paymentId())));
      try (var dispatcher = f.dispatcher(f.result)) {
        assertEquals(OutboxDispatcher.Outcome.FAILED, dispatcher.dispatchOne());
      }
      assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(booking.orderId())));
      assertEquals("TEMP_LOCKED", f.text("SELECT status FROM schedule_reservation WHERE order_id=?",
          Long.parseLong(booking.orderId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM order_payment_result"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
    }
  }

  @Test
  void paymentIntentAndChannelTimeAreStoredAsRawUtcUnderBothJvmZones() throws Exception {
    TimeZone previous = TimeZone.getDefault();
    try {
      for (String zone : List.of("Asia/Shanghai", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try (Fixture f = new Fixture(Clock.systemUTC())) {
          CreateOrderResult booking = f.book();
          PreparedPayment payment = f.prepare(booking.orderId(), UUID.randomUUID().toString());
          String expectedExpiry = rawUtc(booking.paymentExpireAt());
          assertEquals(expectedExpiry, f.text("SELECT DATE_FORMAT(expire_at,'%Y-%m-%d %H:%i:%s.%f') "
              + "FROM payment_order WHERE id=?", Long.parseLong(payment.paymentId())), zone);
          SignedNotice notice = f.notice(payment, "SUCCESS", "QA_ZONE_" + zone.replace('/', '_'),
              payment.amount(), payment.amount());
          String trade = JSON.readTree(notice.body()).path("trade_time").asText();
          OffsetDateTime expectedPaid = LocalDateTime.parse(trade, TRADE_TIME)
              .atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()
              .withOffsetSameInstant(ZoneOffset.UTC);
          assertTrue(f.notification.receive(notice.headers(), notice.body()).paid());
          try (var dispatcher = f.dispatcher(f.result)) {
            assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
          }
          String paidRaw = rawUtc(expectedPaid);
          assertEquals(paidRaw, f.text("SELECT DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i:%s.%f') "
              + "FROM payment_order WHERE id=?", Long.parseLong(payment.paymentId())), zone);
          assertEquals(paidRaw, f.text("SELECT DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i:%s.%f') "
              + "FROM pet_order WHERE id=?", Long.parseLong(booking.orderId())), zone);
          assertEquals(rawUtc(expectedPaid.plusMinutes(30)), f.text(
              "SELECT DATE_FORMAT(confirm_deadline,'%Y-%m-%d %H:%i:%s.%f') FROM pet_order WHERE id=?",
              Long.parseLong(booking.orderId())), zone);
        }
      }
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  @Test
  void committedPaymentWithLostExecutionAckReturnsTheOriginalPayment() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      String orderId = f.book().orderId();
      var source = new LostSecondCommitSource(f.db.source);
      var guard = new ScheduleCapacityGuardApiImpl(source);
      var preparation = new PaymentPreparationApiImpl(source, PaymentFoundationAcceptanceTest::id,
          guard, new OrderPaymentFactsApiImpl(source, guard),
          new BookingUserFactsApiImpl(source, guard),
          (merchant, store) -> new PaymentMerchantBindings.Binding(MERCHANT_NO, TERM_NO, SUB_APP));
      String request = UUID.randomUUID().toString();
      var command = new PreparePaymentCommand(new CommandContext(request, "lost-ack-qa",
          OperatorType.USER, USER, "MINIAPP"), orderId);
      PreparedPayment recovered = preparation.prepare(command);
      assertTrue(recovered.replayed(), "the first execution committed before its ACK was lost");
      assertEquals(2, source.lostAtCommit.get(), "the execution ACK, not admission, was lost");
      assertEquals(3, source.commits.get(), "admission, committed execution and recovery read");
      assertEquals(recovered.paymentId(), f.text("SELECT CAST(id AS CHAR) FROM payment_order WHERE order_id=?",
          Long.parseLong(orderId)));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_intent_request"));
      PreparedPayment later = preparation.prepare(command);
      assertTrue(later.replayed());
      assertEquals(recovered.paymentId(), later.paymentId());
      assertEquals(recovered.paymentNo(), later.paymentNo());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_order"));
    }
  }

  @Test
  void lockedOriginalIntentReturnsBoundedBusyConflictWithoutCreatingPayment() throws Exception {
    try (Fixture f = new Fixture(Clock.systemUTC())) {
      String orderId = f.book().orderId();
      var failedBinding = new PaymentPreparationApiImpl(f.db.source,
          PaymentFoundationAcceptanceTest::id, f.guard,
          new OrderPaymentFactsApiImpl(f.db.source, f.guard),
          new BookingUserFactsApiImpl(f.db.source, f.guard),
          (merchant, store) -> { throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
              "test binding unavailable"); });
      String request = UUID.randomUUID().toString();
      var command = new PreparePaymentCommand(new CommandContext(request, "lock-qa",
          OperatorType.USER, USER, "MINIAPP"), orderId);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> failedBinding.prepare(command));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_intent_request"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_order"));
      CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
      try (var thread = Executors.newSingleThreadExecutor()) {
        Future<?> locker = thread.submit(() -> {
          var tx = new TransactionTemplate(new DataSourceTransactionManager(f.db.source));
          tx.executeWithoutResult(status -> {
            assertNotNull(f.db.jdbc.queryForObject(
                "SELECT id FROM payment_intent_request WHERE order_id=? FOR UPDATE",
                Long.class, Long.parseLong(orderId)));
            held.countDown();
            try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted); }
          });
        });
        assertTrue(held.await(3, TimeUnit.SECONDS));
        long started = System.nanoTime();
        try {
          assertCode("COMMON_CONFLICT", () -> f.preparation.prepare(command));
          assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(6)) < 0,
              "request binding lock has a bounded 2-second wait budget");
        } finally {
          release.countDown();
        }
        locker.get(5, TimeUnit.SECONDS);
      }
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_intent_request"));
      PreparedPayment retried = f.preparation.prepare(command);
      assertNotNull(retried.paymentId());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_order"));
    }
  }

  private static String rawUtc(OffsetDateTime time) {
    return time.withOffsetSameInstant(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'000'"));
  }

  private static long id() { return IDS.incrementAndGet(); }

  /** Simulates a DB commit that succeeded while only the client acknowledgement was lost. */
  private static final class LostSecondCommitSource extends DelegatingDataSource {
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger lostAtCommit = new AtomicInteger();

    LostSecondCommitSource(DataSource target) { super(target); }

    @Override public Connection getConnection() throws SQLException {
      return wrap(super.getConnection());
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
      return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection delegate) {
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
            try {
              Object result = method.invoke(delegate, arguments);
              if ("commit".equals(method.getName()) && commits.incrementAndGet() == 2) {
                lostAtCommit.set(2);
                throw new SQLException("committed payment ACK lost", "08006");
              }
              return result;
            } catch (InvocationTargetException wrapped) {
              throw wrapped.getCause();
            }
          });
    }
  }

  private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
    ApiException failure = assertThrows(ApiException.class, action);
    assertEquals(code, failure.code());
  }

  record SignedNotice(Map<String, String> headers, byte[] body) {}

  static final class Fixture implements AutoCloseable {
    final BookingCreateAcceptanceTest.Database db;
    final ScheduleCapacityGuardApiImpl guard;
    final PaymentReceiptVerifier verifier;
    final PaymentNotificationService notification;
    final PaymentPreparationApiImpl preparation;
    final OrderPaymentResultApiImpl result;
    final OrderExpiryApiImpl expiry;
    final TransactionalOutboxPublisher publisher;
    final KeyPair channelKey;
    private final OrderCreationApiImpl creation;

    Fixture(Clock clock) throws Exception {
      db = new BookingCreateAcceptanceTest.Database();
      try {
        db.seedBookableFacts();
        guard = new ScheduleCapacityGuardApiImpl(db.source);
        var scheduleFacts = new ScheduleProtectionFactsApiImpl(db.source, guard);
        var staffFacts = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
        var orderFacts = new OrderProtectionFactsApiImpl(db.source, guard, scheduleFacts,
            staffFacts, clock);
        var proof = new ScheduleCapacityProofApiImpl(db.source, guard, scheduleFacts,
            staffFacts, orderFacts, clock, 10_000);
        var hold = new ReservationHoldApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, scheduleFacts, proof, orderFacts, clock);
        var approval = new PersistentApplicationReviewFactsReader(db.source,
            PaymentFoundationAcceptanceTest::id);
        var users = new BookingUserFactsApiImpl(db.source, guard);
        creation = new OrderCreationApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            users, new BookingMerchantFactsApiImpl(db.source, guard, approval),
            new BookingServiceFactsApiImpl(db.source, guard), guard, hold,
            new BookingCreateAcceptanceTest.InputProtector(), null, clock);
        var orderPayment = new OrderPaymentFactsApiImpl(db.source, guard);
        preparation = new PaymentPreparationApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, orderPayment, users,
            (merchantId, storeId) -> new PaymentMerchantBindings.Binding(
                MERCHANT_NO, TERM_NO, SUB_APP));
        channelKey = rsa();
        verifier = (headers, raw, expected) -> {
          long cents = expected.expectedAmount().movePointRight(2).longValueExact();
          var observed = LakalaProtocol.verifyNotification(headers, raw, channelKey.getPublic(),
              new LakalaProtocol.ExpectedPayment(expected.merchantNo(), expected.paymentNo(), cents));
          return new PaymentReceiptVerifier.VerifiedNotice(observed.merchantNo(),
              observed.outTradeNo(), observed.channelTradeNo(), observed.tradeStatus().name(),
              BigDecimal.valueOf(observed.totalAmountCents(), 2),
              observed.payerAmountCents() == null ? null
                  : BigDecimal.valueOf(observed.payerAmountCents(), 2),
              observed.channelTradeTime(), observed.accountType());
        };
        publisher = new TransactionalOutboxPublisher(db.source,
            PaymentFoundationAcceptanceTest::id, JSON);
        notification = new PaymentNotificationService(db.source, PaymentFoundationAcceptanceTest::id,
            guard, verifier, publisher, ZoneId.of("Asia/Shanghai"));
        var orderExpiryFacts = new OrderExpiryFactsApiImpl(db.source, guard);
        var expiration = new ReservationExpiryApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, orderExpiryFacts, scheduleFacts);
        expiry = new OrderExpiryApiImpl(db.source, PaymentFoundationAcceptanceTest::id, guard,
            expiration, new BookingPaymentExposureApiImpl(db.source, guard),
            new BookingCouponExposureApiImpl(db.source, guard));
        var confirmation = new ReservationConfirmApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, scheduleFacts, orderPayment);
        result = new OrderPaymentResultApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, new PaymentSuccessFactsApiImpl(db.source, guard), confirmation, expiration,
            publisher);
      } catch (Exception failure) {
        db.close();
        throw failure;
      }
    }

    CreateOrderResult book() {
      OffsetDateTime start = OffsetDateTime.parse("2030-01-01T09:00:00Z");
      return creation.create(new CreateOrderCommand(new CommandContext(UUID.randomUUID().toString(),
          "pay-qa-create", OperatorType.USER, USER, "MINIAPP"), STORE, "710401", "710200",
          "IN_STORE", start, start.plusMinutes(90), null, null, "710500", null, null,
          null, null, null));
    }

    PreparedPayment prepare(String orderId, String requestId) {
      return preparation.prepare(new PreparePaymentCommand(new CommandContext(requestId,
          "pay-qa-intent", OperatorType.USER, USER, "MINIAPP"), orderId));
    }

    SignedNotice notice(PreparedPayment payment, String state, String trade,
        BigDecimal total, BigDecimal paid) throws Exception {
      String localTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
          .minusMinutes(1).format(TRADE_TIME);
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("merchant_no", MERCHANT_NO);
      fields.put("out_trade_no", payment.paymentNo());
      fields.put("trade_no", trade);
      fields.put("trade_status", state);
      fields.put("total_amount", cents(total));
      fields.put("payer_amount", cents(paid));
      fields.put("trade_time", localTime);
      fields.put("account_type", "WECHAT");
      return sign(JSON.writeValueAsBytes(fields));
    }

    SignedNotice sign(byte[] raw) throws Exception {
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      String nonce = "ABCDEF123456";
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(channelKey.getPrivate());
      signer.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.US_ASCII));
      signer.update(raw);
      signer.update((byte) '\n');
      String signature = Base64.getEncoder().encodeToString(signer.sign());
      return new SignedNotice(Map.of("Authorization", "LKLAPI-SHA256withRSA timestamp=\""
          + timestamp + "\",nonce_str=\"" + nonce + "\",signature=\"" + signature + "\""), raw);
    }

    private static String cents(BigDecimal amount) {
      return amount.movePointRight(2).toBigIntegerExact().toString();
    }

    private static KeyPair rsa() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    }

    String text(String sql, Object arg) { return db.jdbc.queryForObject(sql, String.class, arg); }
    String text(String sql) { return db.jdbc.queryForObject(sql, String.class); }
    long count(String sql) { return db.jdbc.queryForObject(sql, Long.class); }
    OutboxDispatcher dispatcher(IntegrationEventConsumer consumer) {
      return new OutboxDispatcher(db.source, "qa-payment-dispatch", new OutboxDispatchSettings(
          Duration.ofSeconds(3), Duration.ofMillis(500), Duration.ofMillis(20), 1),
          new OutboxRetryDelays(List.of(Duration.ofMillis(100))), List.of(consumer));
    }
    @Override public void close() { db.close(); }
  }
}
