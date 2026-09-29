package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.petplatform.common.*;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.order.api.command.OrderAutoConfirmApi;
import com.petplatform.order.api.command.OrderAutoConfirmRepairApi;
import com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl;
import com.petplatform.order.biz.application.*;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.refund.api.query.RefundOrderFactsApi;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.task.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL, signed offline payment receipts; no live provider or production flags. */
class AutoConfirmExecutionAcceptanceTest {
    private static final AtomicLong IDS = new AtomicLong(8_960_000_000_000_000L);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void dueConfirmationIsAtomicAndConcurrentReplayEmitsOneProofAndEvent() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true);
            var service = service(f.db.source);
            var command = command(f,order);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> { start.await(); return service.autoConfirm(command); });
                var b = pool.submit(() -> { start.await(); return service.autoConfirm(command); });
                start.countDown();
                assertEquals(Set.of(OrderAutoConfirmApi.Result.CONFIRMED,OrderAutoConfirmApi.Result.ALREADY_CONFIRMED),
                        Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
            }
            assertConfirmed(f);
            String payload = f.text("SELECT payload FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'");
            var node = JSON.readTree(payload);
            assertTrue(node.path("orderId").isTextual()); assertEquals(order,node.path("orderId").textValue());
            assertEquals("AUTO",node.path("confirmMode").textValue());
            assertEquals(command.expectedConfirmDeadline(),OffsetDateTime.parse(node.path("confirmDeadline").textValue()));
            assertEquals(OrderAutoConfirmApi.Result.ALREADY_CONFIRMED,service.autoConfirm(command));
            assertConfirmed(f);
        }
    }

    @Test void earlyWorkerRetriesAtOriginalDeadlineEvenWhenTaskExecuteTimeIsCorrupted() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,false); var service = service(f.db.source);
            assertEquals(OrderAutoConfirmApi.Result.NOT_DUE,service.autoConfirm(command(f,order)));
            var before = snapshot(f,order);
            f.db.jdbc.update("UPDATE async_task SET execute_at=UTC_TIMESTAMP(3) WHERE task_key=?",before.taskKey());
            try (var worker = worker(f,service)) { assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne()); }
            var after = snapshot(f,order);
            assertEquals("RETRY_WAIT",after.status()); assertTrue(!after.executeAt().isBefore(before.submittedExecuteAt()));
            assertEquals(before.submittedExecuteAt(),after.submittedExecuteAt());
            assertEquals("PENDING_CONFIRM",f.text("SELECT order_stage FROM pet_order")); assertNoConfirmation(f);
        }
    }

    @Test void equivalentOffsetAndJvmZoneDoNotChangeDeadlineMeaning() throws Exception {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            try (var f = fixture(true)) {
                String order = paid(f,true);
                assertEquals(OrderAutoConfirmApi.Result.CONFIRMED,service(f.db.source).autoConfirm(
                        new OrderAutoConfirmApi.AutoConfirmOrderCommand(command(f,order).context(),order,0,
                                deadline(f).withOffsetSameInstant(ZoneOffset.ofHours(8)))));
                assertConfirmed(f);
            }
        } finally { TimeZone.setDefault(saved); }
    }

    @Test void outboxFailureRollsBackConfirmationAndRetryCanCommit() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var service = service(f.db.source);
            f.db.jdbc.execute("CREATE TRIGGER qa_confirm_fail BEFORE INSERT ON integration_event_outbox FOR EACH ROW "
                    + "BEGIN IF NEW.event_type='OrderConfirmedEvent.v1' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='qa'; END IF; END");
            assertThrows(ApiException.class,() -> service.autoConfirm(command(f,order)));
            assertNoConfirmation(f); assertEquals("PENDING_CONFIRM",f.text("SELECT order_stage FROM pet_order"));
            assertTrue(f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'") > 0);
            f.db.jdbc.execute("DROP TRIGGER qa_confirm_fail");
            assertEquals(OrderAutoConfirmApi.Result.CONFIRMED,service.autoConfirm(command(f,order))); assertConfirmed(f);
        }
    }

    @Test void committedConfirmationWithLostAckReplaysOriginalProof() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true);
            var source = new AutoConfirmTaskPreparationAcceptanceTest.LostCommitSource(f.db.source);
            var service = service(source);
            assertThrows(ApiException.class,() -> service.autoConfirm(command(f,order)));
            assertTrue(source.committed.get()); assertConfirmed(f);
            assertEquals(OrderAutoConfirmApi.Result.ALREADY_CONFIRMED,service.autoConfirm(command(f,order))); assertConfirmed(f);
        }
    }

    @Test void staleMerchantProgressCanceledAndRescheduledOrdersNeverConfirm() throws Exception {
        for (String change : List.of("order_stage='PENDING_SERVICE',confirm_mode='MANUAL',confirmed_at=UTC_TIMESTAMP(3)",
                "order_stage='CANCELED',canceled_at=UTC_TIMESTAMP(3)", "reschedule_count=1")) {
            try (var f = fixture(true)) {
                String order = paid(f,true); var command = command(f,order);
                f.db.jdbc.update("UPDATE pet_order SET " + change);
                assertEquals(OrderAutoConfirmApi.Result.STALE,service(f.db.source).autoConfirm(command));
                assertEquals(0,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
            }
        }
    }

    @Test void sameRoundWrongDeadlineIsConflictAndRoundOneAndNonSystemAreRejected() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var c = command(f,order); var service = service(f.db.source);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,assertThrows(ApiException.class,() -> service.autoConfirm(
                    new OrderAutoConfirmApi.AutoConfirmOrderCommand(c.context(),order,0,c.expectedConfirmDeadline().plusSeconds(1)))).code());
            assertEquals(CommonApiCodes.INVALID_ARGUMENT,assertThrows(ApiException.class,() -> service.autoConfirm(
                    new OrderAutoConfirmApi.AutoConfirmOrderCommand(c.context(),order,1,c.expectedConfirmDeadline()))).code());
            var user = new CommandContext(c.context().requestId(),"qa",OperatorType.USER,"1","MINIAPP");
            assertEquals(CommonApiCodes.FORBIDDEN,assertThrows(ApiException.class,() -> service.autoConfirm(
                    new OrderAutoConfirmApi.AutoConfirmOrderCommand(user,order,0,c.expectedConfirmDeadline()))).code());
            assertNoConfirmation(f);
        }
    }

    @Test void currentPaymentReversalAndReservationDriftAreRetryable() throws Exception {
        for (String update : List.of("UPDATE payment_order SET status='RECONCILIATION_REQUIRED'",
                "UPDATE schedule_reservation SET status='EXPIRED'",
                "UPDATE order_payment_result SET channel_paid_amount=1",
                "UPDATE pet_order SET current_aftersale_id=99",
                "UPDATE pet_order SET current_refund_application_id=99")) {
            try (var f = fixture(true)) {
                String order = paid(f,true); var c = command(f,order); f.db.jdbc.update(update);
                assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,assertThrows(ApiException.class,
                        () -> service(f.db.source).autoConfirm(c)).code()); assertNoConfirmation(f);
                assertTrue(f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'") > 0);
            }
        }
    }

    @Test void allRefundSourcesAndFailedOrUnknownWithoutExecutionBindingBlock() throws Exception {
        for (String status : List.of("CREATED","FAILED","UNKNOWN")) {
            try (var f = fixture(true)) {
                String order = paid(f,true); insertRefund(f,order,status);
                assertEquals(OrderAutoConfirmApi.Result.BLOCKED_BY_REFUND,service(f.db.source).autoConfirm(command(f,order)));
                assertNoConfirmation(f); assertEquals(0,f.count("SELECT COUNT(*) FROM refund_execution"));
                assertEquals(1,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'"));
            }
        }
    }

    @Test void failedRefundQueryNeverBecomesAbsence() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true);
            var service = service(f.db.source,(o,s,c) -> { throw new IllegalStateException("offline"); });
            assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
                    assertThrows(ApiException.class,() -> service.autoConfirm(command(f,order))).code());
            assertNoConfirmation(f);
        }
    }

    @Test void refundCommitAndRollbackSerializeAgainstConfirmation() throws Exception {
        for (boolean rollback : List.of(false,true)) {
            try (var f = fixture(true); var pool = Executors.newFixedThreadPool(2)) {
                String order = paid(f,true); var c = command(f,order);
                String store = f.text("SELECT CAST(store_id AS CHAR) FROM pet_order");
                var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
                var writer = pool.submit(() -> transaction(f.db.source)
                        .executeWithoutResult(tx -> {
                            f.guard.acquire(List.of(store),new QueryContext("refund-qa",OperatorType.SYSTEM,null));
                            insertRefund(f,order,"CREATED"); locked.countDown();
                            try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                            if (rollback) tx.setRollbackOnly();
                        }));
                assertTrue(locked.await(5,TimeUnit.SECONDS));
                var confirmation = pool.submit(() -> service(f.db.source).autoConfirm(c));
                release.countDown(); writer.get(10,TimeUnit.SECONDS);
                assertEquals(rollback ? OrderAutoConfirmApi.Result.CONFIRMED : OrderAutoConfirmApi.Result.BLOCKED_BY_REFUND,
                        confirmation.get(10,TimeUnit.SECONDS));
                if (rollback) assertConfirmed(f); else assertNoConfirmation(f);
            }
        }
    }

    @Test void confirmationCommitAllowsSubsequentGuardedPreServiceRefundFixture() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); assertEquals(OrderAutoConfirmApi.Result.CONFIRMED,service(f.db.source).autoConfirm(command(f,order)));
            String store = f.text("SELECT CAST(store_id AS CHAR) FROM pet_order");
            transaction(f.db.source).executeWithoutResult(tx -> {
                f.guard.acquire(List.of(store),new QueryContext("refund-after",OperatorType.SYSTEM,null));
                insertRefund(f,order,"CREATED");
            });
            assertEquals(1,f.count("SELECT COUNT(*) FROM refund_order")); assertConfirmed(f);
            assertEquals(OrderAutoConfirmApi.Result.ALREADY_CONFIRMED,service(f.db.source).autoConfirm(command(f,order)));
        }
    }

    @Test void missingTaskRepairUsesOriginalDeadlineAndReplaysWithoutResettingActiveState() throws Exception {
        try (var f = fixture(false)) {
            String order = paid(f,true); var service = service(f.db.source);
            assertNull(snapshot(f,order));
            assertEquals(OrderAutoConfirmRepairApi.Result.CREATED,service.repairMissingTask(repairContext(order),order));
            var created = snapshot(f,order); assertEquals(deadline(f),created.submittedExecuteAt());
            for (String state : List.of("READY","RETRY_WAIT","RUNNING")) {
                f.db.jdbc.update("UPDATE async_task SET status=?,execute_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR),retry_count=3 WHERE task_key=?",
                        state,created.taskKey());
                var before = snapshot(f,order);
                assertEquals(OrderAutoConfirmRepairApi.Result.EXISTS,service.repairMissingTask(repairContext(order),order));
                assertEquals(before,snapshot(f,order));
            }
            assertNoConfirmation(f);
        }
    }

    @Test void terminalRecoveryPreservesTaskAndAttemptsAndConfirmsOnce() throws Exception {
        for (String state : List.of("DEAD","CANCELED","SUCCEEDED")) {
            try (var f = fixture(true)) {
                String order = paid(f,true); var service = service(f.db.source);
                f.db.jdbc.update("UPDATE async_task SET status=?,retry_count=8 WHERE task_type='ORDER_AUTO_CONFIRM'",state);
                var before = snapshot(f,order);
                long attempts = f.count("SELECT COUNT(*) FROM async_task_attempt");
                assertEquals(OrderAutoConfirmRepairApi.Result.RECOVERED,service.repairMissingTask(repairContext(order),order));
                assertEquals(before,snapshot(f,order)); assertEquals(attempts,f.count("SELECT COUNT(*) FROM async_task_attempt"));
                assertEquals(OrderAutoConfirmRepairApi.Result.STALE,service.repairMissingTask(repairContext(order),order));
                assertConfirmed(f);
            }
        }
    }

    @Test void futureTerminalIsDiagnosedWithoutPrematureConfirmationAndScanIsBounded() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,false); var service = service(f.db.source);
            f.db.jdbc.update("UPDATE async_task SET status='DEAD' WHERE task_type='ORDER_AUTO_CONFIRM'");
            var before = snapshot(f,order);
            assertEquals(Long.parseLong(order),service.reconcile(0,1,true));
            assertEquals(0,service.reconcile(Long.parseLong(order),1,true));
            assertEquals(OrderAutoConfirmRepairApi.Result.NOT_DUE,service.repairMissingTask(repairContext(order),order));
            assertEquals(before,snapshot(f,order)); assertNoConfirmation(f);
            assertEquals(1,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'"));
        }
    }

    @Test void repairConflictAndRefundBlockNeverOverwriteExistingTask() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var service = service(f.db.source);
            f.db.jdbc.update("UPDATE async_task SET owner_module='REFUND' WHERE task_type='ORDER_AUTO_CONFIRM'");
            var before = snapshot(f,order);
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,assertThrows(ApiException.class,
                    () -> service.repairMissingTask(repairContext(order),order)).code());
            assertEquals(before,snapshot(f,order)); assertNoConfirmation(f);
            insertRefund(f,order,"CREATED");
            assertEquals(OrderAutoConfirmRepairApi.Result.BLOCKED_BY_REFUND,service.repairMissingTask(repairContext(order),order));
            assertEquals(before,snapshot(f,order)); assertNoConfirmation(f);
        }
    }

    @Test void workerValidatesImmutableBindingAndLeavesDurableDiagnostic() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var service = service(f.db.source);
            f.db.jdbc.update("UPDATE async_task SET owner_module='REFUND' WHERE task_type='ORDER_AUTO_CONFIRM'");
            try (var worker = worker(f,service)) { assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne()); }
            assertEquals("RETRY_WAIT",snapshot(f,order).status()); assertNoConfirmation(f);
            assertEquals(1,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'"));
        }
    }

    @Test void workerExecutesOnlyItsTaskTypeAndCancelsRefundBlockedTask() throws Exception {
        for (boolean blocked : List.of(false,true)) {
            try (var f = fixture(true)) {
                String order = paid(f,true); if (blocked) insertRefund(f,order,"CREATED");
                try (var worker = worker(f,service(f.db.source))) {
                    assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne());
                    assertEquals(AsyncTaskWorker.Outcome.EMPTY,worker.runOne());
                }
                assertEquals(blocked ? "CANCELED" : "SUCCEEDED",snapshot(f,order).status());
                if (blocked) assertNoConfirmation(f); else assertConfirmed(f);
                assertEquals("READY",f.text("SELECT status FROM async_task WHERE task_type='RESERVATION_HOLD_EXPIRE'"));
            }
        }
    }

    @Test void workerCrashAfterBusinessCommitAndLeaseReclaimProduceOneConfirmation() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var service = service(f.db.source);
            var real = OrderAutoConfirmTaskRegistration.create(f.db.source,service);
            var crash = new TaskRegistration<OrderAutoConfirmTaskRegistration.Payload>(new TaskHandler<>() {
                @Override public String taskType() { return OrderAutoConfirmTaskSpec.TYPE; }
                @Override public TaskExecutionResult execute(TaskExecutionContext context,
                        OrderAutoConfirmTaskRegistration.Payload payload) {
                    assertInstanceOf(TaskExecutionResult.Success.class,real.handler().execute(context,payload));
                    throw new AssertionError("QA worker process lost before task acknowledgement");
                }
            },real.decode(),real.requestId());
            try (var first = AsyncTaskWorker.create(f.db.source,IDS::incrementAndGet,"crash-qa",Clock.systemUTC(),
                    TaskWorkerSettings.defaults(),new TaskRetryDelays(Map.of(OrderAutoConfirmTaskSpec.TYPE,
                            List.of(Duration.ofSeconds(30)))),List.of(crash))) {
                assertThrows(AssertionError.class,first::runOne);
            }
            assertConfirmed(f); assertEquals("RUNNING",snapshot(f,order).status());
            f.db.jdbc.update("UPDATE async_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) "
                    + "WHERE task_type='ORDER_AUTO_CONFIRM'");
            try (var recovered = worker(f,service)) {
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED,recovered.runOne());
            }
            assertConfirmed(f); assertEquals("SUCCEEDED",snapshot(f,order).status());
            assertEquals(2,f.count("SELECT COUNT(*) FROM async_task_attempt"));
            assertEquals(1,f.count("SELECT COUNT(*) FROM async_task_attempt WHERE error_code='LEASE_EXPIRED'"));
        }
    }

    @Test void missingOrCorruptedSuccessProofCannotBecomeASecondConfirmation() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true); var service = service(f.db.source); var command = command(f,order);
            assertEquals(OrderAutoConfirmApi.Result.CONFIRMED,service.autoConfirm(command));
            f.db.jdbc.update("UPDATE order_status_log SET remark='{}' WHERE event_type='ORDER_AUTO_CONFIRMED'");
            assertThrows(ApiException.class,() -> service.autoConfirm(command));
            f.db.jdbc.update("DELETE FROM order_status_log WHERE event_type='ORDER_AUTO_CONFIRMED'");
            assertThrows(ApiException.class,() -> service.autoConfirm(command));
            assertEquals(1,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
        }
    }

    @Test void inconsistentOrderRefundPointerBlocksAndRefundFactsRequireGuard() throws Exception {
        try (var f = fixture(true)) {
            String order = paid(f,true);
            String store = f.text("SELECT CAST(store_id AS CHAR) FROM pet_order");
            var facts = new RefundOrderFactsApiImpl(f.db.source,f.guard);
            assertThrows(ApiException.class,() -> facts.findByOrder(order,store,new QueryContext("qa",OperatorType.SYSTEM,null)));
            f.db.jdbc.update("UPDATE pet_order SET refund_order_id=99");
            assertEquals(OrderAutoConfirmApi.Result.BLOCKED_BY_REFUND,service(f.db.source).autoConfirm(command(f,order)));
            assertNoConfirmation(f);
            assertEquals(1,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY'"));
        }
    }

    private static PaymentFoundationAcceptanceTest.Fixture fixture(boolean enabled) throws Exception {
        return new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(),enabled);
    }
    private static TransactionTemplate transaction(DataSource source) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }
    private static String paid(PaymentFoundationAcceptanceTest.Fixture f,boolean overdue) throws Exception {
        String order = f.book().orderId(); var intent = f.prepare(order,UUID.randomUUID().toString());
        var notice = f.notice(intent,"SUCCESS","AUTO_" + IDS.incrementAndGet(),intent.amount(),intent.amount());
        if (overdue) {
            ObjectNode body = (ObjectNode) JSON.readTree(notice.body());
            body.put("trade_time",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1)
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            notice = f.sign(JSON.writeValueAsBytes(body));
        }
        f.notification.receive(notice.headers(),notice.body());
        f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(f)); return order;
    }
    private static OffsetDateTime deadline(PaymentFoundationAcceptanceTest.Fixture f) {
        return f.db.jdbc.queryForObject("SELECT confirm_deadline FROM pet_order",LocalDateTime.class).atOffset(ZoneOffset.UTC);
    }
    private static OrderAutoConfirmApi.AutoConfirmOrderCommand command(PaymentFoundationAcceptanceTest.Fixture f,String order) {
        return OrderAutoConfirmService.command("confirm-qa",order,deadline(f));
    }
    private static CommandContext repairContext(String order) {
        return new CommandContext("REPAIR:" + OrderAutoConfirmTaskSpec.key(order),"repair-qa",OperatorType.SYSTEM,null,"REPAIR");
    }
    private static TaskSubmissionSnapshot snapshot(PaymentFoundationAcceptanceTest.Fixture f,String order) {
        return new TaskSubmissionInspector(f.db.source).find(OrderAutoConfirmTaskSpec.key(order));
    }
    private static OrderAutoConfirmService service(DataSource source) { return service(source,null); }
    private static OrderAutoConfirmService service(DataSource source,RefundOrderFactsApi override) {
        var guard = new ScheduleCapacityGuardApiImpl(source);
        return new OrderAutoConfirmService(source,IDS::incrementAndGet,guard,new PaymentSuccessFactsApiImpl(source,guard),
                new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,new ScheduleProtectionFactsApiImpl(source,guard),
                        new OrderPaymentFactsApiImpl(source,guard)),
                override == null ? new RefundOrderFactsApiImpl(source,guard) : override,
                new TransactionalOutboxPublisher(source,IDS::incrementAndGet,JSON));
    }
    private static AsyncTaskWorker worker(PaymentFoundationAcceptanceTest.Fixture f,OrderAutoConfirmService service) {
        return AsyncTaskWorker.create(f.db.source,IDS::incrementAndGet,"auto-qa",Clock.systemUTC(),TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of(OrderAutoConfirmTaskSpec.TYPE,List.of(Duration.ofSeconds(30)))),
                List.of(OrderAutoConfirmTaskRegistration.create(f.db.source,service)));
    }
    private static void insertRefund(PaymentFoundationAcceptanceTest.Fixture f,String order,String status) {
        long id=IDS.incrementAndGet();
        f.db.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,"
                + "initiator_type,created_at,updated_at) VALUES(?,?,?,'FULL','PRESTART_AUTO',128,1,?,'SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id,id,Long.parseLong(order),status);
    }
    private static void assertNoConfirmation(PaymentFoundationAcceptanceTest.Fixture f) {
        assertEquals(0,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
        assertEquals(0,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='ORDER_AUTO_CONFIRMED'"));
    }
    private static void assertConfirmed(PaymentFoundationAcceptanceTest.Fixture f) {
        assertEquals("PENDING_SERVICE",f.text("SELECT order_stage FROM pet_order"));
        assertEquals("AUTO",f.text("SELECT confirm_mode FROM pet_order"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM order_status_log WHERE event_type='ORDER_AUTO_CONFIRMED'"));
    }
}
