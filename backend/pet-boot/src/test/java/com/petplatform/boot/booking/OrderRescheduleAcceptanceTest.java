package com.petplatform.boot.booking;
import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.common.*;
import com.petplatform.order.api.command.OrderRescheduleApi.*;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
import com.petplatform.order.api.command.OrderAutoConfirmApi;
import com.petplatform.order.api.command.OrderAutoConfirmRepairApi;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.order.biz.application.*;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.verification.api.command.VerificationRescheduleFenceApi;
import com.petplatform.task.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real SQL owners and signed offline payment. qa_verification_fence is an explicit QA-only
 * durable stand-in: these tests do NOT prove real verification-code invalidation or C HTTP. */
class OrderRescheduleAcceptanceTest {
 static final AtomicLong IDS=new AtomicLong(9_070_000_000_000_000L);
 static final OffsetDateTime START=OffsetDateTime.parse("2030-01-01T09:15:00Z");
 @Test void atomicExchangePreservesIdentityAndSnapshotsAndReplaysFirstReceipt() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();String reservation=t.text("SELECT CAST(reservation_id AS CHAR) FROM pet_order");
   String claim=t.text("SELECT CAST(id AS CHAR) FROM schedule_reservation_claim");
   String snapshot=t.text("SELECT CAST(snapshot_json AS CHAR) FROM order_service_snapshot");
   var c=t.command(o);var r=t.service.reschedule(c);
   assertEquals(r,t.service.reschedule(c));assertEquals(reservation,r.reservationId());assertEquals(1,r.confirmRound());
   assertEquals(claim,t.text("SELECT CAST(id AS CHAR) FROM schedule_reservation_claim"));
   assertEquals(snapshot,t.text("SELECT CAST(snapshot_json AS CHAR) FROM order_service_snapshot"));
   assertEquals(1,t.count("SELECT COUNT(*) FROM schedule_reservation"));assertEquals(1,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));
   assertEquals(1,t.count("SELECT COUNT(*) FROM qa_verification_fence"));assertEquals(1,t.count("SELECT COUNT(*) FROM order_reschedule_record"));
   assertEquals(1,t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderRescheduledEvent.v1'"));
   assertEquals(OffsetDateTime.parse(r.rescheduledAt()).plusMinutes(30),OffsetDateTime.parse(r.confirmDeadline()));
   assertEquals("CANCELED",t.text("SELECT status FROM async_task WHERE task_key LIKE 'ORDER_AUTO_CONFIRM:%:0'"));
   assertEquals("READY",t.text("SELECT status FROM async_task WHERE task_key LIKE 'ORDER_AUTO_CONFIRM:%:1'"));
   code("ORDER_RESCHEDULE_LIMIT_REACHED",()->t.service.reschedule(t.command(o)));
   var altered=new Command(c.context(),o,c.expectedOrderVersion(),START.plusMinutes(5),START.plusMinutes(95),null,null,"710500",null,null);
   code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->t.service.reschedule(altered));
   t.f.f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(t.f.f));assertEquals(r.confirmDeadline(),t.deadline().toString());
  }
 }
 @Test void unchangedIntervalsWrongVersionAndForeignUserDoNotConsumeOpportunity() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();var c=t.command(o);
   code(CommonApiCodes.CONFLICT,()->t.service.reschedule(new Command(c.context(),o,c.expectedOrderVersion(),START.minusMinutes(15),START.plusMinutes(75),null,null,"710500",null,null)));
   code(CommonApiCodes.CONFLICT,()->t.service.reschedule(new Command(context("710100"),o,"0",START,START.plusMinutes(90),null,null,"710500",null,null)));
   code(CommonApiCodes.FORBIDDEN,()->t.service.reschedule(new Command(context("710101"),o,c.expectedOrderVersion(),START,START.plusMinutes(90),null,null,"710500",null,null)));
   assertEquals(0,t.count("SELECT COUNT(*) FROM order_reschedule_record"));assertEquals(0,t.count("SELECT reschedule_count FROM pet_order"));
   assertNotNull(t.service.reschedule(t.command(o)));
  }
 }
 @Test void everyPersistenceFailureRollsBackSwapTasksFenceAndEventButKeepsAdmission() throws Exception {
  for(String table:List.of("schedule_reservation_claim","schedule_reservation","schedule_reservation_change","pet_order","qa_verification_fence",
    "order_reschedule_record","async_task","order_status_log","integration_event_outbox","order_reschedule_command"))try(var t=new T()){
   String o=t.f.paid();var c=t.command(o);String original=t.text("SELECT CAST(start_at AS CHAR) FROM schedule_reservation");
   String operation=Set.of("schedule_reservation_claim","schedule_reservation","pet_order","async_task","order_reschedule_command").contains(table)?"UPDATE":"INSERT";
   t.f.f.db.jdbc.execute("CREATE TRIGGER qa_fail BEFORE "+operation+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
   assertThrows(ApiException.class,()->t.service.reschedule(c),table);
   assertEquals(original,t.text("SELECT CAST(start_at AS CHAR) FROM schedule_reservation"),table);
   assertEquals(0,t.count("SELECT reschedule_count FROM pet_order"),table);assertEquals(0,t.count("SELECT COUNT(*) FROM order_reschedule_record"));
   assertEquals(0,t.count("SELECT COUNT(*) FROM qa_verification_fence"));assertEquals(0,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));
   assertEquals("READY",t.text("SELECT status FROM async_task WHERE task_type='ORDER_AUTO_CONFIRM'"));
   assertEquals("RESERVED",t.text("SELECT state FROM order_reschedule_command"));
   t.f.f.db.jdbc.execute("DROP TRIGGER qa_fail");assertNotNull(t.service.reschedule(c));
  }
 }
 @Test void runningLeaseCannotHeartbeatOrCompleteAfterReschedule() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update("UPDATE async_task SET execute_at=UTC_TIMESTAMP(3) WHERE task_type='ORDER_AUTO_CONFIRM'");
   var repository=new JdbcAsyncTaskRepository(t.f.f.db.source,IDS::incrementAndGet,Set.of("ORDER_AUTO_CONFIRM"));
   var lease=repository.claim("reschedule-qa",Duration.ofMinutes(1)).orElseThrow();
   t.service.reschedule(t.command(o));assertFalse(repository.heartbeat(lease,Duration.ofMinutes(1)));
   assertFalse(repository.complete(lease,new TaskExecutionResult.Success("CONFIRMED")));
   assertEquals("NOOP",t.text("SELECT result FROM async_task_attempt WHERE task_id="+lease.taskId()));
   assertEquals("OWNER_CANCELED",t.text("SELECT error_code FROM async_task_attempt WHERE task_id="+lease.taskId()));
  }
 }
 @Test void oldRoundIsStaleAndNewRoundCanConfirmRejectRefundAndReplay() throws Exception {
  for(String action:List.of("CONFIRM","REJECT"))try(var t=new T()){
   String o=t.f.paid();var old=t.f.autoCommand(o);var c=t.command(o);var receipt=t.service.reschedule(c);
   assertEquals(OrderAutoConfirmApi.Result.STALE,t.f.auto().autoConfirm(old));
   assertEquals(OrderAutoConfirmRepairApi.Result.STALE,t.f.auto().repairMissingTask(new CommandContext("REPAIR:"+OrderAutoConfirmTaskSpec.key(o),"qa",OperatorType.SYSTEM,null,"REPAIR"),o));
   var m=t.f.command(o,action);var command=new MerchantOrderCommandApi.Command(m.context(),o,1,m.action(),m.reasonCode(),m.reasonText(),m.internalNote());
   var decision=t.f.service.decide(command);assertEquals(1,decision.confirmRound());assertEquals(decision,t.f.service.decide(command));
   assertEquals(receipt,t.service.reschedule(c));
   if(action.equals("REJECT")){
    assertEquals("CONFIRMED",t.text("SELECT status FROM schedule_reservation"));
    assertTrue(t.f.executor(false).execute(decision.refundOrderId(),"710302",false,"qa").done());
    t.f.projection.consume(t.f.event("RefundSucceededEvent.v1"));assertEquals("RELEASED",t.text("SELECT status FROM schedule_reservation"));
   }
   t.f.f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(t.f.f));
  }
 }
 @Test void concurrentDuplicateIsOneExchangeAndOneEvent() throws Exception {
  try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
   String o=t.f.paid();var c=t.command(o);var start=new CountDownLatch(1);
   var a=pool.submit(()->{start.await();return t.service.reschedule(c);});var b=pool.submit(()->{start.await();return t.service.reschedule(c);});start.countDown();
   assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
   assertEquals(1,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));assertEquals(1,t.count("SELECT COUNT(*) FROM qa_verification_fence"));
  }
 }
 @Test void unknownDependenciesAndExistingRefundFailClosed() throws Exception {
  for(String sql:List.of("UPDATE payment_order SET status='RECONCILIATION_REQUIRED'","UPDATE pet_order SET current_aftersale_id=42",
   "UPDATE pet_order SET current_refund_application_id=43","UPDATE schedule_reservation SET status='EXPIRED'"))try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update(sql);code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.service.reschedule(t.command(o)));
   assertEquals(0,t.count("SELECT COUNT(*) FROM order_reschedule_record"));
  }
 }
 @Test void originalManualConfirmationRemainsAuditedAfterSecondRoundDecision() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();var first=t.f.command(o,"CONFIRM");var r=t.f.service.decide(first);
   t.service.reschedule(t.command(o));assertEquals(r,t.f.service.decide(first));
   var next=t.f.command(o,"REJECT");t.f.service.decide(new MerchantOrderCommandApi.Command(next.context(),o,1,"REJECT",next.reasonCode(),next.reasonText(),null));
   assertEquals(2,t.count("SELECT COUNT(*) FROM order_merchant_decision"));
  }
 }
 @Test void pickupSwapsTwoWholeWindowsAndKeepsTheOriginalStaff() throws Exception {
  try(var t=new T()){
   String o=t.pickupPaid();t.assign(o);
   t.window(710503,"PICKUP","2030-01-01 09:40:00","2030-01-01 10:15:00");
   t.window(710504,"RETURN","2030-01-01 12:30:00","2030-01-01 13:20:00");
   var c=new Command(context("710100"),o,t.text("SELECT CAST(version AS CHAR) FROM pet_order"),null,null,
    START.plusMinutes(25),START.plusMinutes(195),null,"710503","710504");
   var r=t.service.reschedule(c);assertEquals("2030-01-01T09:40Z",r.pickupStart());assertEquals("2030-01-01T12:30Z",r.returnStart());
   assertEquals("2030-01-01T13:20Z",r.appointmentEnd());assertEquals(2,t.count("SELECT COUNT(*) FROM schedule_reservation_claim"));
   assertEquals("710303",t.text("SELECT CAST(service_staff_id AS CHAR) FROM pet_order"));
   assertEquals(1,t.count("SELECT COUNT(*) FROM order_staff_assignment WHERE is_current=1"));
  }
 }
 @Test void newTimeConflictsWithAnotherServiceAndRetainsAllOldClaims() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();
   t.f.f.db.jdbc.update("UPDATE schedule_availability_window SET start_at='2030-01-01 10:30:00',end_at='2030-01-01 11:05:00' WHERE id=710501");
   t.f.f.db.jdbc.update("UPDATE schedule_availability_window SET start_at='2030-01-01 12:30:00',end_at='2030-01-01 13:20:00' WHERE id=710502");
   t.pickupBook(OffsetDateTime.parse("2030-01-01T10:30Z"),OffsetDateTime.parse("2030-01-01T12:30Z"));
   code("SCHEDULE_CAPACITY_EXCEEDED",()->t.service.reschedule(t.command(o)));
   assertEquals(0,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));assertEquals(3,t.count("SELECT COUNT(*) FROM schedule_reservation_claim"));
  }
 }
 @Test void assignedStaffCannotBeSilentlyReplacedWhenOnlyAnotherPersonCoversNewTime() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();t.assign(o);t.f.f.db.jdbc.update("UPDATE staff_availability_window SET end_at='2030-01-01 10:30:00'");
   t.f.f.db.jdbc.update("INSERT INTO merchant_staff(id,merchant_id,store_id,staff_name,employment_status,service_enabled,created_at,updated_at) VALUES(710304,710301,710302,'QA alternate','ACTIVE',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
   t.f.f.db.jdbc.update("INSERT INTO staff_service_capability(id,staff_id,service_id,status,created_at,updated_at) VALUES(?,710304,710401,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",IDS.incrementAndGet());
   t.f.f.db.jdbc.update("INSERT INTO staff_availability_window(id,store_id,staff_id,start_at,end_at,status,created_at,updated_at) VALUES(?,710302,710304,'2030-01-01 08:00:00','2030-01-01 14:00:00','AVAILABLE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",IDS.incrementAndGet());
   code("SCHEDULE_CAPACITY_EXCEEDED",()->t.service.reschedule(t.command(o)));assertEquals("710303",t.text("SELECT CAST(service_staff_id AS CHAR) FROM pet_order"));
  }
 }
 @Test void roundOneRepairAndWorkerUseImmutableRescheduleTimeAndRejectTampering() throws Exception {
  for(boolean repairTerminal:List.of(false,true))try(var t=new T()){
   String o=t.f.paid();t.service.reschedule(t.command(o));var auto=t.f.auto();
   var command=OrderAutoConfirmService.command("qa",o,1,t.deadline());assertEquals(OrderAutoConfirmApi.Result.NOT_DUE,auto.autoConfirm(command));
   t.f.f.db.jdbc.update("DELETE FROM async_task WHERE task_key=?",OrderAutoConfirmTaskSpec.key(o,1));
   var repair=new CommandContext("REPAIR:"+OrderAutoConfirmTaskSpec.key(o,1),"qa",OperatorType.SYSTEM,null,"REPAIR");
   assertEquals(OrderAutoConfirmRepairApi.Result.CREATED,auto.repairMissingTask(repair,o));
   assertEquals(1,t.count("SELECT COUNT(*) FROM async_task WHERE task_key LIKE 'ORDER_AUTO_CONFIRM:%:1'"));
   // Explicit time-travel fixture: shift the immutable epoch AND its matching task to a due instant.
   var now=t.f.f.db.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",LocalDateTime.class);var due=now.minusSeconds(1);
   t.f.f.db.jdbc.update("UPDATE order_reschedule_record SET rescheduled_at=?,new_confirm_deadline=?",due.minusMinutes(30),due);
   t.f.f.db.jdbc.update("UPDATE pet_order SET confirm_deadline=?",due);
   t.f.f.db.jdbc.update("UPDATE async_task SET payload_json=?,submitted_execute_at=?,execute_at=? WHERE task_key=?",OrderAutoConfirmTaskSpec.payload(o,1,due.atOffset(ZoneOffset.UTC)),due,due,OrderAutoConfirmTaskSpec.key(o,1));
   if(repairTerminal){
    t.f.f.db.jdbc.update("UPDATE async_task SET status='DEAD' WHERE task_key=?",OrderAutoConfirmTaskSpec.key(o,1));
    assertEquals(OrderAutoConfirmRepairApi.Result.RECOVERED,auto.repairMissingTask(repair,o));
   }else try(var worker=AsyncTaskWorker.create(t.f.f.db.source,IDS::incrementAndGet,"rs-worker",Clock.systemUTC(),TaskWorkerSettings.defaults(),
     new TaskRetryDelays(Map.of("ORDER_AUTO_CONFIRM",List.of(Duration.ofSeconds(1)))),List.of(OrderAutoConfirmTaskRegistration.create(t.f.f.db.source,auto)))){
    assertEquals(AsyncTaskWorker.Outcome.COMPLETED,worker.runOne());
   }
   assertEquals("AUTO",t.text("SELECT confirm_mode FROM pet_order"));
   assertEquals(OrderAutoConfirmApi.Result.ALREADY_CONFIRMED,auto.autoConfirm(OrderAutoConfirmService.command("qa",o,1,t.deadline())));
   t.f.f.db.jdbc.update("UPDATE pet_order SET confirm_deadline=TIMESTAMPADD(SECOND,1,confirm_deadline)");
   code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->auto.autoConfirm(OrderAutoConfirmService.command("qa",o,1,t.deadline())));
   assertEquals("TASK:"+OrderAutoConfirmTaskSpec.key(o,1),t.text("SELECT request_id FROM order_status_log WHERE event_type='AUTO_CONFIRM_ANOMALY' ORDER BY id DESC LIMIT 1"));
  }
 }
 @Test void oldTaskMissingRetryAndTerminalOutcomesAreExplicitAndCorruptionRollsBack() throws Exception {
  for(String status:List.of("MISSING","RETRY_WAIT","SUCCEEDED","DEAD","CANCELED","BROKEN"))try(var t=new T()){
   String o=t.f.paid();String key=OrderAutoConfirmTaskSpec.key(o);
   if(status.equals("MISSING"))t.f.f.db.jdbc.update("DELETE FROM async_task WHERE task_key=?",key);
   else if(status.equals("BROKEN"))t.f.f.db.jdbc.update("UPDATE async_task SET owner_module='REFUND' WHERE task_key=?",key);
   else t.f.f.db.jdbc.update("UPDATE async_task SET status=? WHERE task_key=?",status,key);
   if(status.equals("BROKEN")){code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.service.reschedule(t.command(o)));assertEquals(0,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));}
   else {
    t.service.reschedule(t.command(o));String outcome=t.text("SELECT old_task_outcome FROM order_reschedule_record");
    assertEquals(status.equals("MISSING")?"MISSING":status.equals("RETRY_WAIT")?"CANCELED":"TERMINAL",outcome);
   }
  }
 }
 @Test void merchantConfirmationRaceHasOneWinnerAndStaleVersionCannotOverwriteIt() throws Exception {
  try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
   String o=t.f.paid();var c=t.command(o);var m=t.f.command(o,"CONFIRM");var start=new CountDownLatch(1);
   var a=pool.submit(()->{start.await();try{return (Object)t.service.reschedule(c);}catch(ApiException e){return e.code();}});
   var b=pool.submit(()->{start.await();try{return (Object)t.f.service.decide(m);}catch(ApiException e){return e.code();}});
   start.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter(x->x instanceof String).count());
   assertTrue(results.contains(CommonApiCodes.CONFLICT)||results.contains("ORDER_STATE_NOT_ALLOWED"));
  }
 }
 @Test void liveSessionRefundAndFrozenMerchantAreEnforcedWhileServiceSnapshotSurvivesOffline() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update("UPDATE service_item SET status='OFFLINE',duration_minutes=30 WHERE id=710401");
   t.f.f.db.jdbc.update("UPDATE merchant SET status='OFFLINE'");t.f.f.db.jdbc.update("UPDATE merchant_store SET status='OFFLINE'");
   var c=t.command(o);t.service.reschedule(c);t.f.sessionActive.set(false);code(CommonApiCodes.UNAUTHORIZED,()->t.service.reschedule(c));
  }
  for(String sql:List.of("UPDATE merchant SET status='FROZEN'","UPDATE merchant_store SET status='FROZEN'"))try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update(sql);code(CommonApiCodes.FORBIDDEN,()->t.service.reschedule(t.command(o)));
  }
  try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,initiator_type,created_at,updated_at) VALUES(99,99,?,'FULL','PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",Long.parseLong(o));
   code("ORDER_REFUND_ALREADY_CREATED",()->t.service.reschedule(t.command(o)));
  }
 }
 @Test void committedRescheduleWithLostAckReturnsItsOriginalReceipt() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();var c=t.command(o);var commits=new java.util.concurrent.atomic.AtomicInteger();
   javax.sql.DataSource lost=new org.springframework.jdbc.datasource.DelegatingDataSource(t.f.f.db.source){
    @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
     var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
      try{Object result=method.invoke(connection,args);if(method.getName().equals("commit")&&commits.incrementAndGet()==3)throw new java.sql.SQLException("Reschedule commit ACK lost","08006");return result;}
      catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
     });
    }
   };
   code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.build(lost).reschedule(c));
   assertEquals("SUCCEEDED",t.text("SELECT state FROM order_reschedule_command"));
   var receipt=t.service.reschedule(c);assertEquals(receipt,t.service.reschedule(c));assertEquals(1,t.count("SELECT COUNT(*) FROM order_reschedule_record"));
  }
 }
 @Test void secondRoundTaskInsertionFailureRollsBackEvenACanceledRunningLease() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();t.f.f.db.jdbc.update("UPDATE async_task SET execute_at=UTC_TIMESTAMP(3) WHERE task_type='ORDER_AUTO_CONFIRM'");
   var repository=new JdbcAsyncTaskRepository(t.f.f.db.source,IDS::incrementAndGet,Set.of("ORDER_AUTO_CONFIRM"));
   var lease=repository.claim("rollback-qa",Duration.ofMinutes(1)).orElseThrow();
   t.f.f.db.jdbc.execute("CREATE TRIGGER qa_task_insert BEFORE INSERT ON async_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected new task'");
   code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.service.reschedule(t.command(o)));
   assertTrue(repository.heartbeat(lease,Duration.ofMinutes(1)));assertEquals(0,t.count("SELECT COUNT(*) FROM async_task_attempt WHERE finished_at IS NOT NULL"));
   assertEquals(0,t.count("SELECT COUNT(*) FROM qa_verification_fence"));assertEquals(0,t.count("SELECT COUNT(*) FROM schedule_reservation_change"));
  }
 }
 @Test void tamperedCurrentReservationCannotBeConfirmedOrAuthorizeSecondRoundRefund() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();t.service.reschedule(t.command(o));
   t.f.f.db.jdbc.update("UPDATE schedule_reservation_claim SET start_at=TIMESTAMPADD(MINUTE,1,start_at)");
   var m=t.f.command(o,"REJECT");
   code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.f.service.decide(new MerchantOrderCommandApi.Command(m.context(),o,1,"REJECT",m.reasonCode(),m.reasonText(),null)));
   assertEquals(0,t.count("SELECT COUNT(*) FROM refund_order"));
  }
 }
 @Test void beforeStartBoundaryAndFailedKeyBindingRemainEnforced() throws Exception {
  try(var t=new T()){
   String o=t.f.paid();var c=t.command(o);
   var past=OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0).minusMinutes(1);
   code("ORDER_RESCHEDULE_AFTER_START",()->t.service.reschedule(new Command(context("710100"),o,c.expectedOrderVersion(),past,past.plusMinutes(90),null,null,"710500",null,null)));
   t.f.f.db.jdbc.update("UPDATE pet_order SET appointment_start_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
   code("ORDER_RESCHEDULE_AFTER_START",()->t.service.reschedule(c));
   assertEquals(0,t.count("SELECT reschedule_count FROM pet_order"));assertEquals(2,t.count("SELECT COUNT(*) FROM order_reschedule_command WHERE state='RESERVED'"));
   var changed=new Command(c.context(),o,c.expectedOrderVersion(),START.plusMinutes(1),START.plusMinutes(91),null,null,"710500",null,null);
   code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->t.service.reschedule(changed));
  }
 }
 static void code(String c,org.junit.jupiter.api.function.Executable work){assertEquals(c,assertThrows(ApiException.class,work).code());}
 static CommandContext context(String user){return new CommandContext(UUID.randomUUID().toString(),"reschedule-qa",OperatorType.USER,user,"MINIAPP");}
 static class T implements AutoCloseable {
  final MerchantOrderAcceptanceTest.F f=new MerchantOrderAcceptanceTest.F();
  final OrderRescheduleService service;
  T() throws Exception {
   f.f.db.jdbc.execute("CREATE TABLE qa_verification_fence(id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,reschedule_id BIGINT NOT NULL UNIQUE)");
   service=build(f.f.db.source);
  }
  OrderRescheduleService build(javax.sql.DataSource source) {
   var guard=new ScheduleCapacityGuardApiImpl(source);var facts=new ScheduleProtectionFactsApiImpl(source,guard);
   var staff=new MerchantCurrentStaffFactsApiImpl(source,guard);var orders=new OrderProtectionFactsApiImpl(source,guard,facts,staff,Clock.systemUTC());
   var proof=new ScheduleCapacityProofApiImpl(source,guard,facts,staff,orders,Clock.systemUTC(),10_000);
   var swap=new ReservationSwapApiImpl(source,IDS::incrementAndGet,guard,facts,proof,orders,new OrderRescheduleCommitApiImpl(source,guard));
   VerificationRescheduleFenceApi verification=(o,r,s,change,at,c,transactionSource)->{
    assertSame(source,transactionSource);assertTrue(TransactionSynchronizationManager.hasResource(source));guard.requireHeld(s,source);
    long id=IDS.incrementAndGet();new org.springframework.jdbc.core.JdbcTemplate(source).update("INSERT INTO qa_verification_fence(id,order_id,reschedule_id) VALUES(?,?,?)",id,Long.parseLong(o),Long.parseLong(change));
    return new VerificationRescheduleFenceApi.Fence(Long.toString(id),o,change);
   };
   return new OrderRescheduleService(source,IDS::incrementAndGet,guard,new com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl(source,guard),new RefundOrderFactsApiImpl(source,guard),
    new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,facts,new OrderPaymentFactsApiImpl(source,guard)),swap,verification,new com.petplatform.event.core.TransactionalOutboxPublisher(source,IDS::incrementAndGet,new com.fasterxml.jackson.databind.ObjectMapper()),
    new MerchantOrderAesProtection(new byte[32]),u->{if(!f.sessionActive.get())throw new ApiException(CommonApiCodes.UNAUTHORIZED,"QA revoked");},new com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl(source,guard));
  }
  void assign(String o){
   f.f.db.jdbc.update("UPDATE pet_order SET service_staff_id=710303 WHERE id=?",Long.parseLong(o));
   f.f.db.jdbc.update("INSERT INTO order_staff_assignment(id,order_id,staff_id,assigned_by_type,is_current,assigned_at,version) VALUES(?,?,710303,'MERCHANT',1,UTC_TIMESTAMP(3),0)",IDS.incrementAndGet(),Long.parseLong(o));
  }
  void window(long id,String kind,String start,String end){f.f.db.jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,start_at,end_at,configured_capacity,status,window_kind,created_at,updated_at) VALUES(?,710301,710302,710402,?,?,1,'OPEN',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,start,end,kind);}
  String pickupBook(OffsetDateTime pickup,OffsetDateTime returning){return f.f.book(new com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand(context("710100"),"710302","710402","710200","PICKUP_DELIVERY",null,null,pickup,returning,null,"710501","710502",null,null,"QA service address")).orderId();}
  String pickupPaid()throws Exception{
   String o=pickupBook(OffsetDateTime.parse("2030-01-01T09:00Z"),OffsetDateTime.parse("2030-01-01T11:30Z"));
   var p=f.f.prepare(o,UUID.randomUUID().toString());var n=f.f.notice(p,"SUCCESS","QA_PICKUP_"+IDS.incrementAndGet(),p.amount(),p.amount());
   f.f.notification.receive(n.headers(),n.body());f.f.result.consume(AutoConfirmTaskPreparationAcceptanceTest.event(f.f));return o;
  }
  Command command(String o){return new Command(context("710100"),o,text("SELECT CAST(version AS CHAR) FROM pet_order WHERE id="+o),START,START.plusMinutes(90),null,null,"710500",null,null);}
  String text(String sql){return f.f.text(sql);}long count(String sql){return f.f.count(sql);}
  OffsetDateTime deadline(){return f.f.db.jdbc.queryForObject("SELECT confirm_deadline FROM pet_order",LocalDateTime.class).atOffset(ZoneOffset.UTC);}
  public void close(){f.close();}
 }
}
