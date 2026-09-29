package com.petplatform.boot.booking;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.petplatform.common.*;
import com.petplatform.event.api.*;
import com.petplatform.event.core.TransactionalOutboxPublisher;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import com.petplatform.order.api.command.MerchantOrderCommandApi.*;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.order.biz.application.*;
import com.petplatform.payment.biz.apiimpl.*;
import com.petplatform.payment.biz.application.*;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.refund.biz.application.*;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.task.core.*;
import com.petplatform.boot.config.LateRefundConfiguration;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL and signed offline payment receipts. No live refund network or production flags. */
class MerchantOrderAcceptanceTest {
    private static final AtomicLong IDS=new AtomicLong(8_970_000_000_000_000L);
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void ownerConfirmsOnceAndPrivateNoteIsProtected() throws Exception {
        try(var f=new F()){
            String o=f.paid();var c=f.command(o,"CONFIRM");var first=f.service.decide(c);
            assertEquals(first,f.service.decide(c));assertEquals("PENDING_SERVICE",first.orderStageAtCommit());assertNull(first.refundOrderId());
            assertEquals("MERCHANT",f.f.text("SELECT confirm_mode FROM pet_order"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM order_merchant_decision"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'"));
            byte[] note=f.f.db.jdbc.queryForObject("SELECT internal_note FROM order_merchant_decision",byte[].class);
            assertFalse(new String(note,StandardCharsets.UTF_8).contains("private note"));
            assertFalse(f.f.text("SELECT payload FROM integration_event_outbox WHERE event_type='OrderConfirmedEvent.v1'").contains("private note"));
            code("ORDER_STATE_NOT_ALLOWED",()->f.service.decide(f.command(o,"REJECT")));
        }
    }
    @Test void allApprovedReasonsCreateOneFullRefundWithoutReleasingReservation() throws Exception {
        for(String reason:List.of("SCHEDULE_CONFLICT","STAFF_UNAVAILABLE","PET_NOT_MATCHED","TEMPORARY_CLOSURE","OTHER"))try(var f=new F()){
            String o=f.paid();var c=f.command(o,"REJECT");c=new Command(c.context(),o,0,"REJECT",reason,c.reasonText(),null);
            var first=f.service.decide(c);assertEquals(first,f.service.decide(c));
            assertEquals("CANCELED",f.f.text("SELECT order_stage FROM pet_order"));assertEquals("CONFIRMED",f.f.text("SELECT status FROM schedule_reservation"));
            assertEquals("MERCHANT_REJECT_ORDER",f.f.text("SELECT source_type FROM refund_order"));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_execution WHERE late_event_id IS NOT NULL"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM refund_execution WHERE source_event_id IS NOT NULL"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM async_task WHERE task_type='MERCHANT_REFUND_SUBMIT'"));
            assertEquals(0,new BigDecimal("128.00").compareTo(f.f.db.jdbc.queryForObject("SELECT refund_amount FROM refund_order",BigDecimal.class)));
            assertEquals(0,f.f.db.jdbc.queryForObject("SELECT refunded_amount FROM pet_order",BigDecimal.class).signum());
        }
    }
    @Test void concurrentReplayCannotDuplicateRefundAndDifferentPayloadConflicts() throws Exception {
        try(var f=new F()){
            String o=f.paid();var c=f.command(o,"REJECT");var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)){
                var a=pool.submit(()->{start.await();return f.service.decide(c);});var b=pool.submit(()->{start.await();return f.service.decide(c);});
                start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            }
            code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->f.service.decide(new Command(c.context(),o,0,"REJECT","OTHER","different reason",null)));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(1,f.f.count("SELECT COUNT(*) FROM order_merchant_decision"));
        }
    }
    @Test void outboxTaskAndDecisionFailuresRollBackWholeBusinessButKeepRequestBinding() throws Exception {
        for(String table:List.of("integration_event_outbox","async_task","order_merchant_decision","order_status_log","refund_execution","order_merchant_command"))try(var f=new F()){
            String o=f.paid();var c=f.command(o,"REJECT");
            String trigger=table.equals("order_merchant_command")?"BEFORE UPDATE":"BEFORE INSERT";
            f.f.db.jdbc.execute("CREATE TRIGGER qa_fail "+trigger+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected failure'");
            assertThrows(ApiException.class,()->f.service.decide(c));
            assertEquals("PENDING_CONFIRM",f.f.text("SELECT order_stage FROM pet_order"));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(0,f.f.count("SELECT COUNT(*) FROM order_merchant_decision"));
            assertEquals("RESERVED",f.f.text("SELECT state FROM order_merchant_command"));
            f.f.db.jdbc.execute("DROP TRIGGER qa_fail");assertNotNull(f.service.decide(c).refundOrderId());
        }
    }
    @Test void deadlineIsEnforcedAndAutomaticConfirmationWinsAtAndAfterDeadline() throws Exception {
        try(var f=new F()){
            String o=f.paid(true);code("ORDER_CONFIRM_DEADLINE_PASSED",()->f.service.decide(f.command(o,"REJECT")));
            var auto=f.auto();assertEquals(com.petplatform.order.api.command.OrderAutoConfirmApi.Result.CONFIRMED,auto.autoConfirm(f.autoCommand(o)));
            code("ORDER_STATE_NOT_ALLOWED",()->f.service.decide(f.command(o,"REJECT")));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
    @Test void authorityIsCurrentOnReplayAndOfflineExistingOrdersRemainOperable() throws Exception {
        try(var f=new F()){
            String o=f.paid();f.f.db.jdbc.update("UPDATE merchant SET status='OFFLINE'");f.f.db.jdbc.update("UPDATE merchant_store SET status='OFFLINE'");
            var c=f.command(o,"CONFIRM");f.service.decide(c);f.f.db.jdbc.update("UPDATE merchant SET owner_user_id=999");
            code(CommonApiCodes.FORBIDDEN,()->f.service.decide(c));
        }
        for(String sql:List.of("UPDATE merchant SET status='FROZEN'","UPDATE merchant_store SET status='FROZEN'","UPDATE merchant SET owner_user_id=999"))try(var f=new F()){
            String o=f.paid();f.f.db.jdbc.update(sql);code(CommonApiCodes.FORBIDDEN,()->f.service.decide(f.command(o,"REJECT")));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM order_merchant_command"));
        }
    }
    @Test void revokedSessionModerationRejectionAndUnknownModerationDoNotWriteBusiness() throws Exception {
        try(var f=new F()){
            String o=f.paid();f.sessionActive.set(false);code(CommonApiCodes.UNAUTHORIZED,()->f.service.decide(f.command(o,"CONFIRM")));
            f.sessionActive.set(true);f.moderationAllowed.set(false);code(CommonApiCodes.INVALID_ARGUMENT,()->f.service.decide(f.command(o,"REJECT")));
            f.moderationThrows.set(true);code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->f.service.decide(f.command(o,"REJECT")));
            assertEquals("PENDING_CONFIRM",f.f.text("SELECT order_stage FROM pet_order"));assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
    @Test void failedRefundRecordsWithoutExecutionStillBlockAndUnknownDependenciesFailClosed() throws Exception {
        for(String change:List.of("UPDATE payment_order SET status='RECONCILIATION_REQUIRED'","UPDATE schedule_reservation SET status='EXPIRED'","UPDATE pet_order SET current_aftersale_id=88"))try(var f=new F()){
            String o=f.paid();f.f.db.jdbc.update(change);code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->f.service.decide(f.command(o,"REJECT")));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
        try(var f=new F()){
            String o=f.paid();f.f.db.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,initiator_type,created_at,updated_at) VALUES(99,99,?,'FULL','PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",Long.parseLong(o));
            code("ORDER_REFUND_ALREADY_CREATED",()->f.service.decide(f.command(o,"CONFIRM")));
        }
    }
    @Test void successProjectionAndReservationReleaseAreAtomicAndReplaySafe() throws Exception {
        try(var f=new F()){
            String o=f.paid();var receipt=f.service.decide(f.command(o,"REJECT"));var executor=f.executor(false);
            assertTrue(executor.execute(receipt.refundOrderId(),"710302",false,"qa").done());assertEquals(1,f.sends.get());
            assertEquals("CONFIRMED",f.f.text("SELECT status FROM schedule_reservation"));
            var event=f.event("RefundSucceededEvent.v1");
            var forged=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(event.payloadJson());forged.put("refundAmount",1);
            assertThrows(ApiException.class,()->f.projection.consume(new DispatchedEvent(event.eventId(),event.eventType(),event.eventVersion(),event.occurredAt(),
                event.aggregateType(),event.aggregateId(),event.traceId(),JSON.writeValueAsString(forged))));
            assertEquals("CONFIRMED",f.f.text("SELECT status FROM schedule_reservation"));
            f.f.db.jdbc.execute("CREATE TRIGGER qa_release_fail BEFORE UPDATE ON schedule_reservation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='release failure'");
            assertThrows(ApiException.class,()->f.projection.consume(event));
            assertEquals(0,f.f.db.jdbc.queryForObject("SELECT refunded_amount FROM pet_order",BigDecimal.class).signum());
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM integration_event_consume_log WHERE consumer_name='ORDER_MERCHANT_REFUND'"));
            f.f.db.jdbc.execute("DROP TRIGGER qa_release_fail");f.projection.consume(event);f.projection.consume(event);
            assertEquals("RELEASED",f.f.text("SELECT status FROM schedule_reservation"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM schedule_reservation_audit WHERE action='REFUND_RELEASE'"));
            assertEquals(0,new BigDecimal("128").compareTo(f.f.db.jdbc.queryForObject("SELECT refunded_amount FROM pet_order",BigDecimal.class)));
            assertTrue(executor.execute(receipt.refundOrderId(),"710302",false,"qa").done());assertEquals(1,f.sends.get());
            new OrderLateRefundProjectionConsumer(f.f.db.source,IDS::incrementAndGet,f.f.guard,f.f.reservationExpiry,f.refunds).consume(event);
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM order_late_refund_result"));
        }
    }
    @Test void unknownChannelOutcomeQueriesOriginalNumberAndNeverResubmits() throws Exception {
        try(var f=new F()){
            String o=f.paid();var receipt=f.service.decide(f.command(o,"REJECT"));var executor=f.executor(true);
            assertTrue(executor.execute(receipt.refundOrderId(),"710302",false,"qa").done());
            assertTrue(executor.execute(receipt.refundOrderId(),"710302",false,"qa").done());assertEquals(1,f.sends.get());
            assertEquals("UNKNOWN",f.f.text("SELECT status FROM refund_order"));assertEquals("CONFIRMED",f.f.text("SELECT status FROM schedule_reservation"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM async_task WHERE task_type='MERCHANT_REFUND_CHANNEL_QUERY'"));
            f.f.db.jdbc.update("UPDATE payment_refund_dispatch SET query_not_before=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
            assertTrue(executor.execute(receipt.refundOrderId(),"710302",true,"qa").done());assertEquals(1,f.sends.get());assertEquals(1,f.queries.get());
            f.projection.consume(f.event("RefundSucceededEvent.v1"));assertEquals("RELEASED",f.f.text("SELECT status FROM schedule_reservation"));
        }
    }
    @Test void forgedSourceOrSuccessCannotSendOrRelease() throws Exception {
        try(var f=new F()){
            String o=f.paid();var receipt=f.service.decide(f.command(o,"REJECT"));
            f.f.db.jdbc.update("UPDATE refund_execution SET source_event_id=source_event_id+1");
            assertThrows(ApiException.class,()->f.executor(false).execute(receipt.refundOrderId(),"710302",false,"qa"));assertEquals(0,f.sends.get());
            var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.db.source));
            assertThrows(ApiException.class,()->tx.executeWithoutResult(s->{var ctx=new QueryContext("qa",OperatorType.SYSTEM,null);f.f.guard.acquire(List.of("710302"),ctx);
                f.release.release(o,f.f.text("SELECT CAST(id AS CHAR) FROM schedule_reservation"),"710302",receipt.refundOrderId(),ctx);}));
            assertEquals("CONFIRMED",f.f.text("SELECT status FROM schedule_reservation"));
        }
    }
    @Test void rejectionDoesNotReviveWhenPaymentOrAutomaticTaskReplays() throws Exception {
        try(var f=new F()){
            String o=f.paid();f.service.decide(f.command(o,"REJECT"));f.f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(f.f));
            f.f.db.jdbc.update("UPDATE payment_order SET status='RECONCILIATION_REQUIRED'");
            f.f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(f.f));
            assertEquals(com.petplatform.order.api.command.OrderAutoConfirmApi.Result.STALE,f.auto().autoConfirm(f.autoCommand(o)));
            assertEquals("CANCELED",f.f.text("SELECT order_stage FROM pet_order"));assertEquals(1,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
    @Test void newWorkerProcessesOnlyItsSourceTaskType() throws Exception {
        try(var f=new F()){
            String o=f.paid();var rejected=f.service.decide(f.command(o,"REJECT"));var execution=f.executor(false);
            assertThrows(ApiException.class,()->execution.execute(rejected.refundOrderId(),"710302",false,"qa","LATE_PAYMENT_TIMEOUT"));
            assertEquals(0,f.sends.get());
            try(var worker=AsyncTaskWorker.create(f.f.db.source,IDS::incrementAndGet,"merchant-qa",Clock.systemUTC(),TaskWorkerSettings.defaults(),
                new TaskRetryDelays(Map.of("REFUND_CHANNEL",List.of(Duration.ofSeconds(30)))),List.of(LateRefundConfiguration.registration("MERCHANT_REFUND_SUBMIT",execution,f.f.db.source)))){
                assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne());
            }
            assertEquals("SUCCESS",f.f.text("SELECT status FROM refund_order"));assertEquals(1,f.sends.get());
        }
    }
    @Test void malformedReasonsAndRoundsNeverReachDurableAdmission() throws Exception {
        try(var f=new F()){
            String o=f.paid();var c=f.command(o,"REJECT");
            for(String reason:List.of("1234"," ".repeat(10),"a".repeat(201),"\uD83D\uDC36".repeat(201),"broken\uD800"))
                code(CommonApiCodes.INVALID_ARGUMENT,()->f.service.decide(new Command(c.context(),o,0,"REJECT","OTHER",reason,null)));
            code(CommonApiCodes.INVALID_ARGUMENT,()->f.service.decide(new Command(c.context(),o,2,"REJECT","OTHER","valid reason",null)));
            code(CommonApiCodes.INVALID_ARGUMENT,()->f.service.decide(new Command(c.context(),o,0,"REJECT","NEW_REASON","valid reason",null)));
            assertEquals(0,f.f.count("SELECT COUNT(*) FROM order_merchant_command"));
            assertNotNull(f.service.decide(new Command(c.context(),o,0,"REJECT","OTHER","\uD83D\uDC36".repeat(5),null)).refundOrderId());
        }
    }
    @Test void lostBusinessCommitAckReplaysTheOriginalProtectedReceipt() throws Exception {
        try(var f=new F()){
            String o=f.paid();var c=f.command(o,"REJECT");AtomicInteger commits=new AtomicInteger();
            DataSource lost=new DelegatingDataSource(f.f.db.source){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                    var connection=super.getConnection();
                    return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                        try{Object result=method.invoke(connection,args);
                            if(method.getName().equals("commit")&&commits.incrementAndGet()==3)throw new java.sql.SQLException("ACK lost after business commit","08006");
                            return result;
                        }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
                }
            };
            code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->f.build(lost).decide(c));
            assertEquals("SUCCEEDED",f.f.text("SELECT state FROM order_merchant_command"));
            var receipt=f.service.decide(c);assertEquals(receipt,f.service.decide(c));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM refund_order"));assertEquals(1,f.f.count("SELECT COUNT(*) FROM order_merchant_decision"));
        }
    }
    @Test void confirmAndRejectRaceCommitExactlyOneDecision() throws Exception {
        try(var f=new F();var pool=Executors.newFixedThreadPool(2)){
            String o=f.paid();var start=new CountDownLatch(1);
            java.util.function.Function<String,Object> decide=action->{try{start.await();return f.service.decide(f.command(o,action));}
                catch(ApiException rejected){return rejected.code();}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}};
            var confirm=pool.submit(()->decide.apply("CONFIRM"));var reject=pool.submit(()->decide.apply("REJECT"));start.countDown();
            var results=List.of(confirm.get(10,TimeUnit.SECONDS),reject.get(10,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(Receipt.class::isInstance).count());assertTrue(results.contains("ORDER_STATE_NOT_ALLOWED"));
            assertEquals(1,f.f.count("SELECT COUNT(*) FROM order_merchant_decision"));
            String action=((Receipt)results.stream().filter(Receipt.class::isInstance).findFirst().orElseThrow()).action();
            assertEquals(action.equals("REJECT")?1:0,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
    @Test void waitingForStoreLockPastDeadlineCannotBeatAutomaticConfirmation() throws Exception {
        try(var f=new F();var pool=Executors.newFixedThreadPool(3)){
            String o=f.paidNearDeadline();var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            var lockTx=new TransactionTemplate(new DataSourceTransactionManager(f.f.db.source));
            lockTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            var lock=pool.submit(()->lockTx.executeWithoutResult(s->{
                f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));held.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}
            }));assertTrue(held.await(3,TimeUnit.SECONDS));
            var now=f.f.db.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class);
            var deadline=f.f.db.jdbc.queryForObject("SELECT confirm_deadline FROM pet_order",LocalDateTime.class);
            assertTrue(now.isBefore(deadline));
            // Enter inside the existing two-second DB lock wait budget, then cross the cutoff.
            Duration beforeEntry=Duration.between(now,deadline).minusMillis(800);
            if(!beforeEntry.isNegative())Thread.sleep(beforeEntry);
            now=f.f.db.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class);assertTrue(now.isBefore(deadline));
            var entered=new CountDownLatch(1);
            var manual=pool.submit(()->{entered.countDown();return assertThrows(ApiException.class,()->f.service.decide(f.command(o,"REJECT"))).code();});
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            Thread.sleep(Duration.between(now,deadline).plusMillis(150));
            var automatic=pool.submit(()->f.auto().autoConfirm(f.autoCommand(o)));release.countDown();lock.get(3,TimeUnit.SECONDS);
            assertTrue(Set.of("ORDER_CONFIRM_DEADLINE_PASSED","ORDER_STATE_NOT_ALLOWED").contains(manual.get(10,TimeUnit.SECONDS)));
            assertEquals(com.petplatform.order.api.command.OrderAutoConfirmApi.Result.CONFIRMED,automatic.get(10,TimeUnit.SECONDS));
            assertEquals("AUTO",f.f.text("SELECT confirm_mode FROM pet_order"));assertEquals(0,f.f.count("SELECT COUNT(*) FROM refund_order"));
        }
    }
    private static void code(String code,org.junit.jupiter.api.function.Executable work){assertEquals(code,assertThrows(ApiException.class,work).code());}
    static final class F implements AutoCloseable {
        final PaymentFoundationAcceptanceTest.Fixture f=new PaymentFoundationAcceptanceTest.Fixture(Clock.systemUTC(),true);
        final OrderMerchantRejectFactsApiImpl facts=new OrderMerchantRejectFactsApiImpl(f.db.source,f.guard,new ReservationConfirmApiImpl(f.db.source,IDS::incrementAndGet,f.guard,new ScheduleProtectionFactsApiImpl(f.db.source,f.guard),new OrderPaymentFactsApiImpl(f.db.source,f.guard)));
        final PaymentSuccessFactsApiImpl paid=new PaymentSuccessFactsApiImpl(f.db.source,f.guard);
        final LateRefundService refunds=new LateRefundService(f.db.source,IDS::incrementAndGet,f.guard,
            new OrderLatePaymentFactsApiImpl(f.db.source,f.guard,f.reservationExpiry),paid,f.publisher,facts);
        final ReservationRefundReleaseApiImpl release=new ReservationRefundReleaseApiImpl(f.db.source,IDS::incrementAndGet,f.guard,refunds);
        final OrderMerchantRefundProjectionConsumer projection=new OrderMerchantRefundProjectionConsumer(f.db.source,IDS::incrementAndGet,f.guard,facts,refunds,release);
        final AtomicBoolean sessionActive=new AtomicBoolean(true),moderationAllowed=new AtomicBoolean(true),moderationThrows=new AtomicBoolean(false);
        final AtomicInteger sends=new AtomicInteger(),queries=new AtomicInteger();
        final MerchantOrderService service;
        F() throws Exception {
            service=build(f.db.source);
        }
        MerchantOrderService build(DataSource source){
            var guard=new ScheduleCapacityGuardApiImpl(source);
            var payment=new PaymentSuccessFactsApiImpl(source,guard);
            var outbox=new TransactionalOutboxPublisher(source,IDS::incrementAndGet,JSON);
            var refund=new LateRefundService(source,IDS::incrementAndGet,guard,
                new OrderLatePaymentFactsApiImpl(source,guard,f.reservationExpiry),payment,outbox,new OrderMerchantRejectFactsApiImpl(source,guard,new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,new ScheduleProtectionFactsApiImpl(source,guard),new OrderPaymentFactsApiImpl(source,guard))));
            return new MerchantOrderService(source,IDS::incrementAndGet,guard,new MerchantOrderAuthorityApiImpl(source,guard),payment,
                new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,new ScheduleProtectionFactsApiImpl(source,guard),new OrderPaymentFactsApiImpl(source,guard)),
                new RefundOrderFactsApiImpl(source,guard),refund,outbox,new MerchantOrderAesProtection(new byte[32]),
                text->{if(moderationThrows.get())throw new IllegalStateException();return new MerchantOrderPorts.Approval(MerchantOrderService.sha(text.getBytes(StandardCharsets.UTF_8)),"qa-policy-1",moderationAllowed.get());},
                user->{if(!sessionActive.get())throw new ApiException(CommonApiCodes.UNAUTHORIZED,"qa revoked");});
        }
        String paid() throws Exception {return paid(false);}
        String paid(boolean overdue) throws Exception {return paidAt(overdue?LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1):null);}
        String paidNearDeadline() throws Exception {return paidAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).plusSeconds(7));}
        String paidAt(LocalDateTime signedTime) throws Exception {
            String o=f.book().orderId();var p=f.prepare(o,UUID.randomUUID().toString());
            var n=f.notice(p,"SUCCESS","MERCHANT_"+IDS.incrementAndGet(),p.amount(),p.amount());
            if(signedTime!=null){var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(n.body());
                body.put("trade_time",signedTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
                n=f.sign(JSON.writeValueAsBytes(body));}
            f.notification.receive(n.headers(),n.body());f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(f));return o;
        }
        Command command(String o,String action){return new Command(new CommandContext(UUID.randomUUID().toString(),"merchant-qa",OperatorType.USER,"710300","MINIAPP"),o,0,action,
            action.equals("REJECT")?"OTHER":null,action.equals("REJECT")?"staff cannot attend":null,action.equals("CONFIRM")?"private note":null);}
        OrderAutoConfirmService auto(){return new OrderAutoConfirmService(f.db.source,IDS::incrementAndGet,f.guard,paid,
            new ReservationConfirmApiImpl(f.db.source,IDS::incrementAndGet,f.guard,new ScheduleProtectionFactsApiImpl(f.db.source,f.guard),new OrderPaymentFactsApiImpl(f.db.source,f.guard)),new RefundOrderFactsApiImpl(f.db.source,f.guard),f.publisher);}
        com.petplatform.order.api.command.OrderAutoConfirmApi.AutoConfirmOrderCommand autoCommand(String o){return OrderAutoConfirmService.command("qa",o,f.db.jdbc.queryForObject("SELECT confirm_deadline FROM pet_order",LocalDateTime.class).atOffset(ZoneOffset.UTC));}
        RefundExecutionService executor(boolean unknown){
            PaymentRefundChannel channel=new PaymentRefundChannel(){
                public VerifiedResult submit(RefundRequest request){sends.incrementAndGet();if(unknown)throw new IllegalStateException("connection lost");return success(request);}
                public VerifiedResult query(RefundRequest request){queries.incrementAndGet();return success(request);}
                private VerifiedResult success(RefundRequest request){return new VerifiedResult("SUCCESS",request.refundNo(),"MERCHANT_REFUND_01",12800,12800L,
                    LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1).truncatedTo(ChronoUnit.SECONDS),"d".repeat(64));}
            };
            var payment=new PaymentRefundService(f.db.source,IDS::incrementAndGet,f.guard,new OrderLatePaymentFactsApiImpl(f.db.source,f.guard,f.reservationExpiry),paid,refunds,
                channel,new PaymentRefundService.Settings("127.0.0.1","https://qa.invalid/refund",ZoneId.of("Asia/Shanghai")),Clock.systemUTC(),facts);
            return new RefundExecutionService(refunds,payment,new PaymentRefundResultFactsApiImpl(f.db.source,f.guard));
        }
        DispatchedEvent event(String type){return f.db.jdbc.queryForObject("SELECT event_id,event_version,occurred_at,aggregate_type,aggregate_id,trace_id,payload FROM integration_event_outbox WHERE event_type=?",
            (rs,n)->new DispatchedEvent(rs.getString("event_id"),type,rs.getInt("event_version"),rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
                rs.getString("aggregate_type"),rs.getLong("aggregate_id"),rs.getString("trace_id"),rs.getString("payload")),type);}
        public void close(){f.close();}
    }
}
