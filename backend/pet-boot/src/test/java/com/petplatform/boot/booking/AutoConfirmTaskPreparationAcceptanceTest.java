package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.*;
import com.petplatform.event.api.DispatchedEvent;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi.Finding;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.order.biz.application.OrderAutoConfirmTaskSpec;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.task.core.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated MySQL B-only acceptance; no channel network, confirmation or mutating repair. */
class AutoConfirmTaskPreparationAcceptanceTest {
    private static final AtomicLong IDS = new AtomicLong(8_950_000_000_000_000L);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final QueryContext SYSTEM = new QueryContext("auto-task-qa", OperatorType.SYSTEM, null);

    @Test void normalPaymentCreatesUniqueTaskAtomicallyAndConcurrentReplaysDoNotExtendDeadline() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f);
            String order = order(event);
            f.result.consume(event);
            var before = snapshot(f, order);
            assertNotNull(before);
            var deadline = deadline(f, order);
            assertTrue(OrderAutoConfirmTaskSpec.matches(before, order, deadline));
            assertEquals(deadline, before.executeAt());
            assertEquals("READY", before.status());
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var left = pool.submit(() -> { await(start); f.result.consume(event); });
                var right = pool.submit(() -> { await(start); f.result.consume(event); });
                start.countDown();
                left.get(10, TimeUnit.SECONDS); right.get(10, TimeUnit.SECONDS);
            }
            assertEquals(before, snapshot(f, order));
            assertEquals(1, f.count("SELECT COUNT(*) FROM async_task WHERE task_type='ORDER_AUTO_CONFIRM'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_PAYMENT_SUCCEEDED'"));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
            assertPending(f, order);
        }
    }

    @Test void taskInsertFailureRollsBackOrderReservationResultLogsOutboxAndConsumeClaim() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event);
            f.db.jdbc.execute("CREATE TRIGGER qa_auto_task_failure BEFORE INSERT ON async_task FOR EACH ROW "
                    + "BEGIN IF NEW.task_type='ORDER_AUTO_CONFIRM' THEN SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT='qa fail auto task'; END IF; END");
            assertThrows(ApiException.class, () -> f.result.consume(event));
            assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?", Long.parseLong(order)));
            assertEquals("TEMP_LOCKED", f.text("SELECT status FROM schedule_reservation WHERE order_id=?", Long.parseLong(order)));
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_payment_result"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_PAYMENT_SUCCEEDED'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type IN ('ORDER_PAID','PAYMENT_SUCCEEDED')"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
            assertEquals(0, f.count("SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='CONFIRM'"));
            assertNull(snapshot(f, order));
            f.db.jdbc.execute("DROP TRIGGER qa_auto_task_failure");
            f.result.consume(event);
            assertNotNull(snapshot(f, order));
            assertPending(f, order);
        }
    }

    @Test void conflictingImmutableTaskKeyNeverOverwritesOrCommitsPaymentProjection() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event);
            var wrongDeadline = OffsetDateTime.parse("2020-01-01T00:00:00Z");
            new TransactionTemplate(new DataSourceTransactionManager(f.db.source)).executeWithoutResult(tx ->
                    new JdbcAsyncTaskSubmitter(f.db.source, IDS::incrementAndGet).enqueueAt(
                            OrderAutoConfirmTaskSpec.key(order), "ORDER", OrderAutoConfirmTaskSpec.TYPE,
                            "ORDER", Long.parseLong(order), null, OrderAutoConfirmTaskSpec.payload(order, wrongDeadline),
                            8, OrderAutoConfirmTaskSpec.TYPE, wrongDeadline));
            var wrong = snapshot(f, order);
            assertThrows(ApiException.class, () -> f.result.consume(event));
            assertEquals(wrong, snapshot(f, order));
            assertEquals("PENDING_PAYMENT", f.text("SELECT order_stage FROM pet_order WHERE id=?", Long.parseLong(order)));
            assertEquals(0, f.count("SELECT COUNT(*) FROM order_payment_result"));
        }
    }

    @Test void realCommitAckLossRecoversTheOriginalTaskAndEvent() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event);
            var lost = new LostCommitSource(f.db.source);
            var consumer = consumer(lost, true);
            assertThrows(ApiException.class, () -> consumer.consume(event));
            assertTrue(lost.committed.get());
            var committed = snapshot(f, order);
            assertNotNull(committed);
            consumer.consume(event);
            assertEquals(committed, snapshot(f, order));
            assertEquals(1, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderPaidEvent.v1'"));
            assertPending(f, order);
        }
    }

    @Test void delayedConsumptionAndJvmZonesKeepOriginalChannelDeadlineEvenWhenOverdue() throws Exception {
        TimeZone saved = TimeZone.getDefault();
        try {
            for (String zone : List.of("Asia/Shanghai", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
                    String order = f.book().orderId();
                    var payment = f.prepare(order, UUID.randomUUID().toString());
                    var original = f.notice(payment, "SUCCESS", "QA_PAST_" + zone, payment.amount(), payment.amount());
                    var body = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(original.body());
                    // Two days back keeps the derived deadline (23:45+30m Shanghai) strictly in the
                    // past: with minusDays(1) the deadline lands at today 00:15 Shanghai, so runs
                    // between 00:00 and 00:15 Shanghai saw due()=false and the test flaked daily.
                    var channelLocal = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).atTime(23, 45);
                    body.put("trade_time", channelLocal.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
                    var signed = f.sign(JSON.writeValueAsBytes(body));
                    f.notification.receive(signed.headers(), signed.body());
                    f.result.consume(event(f));
                    var expected = channelLocal.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()
                            .withOffsetSameInstant(ZoneOffset.UTC).plusMinutes(30);
                    assertEquals(expected, snapshot(f, order).executeAt());
                    var item = inspector(f).inspect(SYSTEM, null, 10).items().getFirst();
                    assertTrue(item.due());
                    assertEquals(Finding.ACTIVE_TASK, item.finding());
                    assertPending(f, order);
                }
            }
        } finally { TimeZone.setDefault(saved); }
    }

    @Test void defaultOffAndPaymentReplayDoNotSilentlyBackfillHistoricalOrders() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC())) {
            var event = paidEvent(f); String order = order(event);
            f.result.consume(event);
            assertNull(snapshot(f, order));
            consumer(f.db.source, true).consume(event);
            var query = inspector(f);
            var first = query.inspect(SYSTEM, null, 10);
            assertEquals(Finding.MISSING_TASK, first.items().getFirst().finding());
            assertEquals(first, query.inspect(SYSTEM, null, 10));
            assertNull(snapshot(f, order), "dry run and replays cannot bypass missing A qualification");
            assertPending(f, order);
        }
    }

    @Test void inspectionReportsTerminalAndBindingAnomaliesWithoutResettingRetryOrLeaseState() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event); f.result.consume(event);
            var original = snapshot(f, order);
            var query = inspector(f);
            for (String state : List.of("READY", "RETRY_WAIT", "RUNNING", "DEAD", "CANCELED", "SUCCEEDED")) {
                f.db.jdbc.update("UPDATE async_task SET status=?,retry_count=3,execute_at=DATE_ADD(execute_at,INTERVAL 1 MINUTE),"
                        + "lease_owner='qa-preserved',lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) WHERE id=?",
                        state, Long.parseLong(original.taskId()));
                var before = f.db.jdbc.queryForMap("SELECT * FROM async_task WHERE id=?", Long.parseLong(original.taskId()));
                var finding = query.inspect(SYSTEM, null, 10).items().getFirst().finding();
                assertEquals(Set.of("READY", "RETRY_WAIT", "RUNNING").contains(state)
                        ? Finding.ACTIVE_TASK : Finding.TERMINAL_TASK_REQUIRES_REVIEW, finding);
                assertEquals(before, f.db.jdbc.queryForMap("SELECT * FROM async_task WHERE id=?", Long.parseLong(original.taskId())));
            }
            f.db.jdbc.update("UPDATE async_task SET owner_module='REFUND' WHERE id=?", Long.parseLong(original.taskId()));
            assertEquals(Finding.TASK_BINDING_CONFLICT, query.inspect(SYSTEM, null, 10).items().getFirst().finding());
            f.db.jdbc.update("UPDATE async_task SET owner_module='ORDER',task_key=LOWER(task_key) WHERE id=?", Long.parseLong(original.taskId()));
            assertEquals(Finding.TASK_BINDING_CONFLICT, query.inspect(SYSTEM, null, 10).items().getFirst().finding());
            assertPending(f, order);
        }
    }

    @Test void inspectionRejectsUnsupportedRoundAndBrokenOrderFactsRatherThanOfferingRepair() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event); f.result.consume(event);
            var query = inspector(f);
            f.db.jdbc.update("UPDATE pet_order SET reschedule_count=1 WHERE id=?", Long.parseLong(order));
            assertEquals(Finding.ORDER_FACTS_REQUIRE_REVIEW, query.inspect(SYSTEM, null, 10).items().getFirst().finding());
            f.db.jdbc.update("UPDATE pet_order SET reschedule_count=0,current_refund_application_id=888 WHERE id=?", Long.parseLong(order));
            assertEquals(Finding.ORDER_FACTS_REQUIRE_REVIEW, query.inspect(SYSTEM, null, 10).items().getFirst().finding());
            f.db.jdbc.update("UPDATE pet_order SET current_refund_application_id=NULL,confirm_deadline=DATE_ADD(confirm_deadline,INTERVAL 1 SECOND) WHERE id=?", Long.parseLong(order));
            assertEquals(Finding.ORDER_FACTS_REQUIRE_REVIEW, query.inspect(SYSTEM, null, 10).items().getFirst().finding());
            assertPending(f, order);
        }
    }

    @Test void inspectionIsSystemOnlyBoundedAndUsesStableOrderCursor() throws Exception {
        try (var f = new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(), true)) {
            var event = paidEvent(f); String order = order(event); f.result.consume(event);
            var query = inspector(f);
            assertEquals(CommonApiCodes.FORBIDDEN, assertThrows(ApiException.class, () -> query.inspect(
                    new QueryContext("user", OperatorType.USER, "710100"), null, 10)).code());
            for (int limit : List.of(0, 101)) assertEquals(CommonApiCodes.INVALID_ARGUMENT,
                    assertThrows(ApiException.class, () -> query.inspect(SYSTEM, null, limit)).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT, assertThrows(ApiException.class,
                    () -> query.inspect(SYSTEM, "01", 10)).code());
            // Synthetic anomalous rows exercise pagination only, not booking or confirmation acceptance.
            for (long id : List.of(9_010_000_000_000_001L, 9_010_000_000_000_002L)) {
                f.db.jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,"
                        + "reservation_id,order_stage,fulfillment_type,original_amount,pay_amount,appointment_start_at,"
                        + "appointment_end_at,created_at,updated_at) VALUES(?,?,710100,710301,710302,710401,710200,?,"
                        + "'PENDING_CONFIRM','IN_STORE',128,128,'2030-01-01 09:00:00','2030-01-01 10:30:00',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", id, id, id);
            }
            var page1 = query.inspect(SYSTEM, null, 1);
            assertEquals(order, page1.nextAfterOrderId());
            assertEquals(Map.of(Finding.ACTIVE_TASK, 1), page1.counts());
            var page2 = query.inspect(SYSTEM, page1.nextAfterOrderId(), 2);
            assertEquals(2, page2.items().size());
            assertNull(page2.nextAfterOrderId());
            assertEquals(Map.of(Finding.ORDER_FACTS_REQUIRE_REVIEW, 2), page2.counts());
            assertTrue(query.inspect(SYSTEM, page2.items().getLast().orderId(), 10).items().isEmpty());
        }
    }

    private static DispatchedEvent paidEvent(PaymentFoundationAcceptanceTest.Fixture f) throws Exception {
        String order = f.book().orderId();
        var payment = f.prepare(order, UUID.randomUUID().toString());
        var notice = f.notice(payment, "SUCCESS", "QA_AUTO_TASK", payment.amount(), payment.amount());
        f.notification.receive(notice.headers(), notice.body());
        return event(f);
    }

        static DispatchedEvent event(PaymentFoundationAcceptanceTest.Fixture f, String paymentId) {
        return f.db.jdbc.queryForObject("SELECT * FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1' AND aggregate_id=" + Long.parseLong(paymentId),
                (rs, index) -> new DispatchedEvent(rs.getString("event_id"), rs.getString("event_type"),
                        rs.getInt("event_version"), rs.getObject("occurred_at", LocalDateTime.class).atOffset(ZoneOffset.UTC),
                        rs.getString("aggregate_type"), rs.getLong("aggregate_id"), rs.getString("trace_id"), rs.getString("payload")));
    }

    static DispatchedEvent event(PaymentFoundationAcceptanceTest.Fixture f) {
        return f.db.jdbc.queryForObject("SELECT * FROM integration_event_outbox WHERE event_type='PaymentSucceededEvent.v1'",
                (rs, index) -> new DispatchedEvent(rs.getString("event_id"), rs.getString("event_type"),
                        rs.getInt("event_version"), rs.getObject("occurred_at", LocalDateTime.class).atOffset(ZoneOffset.UTC),
                        rs.getString("aggregate_type"), rs.getLong("aggregate_id"), rs.getString("trace_id"), rs.getString("payload")));
    }

    private static String order(DispatchedEvent event) throws Exception { return JSON.readTree(event.payloadJson()).path("orderId").textValue(); }
    private static OffsetDateTime deadline(PaymentFoundationAcceptanceTest.Fixture f, String order) {
        return f.db.jdbc.queryForObject("SELECT confirm_deadline FROM pet_order WHERE id=?", LocalDateTime.class,
                Long.parseLong(order)).atOffset(ZoneOffset.UTC);
    }
    private static TaskSubmissionSnapshot snapshot(PaymentFoundationAcceptanceTest.Fixture f, String order) {
        return new TaskSubmissionInspector(f.db.source).find(OrderAutoConfirmTaskSpec.key(order));
    }
    private static OrderAutoConfirmTaskInspectionApi inspector(PaymentFoundationAcceptanceTest.Fixture f) {
        return new OrderAutoConfirmTaskInspectionApiImpl(f.db.source);
    }
    private static void assertPending(PaymentFoundationAcceptanceTest.Fixture f, String order) {
        assertEquals("PENDING_CONFIRM", f.text("SELECT order_stage FROM pet_order WHERE id=?", Long.parseLong(order)));
        assertNull(f.text("SELECT confirmed_at FROM pet_order WHERE id=?", Long.parseLong(order)));
        assertEquals(0, f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
    }
    private static void await(CountDownLatch start) {
        try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private static OrderPaymentResultApiImpl consumer(DataSource source, boolean enabled) {
        var guard = new ScheduleCapacityGuardApiImpl(source);
        var facts = new ScheduleProtectionFactsApiImpl(source, guard);
        var expiry = new ReservationExpiryApiImpl(source, IDS::incrementAndGet, guard,
                new OrderExpiryFactsApiImpl(source, guard), facts);
        return new OrderPaymentResultApiImpl(source, IDS::incrementAndGet, guard,
                new PaymentSuccessFactsApiImpl(source, guard),
                new ReservationConfirmApiImpl(source, IDS::incrementAndGet, guard, facts,
                        new OrderPaymentFactsApiImpl(source, guard)), expiry,
                new TransactionalOutboxPublisher(source, IDS::incrementAndGet, JSON), enabled);
    }

    static final class LostCommitSource extends DelegatingDataSource {
        final AtomicBoolean committed = new AtomicBoolean();
        LostCommitSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return wrap(super.getConnection(user, password));
        }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("commit") && committed.compareAndSet(false, true))
                                throw new SQLException("QA commit ACK lost", "08006");
                            return result;
                        } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
        }
    }
}
