package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparedPayment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Real isolated MySQL acceptance. Payment proof comes from a signed offline channel notice. */
class LateRefundAcceptanceTest {
  private static final AtomicLong IDS = new AtomicLong(8_910_000_000_000_000L);
  private static final BigDecimal CHANNEL_PAID = new BigDecimal("97.35");

  @Test
  void signedLatePaymentFixtureKeepsTimeoutClosureAndActualPaidAmount() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      long order = Long.parseLong(late.orderId());
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?", order));
      assertEquals("PAYMENT_TIMEOUT", f.text("SELECT cancel_reason FROM pet_order WHERE id=?", order));
      assertEquals("EXPIRED", f.text("SELECT status FROM schedule_reservation WHERE id=?",
          late.reservationId()));
      assertEquals("LATE", f.text("SELECT result_type FROM order_payment_result WHERE order_id=?", order));
      assertEquals(CHANNEL_PAID, f.db.jdbc.queryForObject(
          "SELECT channel_paid_amount FROM order_payment_result WHERE order_id=?",
          BigDecimal.class, order));
      assertEquals(CHANNEL_PAID, f.db.jdbc.queryForObject(
          "SELECT channel_paid_amount FROM payment_order WHERE id=?", BigDecimal.class,
          Long.parseLong(late.paymentId())));
      assertEquals("LatePaymentSucceededAfterTimeoutEvent.v1", late.event().eventType());
      assertEquals(1, late.event().eventVersion());
      assertEquals("ORDER", late.event().aggregateType());
      assertEquals(order, late.event().aggregateId());
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='OrderPaidEvent.v1'"));
    }
  }

  private static PaymentFoundationAcceptanceTest.Fixture fixture() throws Exception {
    Clock beforeDeadline = Clock.fixed(Instant.now().minus(11, ChronoUnit.MINUTES)
        .truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    return new PaymentFoundationAcceptanceTest.Fixture(beforeDeadline);
  }

  private static LatePayment latePayment(PaymentFoundationAcceptanceTest.Fixture f)
      throws Exception {
    CreateOrderResult booking = f.book();
    long order = Long.parseLong(booking.orderId());
    long reservation = f.db.jdbc.queryForObject(
        "SELECT reservation_id FROM pet_order WHERE id=?", Long.class, order);
    var expiry = new ExpireOrderCommand(new CommandContext(
        "TASK:RESERVATION_HOLD_EXPIRE:" + reservation + ":0", "late-refund-qa-expire",
        OperatorType.SYSTEM, null, "ASYNC_TASK"), booking.orderId(),
        Long.toString(reservation), 0, booking.paymentExpireAt());
    assertEquals(ExpireOrderResult.CLOSED, f.expiry.expire(expiry));

    // Historical channel intent: the current preparation API correctly rejects closed orders.
    long paymentId = IDS.incrementAndGet(), paymentNo = IDS.incrementAndGet();
    f.db.jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,expire_at,"
            + "store_id,merchant_id,user_id,merchant_no,term_no,sub_appid,currency,dispatch_state,created_at,updated_at)"
            + " VALUES(?,?,?,128.00,'INIT','LAKALA_WECHAT',?,?,?,?,?,?,?,'CNY','PREPARED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        paymentId, paymentNo, order,
        booking.paymentExpireAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
        710302L, 710301L, 710100L, "QA_MERCHANT_01", "QA_TERM_01", "wxQaSubApp01");
    PreparedPayment historical = new PreparedPayment(Long.toString(paymentId),
        Long.toString(paymentNo), booking.orderId(), new BigDecimal("128.00"),
        booking.paymentExpireAt(), false);
    var signed = f.notice(historical, "SUCCESS", "QA_LATE_REFUND_TRADE",
        historical.amount(), CHANNEL_PAID);
    assertTrue(f.notification.receive(signed.headers(), signed.body()).paid());
    try (var dispatcher = f.dispatcher(f.result)) {
      assertEquals(com.petplatform.event.core.OutboxDispatcher.Outcome.COMPLETED,
          dispatcher.dispatchOne());
    }
    DispatchedEvent late = f.db.jdbc.queryForObject(
        "SELECT event_id,event_type,event_version,occurred_at,aggregate_type,aggregate_id,trace_id,payload "
            + "FROM integration_event_outbox WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'",
        (rs, row) -> new DispatchedEvent(rs.getString("event_id"), rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
            rs.getString("trace_id"), rs.getString("payload")));
    assertNotNull(late);
    return new LatePayment(booking.orderId(), Long.toString(paymentId),
        Long.toString(paymentNo), reservation, late);
  }

  private record LatePayment(String orderId, String paymentId, String paymentNo,
      long reservationId, DispatchedEvent event) {}
}
