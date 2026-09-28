package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.boot.config.PaymentFoundationConfiguration;
import com.petplatform.common.*;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.*;
import com.petplatform.merchant.biz.apiimpl.*;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.dto.OrderCreationTypes.*;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.*;
import com.petplatform.payment.biz.apiimpl.*;
import com.petplatform.payment.biz.application.*;
import com.petplatform.payment.biz.infrastructure.provider.LakalaProtocol;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DelegatingDataSource;

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
          f.guard, f.verifier, wrongPublisher);
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

  private static long id() { return IDS.incrementAndGet(); }

  private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
    ApiException failure = assertThrows(ApiException.class, action);
    assertEquals(code, failure.code());
  }

  private record SignedNotice(Map<String, String> headers, byte[] body) {}

  private static final class Fixture implements AutoCloseable {
    final BookingCreateAcceptanceTest.Database db;
    final ScheduleCapacityGuardApiImpl guard;
    final PaymentReceiptVerifier verifier;
    final PaymentNotificationService notification;
    final PaymentPreparationApiImpl preparation;
    final OrderPaymentResultApiImpl result;
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
        var outbox = new TransactionalOutboxPublisher(db.source,
            PaymentFoundationAcceptanceTest::id, JSON);
        notification = new PaymentNotificationService(db.source, PaymentFoundationAcceptanceTest::id,
            guard, verifier, outbox);
        var orderExpiryFacts = new OrderExpiryFactsApiImpl(db.source, guard);
        var expiration = new ReservationExpiryApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, orderExpiryFacts, scheduleFacts);
        var confirmation = new ReservationConfirmApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, scheduleFacts, orderPayment);
        result = new OrderPaymentResultApiImpl(db.source, PaymentFoundationAcceptanceTest::id,
            guard, new PaymentSuccessFactsApiImpl(db.source, guard), confirmation, expiration,
            outbox);
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
    @Override public void close() { db.close(); }
  }
}
