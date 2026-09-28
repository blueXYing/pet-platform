package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.ApiException;
import com.petplatform.common.QueryContext;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.api.IntegrationEventConsumer;
import com.petplatform.event.core.OutboxDispatcher;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderCommand;
import com.petplatform.order.api.dto.OrderExpiryTypes.ExpireOrderResult;
import com.petplatform.order.biz.apiimpl.OrderLatePaymentFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderLateRefundProjectionConsumer;
import com.petplatform.order.biz.apiimpl.OrderExpiryFactsApiImpl;
import com.petplatform.payment.api.dto.PaymentPreparationTypes.PreparedPayment;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.payment.biz.apiimpl.PaymentRefundResultFactsApiImpl;
import com.petplatform.payment.biz.application.PaymentRefundChannel;
import com.petplatform.payment.biz.application.PaymentRefundService;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundSubmitCommand;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundQuery;
import com.petplatform.payment.api.dto.PaymentRefundTypes.CoordinationState;
import com.petplatform.payment.api.dto.PaymentRefundTypes.ChannelRefundProgress;
import com.petplatform.payment.api.command.PaymentRefundApi;
import com.petplatform.refund.biz.application.LateRefundService;
import com.petplatform.refund.biz.application.RefundExecutionService;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.ReservationExpiryApiImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import javax.sql.DataSource;

/** Real isolated MySQL acceptance. Payment proof comes from a signed offline channel notice. */
class LateRefundAcceptanceTest {
  private static final AtomicLong IDS = new AtomicLong(8_910_000_000_000_000L);
  private static final BigDecimal CHANNEL_PAID = new BigDecimal("97.35");

  @Test
  void committedLateEventCreatesOneBoundRefundWithTaskAndSurvivesLostDispatchAck()
      throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var consumer = lateRefund(f, f.publisher);
      AtomicInteger invocations = new AtomicInteger();
      IntegrationEventConsumer lostAck = new IntegrationEventConsumer() {
        @Override public String consumerName() { return consumer.consumerName(); }
        @Override public java.util.Set<String> eventTypes() { return consumer.eventTypes(); }
        @Override public void consume(DispatchedEvent event) {
          consumer.consume(event);
          invocations.incrementAndGet();
          throw new IllegalStateException("QA: dispatch ACK lost after refund commit");
        }
      };
      try (var dispatcher = f.dispatcher(lostAck)) {
        assertEquals(OutboxDispatcher.Outcome.FAILED, dispatcher.dispatchOne());
      }
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundOrderCreatedEvent.v1'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task "
          + "WHERE owner_module='REFUND' AND task_type='REFUND_SUBMIT'"));
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      assertEquals("REFUND_SUBMIT:" + refundId + ":0",
          f.text("SELECT task_key FROM async_task WHERE owner_module='REFUND'"));
      assertEquals("FULL", f.text("SELECT refund_type FROM refund_order"));
      assertEquals("LATE_PAYMENT_TIMEOUT", f.text("SELECT source_type FROM refund_order"));
      assertEquals("CREATED", f.text("SELECT status FROM refund_order"));
      assertEquals(CHANNEL_PAID, f.db.jdbc.queryForObject(
          "SELECT refund_amount FROM refund_order", BigDecimal.class));
      assertEquals(CHANNEL_PAID, f.db.jdbc.queryForObject(
          "SELECT channel_paid_amount FROM refund_execution", BigDecimal.class));
      assertEquals("EVENT:LATE_PAYMENT_AUTO_REFUND:" + late.paymentId() + ":" + late.orderId(),
          f.text("SELECT request_id FROM refund_execution"));
      assertEquals(late.event().eventId(),
          f.text("SELECT CAST(late_event_id AS CHAR) FROM refund_execution"));
      assertEquals(late.paymentNo(),
          f.text("SELECT CAST(payment_no AS CHAR) FROM refund_execution"));

      f.db.jdbc.update("UPDATE integration_event_outbox SET next_retry_at=UTC_TIMESTAMP(3) "
          + "WHERE event_type='LatePaymentSucceededAfterTimeoutEvent.v1'");
      try (var dispatcher = f.dispatcher(lostAck)) {
        assertEquals(OutboxDispatcher.Outcome.COMPLETED, dispatcher.dispatchOne());
      }
      assertEquals(1, invocations.get(), "consume log bypasses the handler after lost ACK");
      consumer.consume(late.event());
      assertEquals(refundId, f.text("SELECT CAST(id AS CHAR) FROM refund_order"));
      assertEquals(refundNo, f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(late.orderId())));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='OrderPaidEvent.v1'"));
    }
  }

  @Test
  void outboxFailureRollsBackRefundBindingConsumeLogAndTask() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var wrongPublisher = new TransactionalOutboxPublisher(
          new DelegatingDataSource(f.db.source), LateRefundAcceptanceTest::id,
          new com.fasterxml.jackson.databind.ObjectMapper());
      var consumer = lateRefund(f, wrongPublisher);
      assertThrows(RuntimeException.class, () -> consumer.consume(late.event()));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundOrderCreatedEvent.v1'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
      lateRefund(f, f.publisher).consume(late.event());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
    }
  }

  @Test
  void taskInsertFailureRollsBackRefundAndConsumeClaim() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var consumer = lateRefund(f, f.publisher);
      f.db.jdbc.execute("CREATE TRIGGER qa_refund_task_fail BEFORE INSERT ON async_task "
          + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA task insert failure'");
      try {
        assertThrows(ApiException.class, () -> consumer.consume(late.event()));
      } finally {
        f.db.jdbc.execute("DROP TRIGGER qa_refund_task_fail");
      }
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundOrderCreatedEvent.v1'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
      consumer.consume(late.event());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
    }
  }

  @Test
  void eventPayloadAloneCannotAuthorizeWrongAmountOrMissingProof() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var consumer = lateRefund(f, f.publisher);
      var original = late.event();
      assertTrue(original.payloadJson().contains("97.35"));
      for (DispatchedEvent invalid : java.util.List.of(
          eventWithPayload(original, "{}"),
          eventWithPayload(original, original.payloadJson().replace("97.35", "98.00")),
          new DispatchedEvent(original.eventId(), original.eventType(),
              original.eventVersion(), original.occurredAt(), original.aggregateType(),
              original.aggregateId() + 1, original.traceId(), original.payloadJson()))) {
        ApiException failure = assertThrows(ApiException.class, () -> consumer.consume(invalid));
        assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", failure.code());
      }
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      consumer.consume(original);
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
    }
  }

  @Test
  void originalPaymentReversalBeforeRefundConsumptionFreezesAutoRefund() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      LocalDateTime expire = f.db.jdbc.queryForObject(
          "SELECT expire_at FROM payment_order WHERE id=?", LocalDateTime.class,
          Long.parseLong(late.paymentId()));
      assertNotNull(expire);
      PreparedPayment historical = new PreparedPayment(late.paymentId(), late.paymentNo(),
          late.orderId(), new BigDecimal("128.00"), expire.atOffset(ZoneOffset.UTC), false);
      var reversed = f.notice(historical, "REFUND", "QA_LATE_REFUND_TRADE",
          historical.amount(), CHANNEL_PAID);
      assertTrue(f.notification.receive(reversed.headers(), reversed.body()).paid());
      assertEquals("RECONCILIATION_REQUIRED", f.text(
          "SELECT dispatch_state FROM payment_order WHERE id=?",
          Long.parseLong(late.paymentId())));
      var consumer = lateRefund(f, f.publisher);
      ApiException failure = assertThrows(ApiException.class,
          () -> consumer.consume(late.event()));
      assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", failure.code());
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
    }
  }

  @Test
  void concurrentConsumersConvergeOnOneOriginalRefundNumber() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var first = lateRefund(f, f.publisher);
      var second = lateRefund(f, f.publisher);
      CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
      try (var threads = Executors.newFixedThreadPool(2)) {
        var a = threads.submit(() -> consumeConcurrently(first, late.event(), ready, start));
        var b = threads.submit(() -> consumeConcurrently(second, late.event(), ready, start));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int successes = (a.get(10, TimeUnit.SECONDS) ? 1 : 0)
            + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
        assertTrue(successes >= 1, "at least one consumer committed the refund");
      }
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundOrderCreatedEvent.v1'"));
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      first.consume(late.event());
      assertEquals(refundNo, f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order"));
    }
  }

  @Test
  void sentButTimedOutRefundQueriesOriginalNumberAfterThirtySecondsAndStoresVerifiedSuccess()
      throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      AtomicInteger submitCalls = new AtomicInteger(), queryCalls = new AtomicInteger();
      PaymentRefundChannel channel = new PaymentRefundChannel() {
        @Override public VerifiedResult submit(RefundRequest request) {
          submitCalls.incrementAndGet();
          assertEquals(refundNo, request.refundNo());
          assertEquals(late.paymentNo(), request.paymentNo());
          assertEquals(CHANNEL_PAID, request.amount());
          assertEquals("MAY_HAVE_SENT", f.text(
              "SELECT state FROM payment_refund_dispatch WHERE refund_order_id=?",
              Long.parseLong(refundId)), "send boundary must commit before network I/O");
          throw new IllegalStateException("offline QA: channel ACK lost after possible send");
        }
        @Override public VerifiedResult query(RefundRequest request) {
          queryCalls.incrementAndGet();
          assertEquals(refundNo, request.refundNo());
          assertEquals("QA_LATE_REFUND_TRADE", request.originalChannelTradeNo());
          return new VerifiedResult("SUCCESS", refundNo, "QA_CHANNEL_REFUND_01",
              9735, 9735L, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                  .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS), "a".repeat(64));
        }
      };
      PaymentRefundService payment = paymentRefund(f, business, channel);
      var submit = new ChannelRefundSubmitCommand(new CommandContext(
          "TASK:REFUND_SUBMIT:" + refundId + ":0", "refund-qa-submit", OperatorType.SYSTEM,
          null, "ASYNC_TASK"), refundId, refundNo, late.paymentId(), "710302", 0);
      var query = new ChannelRefundQuery(new CommandContext(
          "TASK:REFUND_CHANNEL_QUERY:" + refundId + ":0", "refund-qa-query",
          OperatorType.SYSTEM, null, "ASYNC_TASK"), refundId, refundNo,
          late.paymentId(), "710302", 0);
      var pending = payment.submitRefund(submit);
      assertEquals(CoordinationState.QUERY_PENDING, pending.state());
      assertEquals(1, submitCalls.get());
      assertEquals(0, queryCalls.get());
      assertEquals("QUERY_PENDING", f.text("SELECT state FROM payment_refund_dispatch"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_refund_receipt"));
      assertTrue(f.db.jdbc.queryForObject(
          "SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(3),query_not_before) "
              + "FROM payment_refund_dispatch", Integer.class) >= 29);
      assertEquals(CoordinationState.QUERY_PENDING, payment.queryRefund(query).state());
      assertEquals(CoordinationState.QUERY_PENDING, payment.submitRefund(submit).state());
      assertEquals(1, submitCalls.get(), "a possibly sent refund is never submitted again");
      assertEquals(0, queryCalls.get(), "first query is delayed at least 30 seconds");
      f.db.jdbc.update("UPDATE payment_refund_dispatch "
          + "SET query_not_before=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
      assertEquals(CoordinationState.VERIFIED_SUCCESS, payment.queryRefund(query).state());
      assertEquals(1, queryCalls.get());
      assertEquals(1, submitCalls.get());
      assertEquals("VERIFIED_SUCCESS", f.text("SELECT state FROM payment_refund_dispatch"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_refund_receipt "
          + "WHERE receipt_source='QUERY' AND channel_status='SUCCESS'"));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(f.db.source));
      tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      tx.executeWithoutResult(status -> {
        var context = new QueryContext("refund-qa-facts", OperatorType.SYSTEM, null);
        f.guard.acquire(java.util.List.of("710302"), context);
        var verified = new PaymentRefundResultFactsApiImpl(f.db.source, f.guard)
            .requireVerified(refundId, refundNo, late.paymentId(), "710302", context);
        assertEquals(CHANNEL_PAID, verified.refundAmount());
        assertEquals("QA_CHANNEL_REFUND_01", verified.channelRefundNo());
      });
      assertEquals(CoordinationState.VERIFIED_SUCCESS, payment.submitRefund(submit).state());
      assertEquals(1, submitCalls.get());
      assertEquals(1, queryCalls.get());
    }
  }

  private static PaymentRefundService paymentRefund(PaymentFoundationAcceptanceTest.Fixture f,
      LateRefundService business, PaymentRefundChannel channel) {
    return new PaymentRefundService(f.db.source, LateRefundAcceptanceTest::id, f.guard,
        new OrderLatePaymentFactsApiImpl(f.db.source, f.guard, f.reservationExpiry),
        new PaymentSuccessFactsApiImpl(f.db.source, f.guard), business, channel,
        new PaymentRefundService.Settings("127.0.0.1", "https://qa.invalid/refund",
            ZoneId.of("Asia/Shanghai")), Clock.systemUTC());
  }

  @Test
  void signedChannelSuccessWithWrongActualAmountRequiresReconciliation() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      AtomicInteger calls = new AtomicInteger();
      PaymentRefundChannel channel = new PaymentRefundChannel() {
        @Override public VerifiedResult submit(RefundRequest request) {
          calls.incrementAndGet();
          return new VerifiedResult("SUCCESS", request.refundNo(), "QA_WRONG_AMOUNT_REFUND",
              9735, 9734L, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                  .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS), "b".repeat(64));
        }
        @Override public VerifiedResult query(RefundRequest request) {
          throw new AssertionError("wrong amount must not schedule a success query");
        }
      };
      var payment = paymentRefund(f, business, channel);
      var submit = new ChannelRefundSubmitCommand(new CommandContext(
          "TASK:REFUND_SUBMIT:" + refundId + ":0", "refund-qa-mismatch",
          OperatorType.SYSTEM, null, "ASYNC_TASK"), refundId, refundNo,
          late.paymentId(), "710302", 0);
      assertEquals(CoordinationState.RECONCILIATION_REQUIRED,
          payment.submitRefund(submit).state());
      assertEquals(1, calls.get());
      assertEquals("RECONCILIATION_REQUIRED",
          f.text("SELECT state FROM payment_refund_dispatch"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM payment_refund_receipt "
          + "WHERE channel_status='SUCCESS'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(f.db.source));
      tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      tx.executeWithoutResult(status -> {
        var context = new QueryContext("refund-qa-mismatch-facts", OperatorType.SYSTEM, null);
        f.guard.acquire(java.util.List.of("710302"), context);
        ApiException unavailable = assertThrows(ApiException.class, () ->
            new PaymentRefundResultFactsApiImpl(f.db.source, f.guard)
                .requireVerified(refundId, refundNo, late.paymentId(), "710302", context));
        assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", unavailable.code());
      });
    }
  }

  @Test
  void createdRefundProjectsToClosedOrderOnlyOnce() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      DispatchedEvent created = event(f, "RefundOrderCreatedEvent.v1");
      var projection = new OrderLateRefundProjectionConsumer(f.db.source,
          LateRefundAcceptanceTest::id, f.guard, f.reservationExpiry, business);
      projection.consume(created);
      projection.consume(created);
      assertEquals(1L, f.count("SELECT COUNT(*) FROM order_late_refund_result"));
      assertEquals("CREATED", f.text("SELECT refund_status FROM order_late_refund_result"));
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(late.orderId())));
      assertEquals("PAYMENT_TIMEOUT", f.text("SELECT cancel_reason FROM pet_order WHERE id=?",
          Long.parseLong(late.orderId())));
      assertEquals("EXPIRED", f.text("SELECT status FROM schedule_reservation WHERE id=?",
          late.reservationId()));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='OrderPaidEvent.v1'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='ORDER_LATE_REFUND'"));
    }
  }

  @Test
  void corruptedRefundOrderAndExecutionBindingFailsClosedOnReplay() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      f.db.jdbc.update("UPDATE refund_order SET refund_no=refund_no+1");
      ApiException replay = assertThrows(ApiException.class,
          () -> business.consume(late.event()));
      assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", replay.code());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_execution"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_consume_log "
          + "WHERE consumer_name='REFUND_LATE_PAYMENT'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task WHERE owner_module='REFUND'"));
    }
  }

  @Test
  void verifiedRefundCompletesOnceAndProjectsSuccessBeforeCreatedWithoutReopeningOrder()
      throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      AtomicInteger sends = new AtomicInteger();
      PaymentRefundChannel channel = new PaymentRefundChannel() {
        @Override public VerifiedResult submit(RefundRequest request) {
          sends.incrementAndGet();
          assertEquals(refundNo, request.refundNo());
          return new VerifiedResult("SUCCESS", refundNo, "QA_REFUND_FINAL_01", 9735,
              9735L, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1)
                  .truncatedTo(ChronoUnit.SECONDS), "c".repeat(64));
        }
        @Override public VerifiedResult query(RefundRequest request) {
          throw new AssertionError("verified submit SUCCESS needs no channel query");
        }
      };
      var payment = paymentRefund(f, business, channel);
      var executor = new RefundExecutionService(business, payment,
          new PaymentRefundResultFactsApiImpl(f.db.source, f.guard));
      assertTrue(executor.execute(refundId, "710302", false, "refund-qa-execute").done());
      assertEquals(1, sends.get());
      assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
      assertEquals("QA_REFUND_FINAL_01", f.text("SELECT channel_refund_no FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_transaction "
          + "WHERE action='REFUND' AND channel_status='SUCCESS'"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));

      var projection = new OrderLateRefundProjectionConsumer(f.db.source,
          LateRefundAcceptanceTest::id, f.guard, f.reservationExpiry, business);
      DispatchedEvent succeeded = event(f, "RefundSucceededEvent.v1");
      DispatchedEvent created = event(f, "RefundOrderCreatedEvent.v1");
      projection.consume(succeeded);
      projection.consume(created);
      projection.consume(succeeded);
      assertEquals("SUCCESS", f.text("SELECT refund_status FROM order_late_refund_result"));
      assertEquals(CHANNEL_PAID, f.db.jdbc.queryForObject(
          "SELECT refunded_amount FROM pet_order WHERE id=?", BigDecimal.class,
          Long.parseLong(late.orderId())));
      assertEquals(refundId, f.text("SELECT CAST(refund_order_id AS CHAR) "
          + "FROM pet_order WHERE id=?", Long.parseLong(late.orderId())));
      assertEquals("CANCELED", f.text("SELECT order_stage FROM pet_order WHERE id=?",
          Long.parseLong(late.orderId())));
      assertEquals("PAYMENT_TIMEOUT", f.text("SELECT cancel_reason FROM pet_order WHERE id=?",
          Long.parseLong(late.orderId())));
      assertEquals("EXPIRED", f.text("SELECT status FROM schedule_reservation WHERE id=?",
          late.reservationId()));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='OrderPaidEvent.v1'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM async_task "
          + "WHERE task_type LIKE '%AUTO_ACCEPT%'"));

      assertTrue(executor.execute(refundId, "710302", false, "refund-qa-replay").done());
      assertEquals(1, sends.get());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
    }
  }

  @Test
  void deadAndCanceledSubmissionTasksCreateDurableReconciliationIssue() throws Exception {
    for (String finalTaskState : java.util.List.of("DEAD", "CANCELED")) {
      try (var f = fixture()) {
        LatePayment late = latePayment(f);
        var business = lateRefund(f, f.publisher);
        business.consume(late.event());
        String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
        f.db.jdbc.update("UPDATE async_task SET status=?,updated_at=UTC_TIMESTAMP(3) "
            + "WHERE task_key=?", finalTaskState, "REFUND_SUBMIT:" + refundId + ":0");
        PaymentRefundChannel channel = new PaymentRefundChannel() {
          @Override public VerifiedResult submit(RefundRequest request) {
            throw new AssertionError("reconciliation scan must not submit to channel");
          }
          @Override public VerifiedResult query(RefundRequest request) {
            throw new AssertionError("reconciliation scan must not query channel");
          }
        };
        var executor = new RefundExecutionService(business,
            paymentRefund(f, business, channel),
            new PaymentRefundResultFactsApiImpl(f.db.source, f.guard));
        assertEquals(1, executor.reconcileDeadTasks(), finalTaskState);
        assertEquals("OPEN", f.text("SELECT status FROM refund_reconciliation_issue "
            + "WHERE refund_order_id=?", Long.parseLong(refundId)));
        assertEquals("LATE_PAYMENT_AUTO_REFUND_FAILED",
            f.text("SELECT issue_code FROM refund_reconciliation_issue "
                + "WHERE refund_order_id=?", Long.parseLong(refundId)));
        assertEquals("UNKNOWN", f.text("SELECT status FROM refund_order WHERE id=?",
            Long.parseLong(refundId)));
        assertEquals(0L, f.count("SELECT COUNT(*) FROM payment_refund_dispatch"));
        assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
            + "WHERE event_type='RefundSucceededEvent.v1'"));
      }
    }
  }

  @Test
  void unknownSubmitAtomicallySchedulesOneOriginalNumberQueryTask() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      AtomicInteger submits = new AtomicInteger();
      PaymentRefundChannel channel = new PaymentRefundChannel() {
        @Override public VerifiedResult submit(RefundRequest request) {
          submits.incrementAndGet();
          throw new IllegalStateException("offline QA: submit ACK unknown");
        }
        @Override public VerifiedResult query(RefundRequest request) {
          throw new AssertionError("query should wait for stored 30 second boundary");
        }
      };
      var executor = new RefundExecutionService(business,
          paymentRefund(f, business, channel),
          new PaymentRefundResultFactsApiImpl(f.db.source, f.guard));
      var first = executor.execute(refundId, "710302", false, "refund-qa-unknown");
      assertTrue(first.done());
      assertNotNull(first.nextQueryAt());
      assertEquals(1, submits.get());
      assertEquals("UNKNOWN", f.text("SELECT status FROM refund_order"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task "
          + "WHERE task_key='REFUND_CHANNEL_QUERY:" + refundId + ":0'"));
      assertEquals(f.text("SELECT DATE_FORMAT(first_query_at,'%Y-%m-%d %H:%i:%s.%f') "
              + "FROM refund_execution"),
          f.text("SELECT DATE_FORMAT(execute_at,'%Y-%m-%d %H:%i:%s.%f') "
              + "FROM async_task WHERE task_key='REFUND_CHANNEL_QUERY:" + refundId + ":0'"));
      executor.execute(refundId, "710302", false, "refund-qa-unknown-replay");
      assertEquals(1, submits.get());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM async_task "
          + "WHERE task_key='REFUND_CHANNEL_QUERY:" + refundId + ":0'"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
    }
  }

  @Test
  void bareCoordinationSuccessHintCannotCompleteBusinessRefund() throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      var business = lateRefund(f, f.publisher);
      business.consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      PaymentRefundApi forged = new PaymentRefundApi() {
        @Override public ChannelRefundProgress submitRefund(ChannelRefundSubmitCommand command) {
          return new ChannelRefundProgress(refundId, refundNo,
              CoordinationState.VERIFIED_SUCCESS, null);
        }
        @Override public ChannelRefundProgress queryRefund(ChannelRefundQuery query) {
          return new ChannelRefundProgress(refundId, refundNo,
              CoordinationState.VERIFIED_SUCCESS, null);
        }
      };
      var executor = new RefundExecutionService(business, forged,
          new PaymentRefundResultFactsApiImpl(f.db.source, f.guard));
      ApiException failure = assertThrows(ApiException.class,
          () -> executor.execute(refundId, "710302", false, "refund-qa-forged-hint"));
      assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", failure.code());
      assertEquals("CREATED", f.text("SELECT status FROM refund_order"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM refund_transaction"));
      assertEquals(0L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
    }
  }

  @Test
  void committedBusinessSuccessWithLostDatabaseAckRecoversWithoutSecondChannelSubmit()
      throws Exception {
    try (var f = fixture()) {
      LatePayment late = latePayment(f);
      lateRefund(f, f.publisher).consume(late.event());
      String refundId = f.text("SELECT CAST(id AS CHAR) FROM refund_order");
      String refundNo = f.text("SELECT CAST(refund_no AS CHAR) FROM refund_order");
      var source = new LostSuccessCommitSource(f.db.source, refundId);
      var guard = new ScheduleCapacityGuardApiImpl(source);
      var scheduleFacts = new ScheduleProtectionFactsApiImpl(source, guard);
      var orderExpiry = new OrderExpiryFactsApiImpl(source, guard);
      var reservationExpiry = new ReservationExpiryApiImpl(source,
          LateRefundAcceptanceTest::id, guard, orderExpiry, scheduleFacts);
      var orderFacts = new OrderLatePaymentFactsApiImpl(source, guard, reservationExpiry);
      var paymentFacts = new PaymentSuccessFactsApiImpl(source, guard);
      var publisher = new TransactionalOutboxPublisher(source,
          LateRefundAcceptanceTest::id, new com.fasterxml.jackson.databind.ObjectMapper());
      var business = new LateRefundService(source, LateRefundAcceptanceTest::id, guard,
          orderFacts, paymentFacts, publisher);
      AtomicInteger sends = new AtomicInteger();
      PaymentRefundChannel channel = new PaymentRefundChannel() {
        @Override public VerifiedResult submit(RefundRequest request) {
          sends.incrementAndGet();
          return new VerifiedResult("SUCCESS", refundNo, "QA_REFUND_ACK_LOST", 9735,
              9735L, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                  .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS), "d".repeat(64));
        }
        @Override public VerifiedResult query(RefundRequest request) {
          throw new AssertionError("committed success must not query or resubmit");
        }
      };
      var payment = new PaymentRefundService(source, LateRefundAcceptanceTest::id,
          guard, orderFacts, paymentFacts, business, channel,
          new PaymentRefundService.Settings("127.0.0.1", "https://qa.invalid/refund",
              ZoneId.of("Asia/Shanghai")), Clock.systemUTC());
      var executor = new RefundExecutionService(business, payment,
          new PaymentRefundResultFactsApiImpl(source, guard));
      source.arm();
      assertThrows(RuntimeException.class,
          () -> executor.execute(refundId, "710302", false, "refund-qa-lost-commit-ack"));
      assertTrue(source.lost.get(), "success commit occurred before its client ACK was lost");
      assertEquals("SUCCESS", f.text("SELECT status FROM refund_order"));
      assertEquals(1, sends.get());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
      assertTrue(executor.execute(refundId, "710302", false, "refund-qa-recover").done());
      assertEquals(1, sends.get());
      assertEquals(1L, f.count("SELECT COUNT(*) FROM refund_transaction"));
      assertEquals(1L, f.count("SELECT COUNT(*) FROM integration_event_outbox "
          + "WHERE event_type='RefundSucceededEvent.v1'"));
    }
  }

  /** Simulates a successful MySQL commit whose response is lost only for business SUCCESS. */
  private static final class LostSuccessCommitSource extends DelegatingDataSource {
    private final JdbcTemplate oracle;
    private final long refundId;
    private volatile boolean armed;
    final AtomicBoolean lost = new AtomicBoolean();

    LostSuccessCommitSource(DataSource target, String refundId) {
      super(target);
      oracle = new JdbcTemplate(target);
      this.refundId = Long.parseLong(refundId);
    }

    void arm() { armed = true; }

    @Override public Connection getConnection() throws SQLException {
      return wrap(super.getConnection());
    }

    @Override public Connection getConnection(String username, String password)
        throws SQLException {
      return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection delegate) {
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            try {
              Object result = method.invoke(delegate, args);
              if (armed && "commit".equals(method.getName()) && !lost.get()
                  && "SUCCESS".equals(oracle.queryForObject(
                      "SELECT status FROM refund_order WHERE id=?", String.class, refundId))
                  && lost.compareAndSet(false, true)) {
                throw new SQLException("QA: committed refund SUCCESS ACK lost", "08006");
              }
              return result;
            } catch (InvocationTargetException wrapped) {
              throw wrapped.getCause();
            }
          });
    }
  }

  private static DispatchedEvent event(PaymentFoundationAcceptanceTest.Fixture f, String type) {
    return f.db.jdbc.queryForObject(
        "SELECT event_id,event_type,event_version,occurred_at,aggregate_type,aggregate_id,trace_id,payload "
            + "FROM integration_event_outbox WHERE event_type=?",
        (rs, row) -> new DispatchedEvent(rs.getString("event_id"), rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
            rs.getString("trace_id"), rs.getString("payload")), type);
  }

  private static boolean consumeConcurrently(LateRefundService consumer, DispatchedEvent event,
      CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      assertTrue(start.await(5, TimeUnit.SECONDS));
      consumer.consume(event);
      return true;
    } catch (ApiException collision) {
      assertEquals("COMMON_DEPENDENCY_UNAVAILABLE", collision.code());
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static DispatchedEvent eventWithPayload(DispatchedEvent original, String payload) {
    return new DispatchedEvent(original.eventId(), original.eventType(),
        original.eventVersion(), original.occurredAt(), original.aggregateType(),
        original.aggregateId(), original.traceId(), payload);
  }

  private static LateRefundService lateRefund(PaymentFoundationAcceptanceTest.Fixture f,
      com.petplatform.event.api.IntegrationEventPublisher publisher) {
    return new LateRefundService(f.db.source, LateRefundAcceptanceTest::id, f.guard,
        new OrderLatePaymentFactsApiImpl(f.db.source, f.guard, f.reservationExpiry),
        new PaymentSuccessFactsApiImpl(f.db.source, f.guard), publisher);
  }

  private static long id() { return IDS.incrementAndGet(); }

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
        710302L, 710301L, 710100L, "QA_MERCHANT_01", "QA_TERM1", "wxQaSubApp01");
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
    DispatchedEvent late = event(f, "LatePaymentSucceededAfterTimeoutEvent.v1");
    assertNotNull(late);
    return new LatePayment(booking.orderId(), Long.toString(paymentId),
        Long.toString(paymentNo), reservation, late);
  }

  private record LatePayment(String orderId, String paymentId, String paymentNo,
      long reservationId, DispatchedEvent event) {}
}
