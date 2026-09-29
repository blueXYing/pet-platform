package com.petplatform.boot.booking;
import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.common.*;
import com.petplatform.verification.api.command.VerificationCredentialApi.*;
import com.petplatform.verification.biz.application.*;
import com.petplatform.order.biz.apiimpl.*;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl;
import com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Real VER/ORDER/SCH/PAYMENT/REFUND persistence. Merchant attempt authorization is an explicit
 * QA adapter: this does not assert delivery of the missing merchant membership contract. */
class VerificationCredentialAcceptanceTest {
 static final AtomicLong IDS=new AtomicLong(9_080_000_000_000_000L);
 static VerificationCredentialService build(MerchantOrderAcceptanceTest.F f,DataSource source){
  return build(f,source,source==f.f.db.source?f.f.guard:new ScheduleCapacityGuardApiImpl(source));
 }
 static VerificationCredentialService build(MerchantOrderAcceptanceTest.F f,DataSource source,com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi guard){
  var facts=new ScheduleProtectionFactsApiImpl(source,guard);
  var confirmed=new ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,facts,new OrderPaymentFactsApiImpl(source,guard));
  var owner=new MerchantOrderAuthorityApiImpl(source,guard);
  var orders=new OrderVerificationCredentialFactsApiImpl(source,guard,new PaymentSuccessFactsApiImpl(source,guard),new RefundOrderFactsApiImpl(source,guard),confirmed,facts,owner);
  var keys=new CredentialProtection("qa-v1",Map.of("qa-v1",new byte[32]),Map.of("qa-v1",new byte[32]));
  return new VerificationCredentialService(source,IDS::incrementAndGet,guard,orders,keys,
   user->{if(!f.sessionActive.get())throw new ApiException(CommonApiCodes.UNAUTHORIZED,"QA revoked");},
   (context,merchant,store)->{if(!f.sessionActive.get())throw new ApiException(CommonApiCodes.UNAUTHORIZED,"QA revoked");owner.requireOwner(merchant,store,new QueryContext(context.traceId(),context.operatorType(),context.operatorId()));},
   new com.petplatform.event.core.TransactionalOutboxPublisher(source,IDS::incrementAndGet,new com.fasterxml.jackson.databind.ObjectMapper()));
 }
 @Test void realPaymentConfirmationReadDoesNotIssueAndInitialCodeValidates(){try(var t=new T()){
  String o=t.ready();assertEquals("NONE",t.read(o).status());assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_state"));
  var c=t.issue(o,"INITIAL");assertTrue(c.code().matches("[0-9A-Z]{32}"));assertEquals(OffsetDateTime.parse(c.issuedAt()).plusMinutes(5),OffsetDateTime.parse(c.expiresAt()));
  assertEquals("VALID",t.check(o,c.code()).resultCode());assertEquals(c.code(),t.read(o).code());assertFalse(c.toString().contains(c.code()));
  assertEquals(1,t.count("SELECT COUNT(*) FROM verification_credential"));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_record"));
  assertFalse(t.text("SELECT GROUP_CONCAT(payload) FROM integration_event_outbox").contains(c.code()));
 }}
 @Test void refreshRetiresOldCodeAndRejectsInitialAutoAndOldVersionBypass(){try(var t=new T()){
  String o=t.ready();var first=t.issue(o,"INITIAL");code(CommonApiCodes.CONFLICT,()->t.issue(o,"INITIAL"));code(CommonApiCodes.CONFLICT,()->t.issue(o,"AUTO"));
  var next=t.issue(o,"MANUAL");assertNotEquals(first.code(),next.code());assertEquals("VERIFICATION_CODE_INVALID",t.check(o,first.code()).resultCode());assertEquals("VALID",t.check(o,next.code()).resultCode());
  code(CommonApiCodes.CONFLICT,()->t.v.issue(new Issue(ctx("710100"),o,first.credentialVersion(),"MANUAL")));
 }}
 @Test void manualSixthRefreshRejectedAndWindowRecovers(){try(var t=new T()){
  String o=t.ready();t.issue(o,"INITIAL");for(int i=0;i<5;i++)t.issue(o,"MANUAL");code(CommonApiCodes.RATE_LIMITED,()->t.issue(o,"MANUAL"));
  t.sql("UPDATE verification_credential_refresh SET committed_at=UTC_TIMESTAMP(3)-INTERVAL 61 SECOND");assertNotNull(t.issue(o,"MANUAL"));
 }}
 @Test void thirdIndependentFailureLocksAndReplayDoesNotCountOrExtend(){try(var t=new T()){
  String o=t.ready();var first=t.issue(o,"INITIAL");var c=new Check(ctx("710300"),o,"710302","BADCODE");
  assertEquals("VERIFICATION_CODE_INVALID",t.v.check(c).resultCode());assertEquals(t.v.check(c),t.v.check(c));
  assertEquals("VERIFICATION_CODE_INVALID",t.check(o,"BADCODE2").resultCode());assertEquals("VERIFICATION_RISK_LOCKED",t.check(o,"BADCODE3").resultCode());
  String until=t.text("SELECT CAST(locked_until AS CHAR) FROM verification_credential_state");assertEquals("LOCKED",t.read(o).status());assertNull(t.read(o).code());
  code("VERIFICATION_RISK_LOCKED",()->t.issue(o,"MANUAL"));assertEquals("VERIFICATION_RISK_LOCKED",t.check(o,first.code()).resultCode());
  assertEquals(until,t.text("SELECT CAST(locked_until AS CHAR) FROM verification_credential_state"));assertEquals(3,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt WHERE counts_failure=1"));
  assertEquals(1,t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='VerificationRiskLockedEvent.v1'"));
 }}
 @Test void lockAndFailureSeriesSurviveRefreshRescheduleAndServiceRestart(){try(var t=new T()){
  String o=t.ready();t.issue(o,"INITIAL");t.check(o,"BAD1");t.issue(o,"MANUAL");t.check(o,"BAD2");
  t.r.service.reschedule(t.r.command(o));t.confirm(o,1);t.issue(o,"INITIAL");assertEquals("VERIFICATION_RISK_LOCKED",t.check(o,"BAD3").resultCode());
  assertEquals("LOCKED",build(t.r.f,t.r.f.f.db.source).read(o,q()).status());
 }}
 @Test void lockedOrderCanRescheduleButCannotIssueUntilLockExpires(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");for(int i=0;i<3;i++)t.check(o,"BAD"+i);
  t.r.service.reschedule(t.r.command(o));t.confirm(o,1);assertEquals("LOCKED",t.read(o).status());code("VERIFICATION_RISK_LOCKED",()->t.issue(o,"INITIAL"));
  t.sql("UPDATE verification_credential_state SET locked_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");t.sql("UPDATE verification_credential_risk_attempt SET attempted_at=UTC_TIMESTAMP(3)-INTERVAL 6 MINUTE");
  var fresh=t.issue(o,"INITIAL");assertEquals("VALID",t.check(o,fresh.code()).resultCode());assertEquals("VERIFICATION_CODE_INVALID",t.check(o,c.code()).resultCode());
 }}
 @Test void expiryAutoRotationAndRollingFiveMinuteFailureWindow(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");t.expire();assertEquals("EXPIRED",t.read(o).status());assertEquals("VERIFICATION_CODE_EXPIRED",t.check(o,c.code()).resultCode());
  t.sql("UPDATE verification_credential_risk_attempt SET attempted_at=UTC_TIMESTAMP(3)-INTERVAL 6 MINUTE");var fresh=t.issue(o,"AUTO");
  assertEquals("VERIFICATION_CODE_INVALID",t.check(o,"BAD1").resultCode());assertEquals("VERIFICATION_CODE_INVALID",t.check(o,"BAD2").resultCode());assertEquals("VALID",t.check(o,fresh.code()).resultCode());
 }}
 @Test void issueSameKeyConcurrencyAndLostResponseReturnsSameProtectedReceipt()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
  String o=t.ready();var c=new Issue(ctx("710100"),o,"0","INITIAL");var latch=new CountDownLatch(1);
  var a=pool.submit(()->{latch.await();return t.v.issue(c);});var b=pool.submit(()->{latch.await();return t.v.issue(c);});latch.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
  var old=t.v.issue(c);t.issue(o,"MANUAL");assertEquals(old,t.v.issue(c));assertEquals("VERIFICATION_CODE_INVALID",t.check(o,old.code()).resultCode());
  code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->t.v.issue(new Issue(c.context(),o,"1","MANUAL")));
 }}
 @Test void issueActualCommittedAckLossReplaysWithoutRotation(){try(var t=new T()){
  String o=t.ready();var c=new Issue(ctx("710100"),o,"0","INITIAL");var commits=new AtomicInteger();
  DataSource lost=new org.springframework.jdbc.datasource.DelegatingDataSource(t.r.f.f.db.source){
   @Override public java.sql.Connection getConnection()throws java.sql.SQLException{var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
    try{Object result=method.invoke(connection,args);if(method.getName().equals("commit")&&commits.incrementAndGet()==3)throw new java.sql.SQLException("QA commit ACK lost","08006");return result;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
  };
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->build(t.r.f,lost).issue(c));var receipt=t.v.issue(c);assertEquals(receipt,t.v.issue(c));assertEquals(1,t.count("SELECT COUNT(*) FROM verification_credential"));
 }}
 @Test void codeAndRescheduleRollbackAsOneTransaction(){for(String table:List.of("verification_credential","verification_credential_state","verification_reschedule_fence","order_reschedule_record","integration_event_outbox"))try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");String op=table.equals("verification_credential")||table.equals("verification_credential_state")?"UPDATE":"INSERT";
  t.sql("CREATE TRIGGER qa_vc_fail BEFORE "+op+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA fault'");
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.r.service.reschedule(t.r.command(o)));t.sql("DROP TRIGGER qa_vc_fail");
  assertEquals("VALID",t.check(o,c.code()).resultCode());assertEquals(0,t.count("SELECT COUNT(*) FROM verification_reschedule_fence"));assertEquals(0,t.count("SELECT reschedule_count FROM pet_order"));
 }}
 @Test void issuanceFailuresPreserveOldCodeAndAdmissionBinding(){for(String table:List.of("verification_credential","verification_credential_state","verification_credential_refresh","verification_credential_command"))try(var t=new T()){
  String o=t.ready();var old=t.issue(o,"INITIAL");var c=new Issue(ctx("710100"),o,old.credentialVersion(),"MANUAL");String op=table.equals("verification_credential_state")||table.equals("verification_credential_command")?"UPDATE":"INSERT";
  t.sql("CREATE TRIGGER qa_vc_fail BEFORE "+op+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA fault'");code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.v.issue(c));t.sql("DROP TRIGGER qa_vc_fail");
  assertEquals("VALID",t.check(o,old.code()).resultCode());code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->t.v.issue(new Issue(c.context(),o,old.credentialVersion(),"AUTO")));assertNotNull(t.v.issue(c));
 }}
 @Test void riskOutboxFailureRollsBackThirdAttemptAndLock(){try(var t=new T()){
  String o=t.ready();t.issue(o,"INITIAL");t.check(o,"BAD1");t.check(o,"BAD2");var c=new Check(ctx("710300"),o,"710302","BAD3");
  t.sql("CREATE TRIGGER qa_vc_fail BEFORE INSERT ON integration_event_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA fault'");code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.v.check(c));t.sql("DROP TRIGGER qa_vc_fail");
  assertEquals("ACTIVE",t.read(o).status());assertEquals(2,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt"));assertEquals("VERIFICATION_RISK_LOCKED",t.v.check(c).resultCode());
 }}
 @Test void authorityRevocationAndCrossStoreDoNotCountFailures(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");code(CommonApiCodes.FORBIDDEN,()->t.v.read(o,new QueryContext("qa",OperatorType.USER,"710101")));
  code("VERIFICATION_STORE_MISMATCH",()->t.v.check(new Check(ctx("710300"),o,"710999",c.code())));assertThrows(ApiException.class,()->t.v.check(new Check(ctx("710101"),o,"710302",c.code())));
  t.r.f.sessionActive.set(false);code(CommonApiCodes.UNAUTHORIZED,()->t.v.read(o,q()));code(CommonApiCodes.UNAUTHORIZED,()->t.check(o,c.code()));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt"));
 }}
 @Test void refundCancelAndVerifiedRejectWhileAftersaleAloneDoesNot(){for(String mutation:List.of("CANCELED","VERIFIED","REFUND","AFTERSALE"))try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");
  switch(mutation){case "CANCELED"->t.sql("UPDATE pet_order SET order_stage='CANCELED'");case "VERIFIED"->t.sql("UPDATE pet_order SET order_stage='COMPLETED',verification_status='VERIFIED'");
   case "REFUND"->t.r.f.f.db.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,initiator_type,created_at,updated_at) VALUES(99,99,?,'FULL','PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",Long.parseLong(o));
   case "AFTERSALE"->t.sql("UPDATE pet_order SET current_aftersale_id=42,current_refund_application_id=43");}
  if(mutation.equals("AFTERSALE"))assertEquals("VALID",t.check(o,c.code()).resultCode());else {assertThrows(ApiException.class,()->t.check(o,c.code()));assertThrows(ApiException.class,()->t.read(o));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt"));}
 }}
 @Test void orphanStateCipherAndConfirmationProofFailClosed(){for(String mutation:List.of("DELETE FROM verification_credential_state","UPDATE verification_credential SET code_cipher=X'00'","DELETE FROM order_merchant_decision","UPDATE verification_credential_state SET current_credential_id=NULL"))try(var t=new T()){
  String o=t.ready();t.issue(o,"INITIAL");t.sql(mutation);code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.read(o));
 }}
 @Test void isolatedFenceCannotCommitWithoutOrderCounterpart(){try(var t=new T()){
  String o=t.ready();var old=t.issue(o,"INITIAL");var tx=new TransactionTemplate(new DataSourceTransactionManager(t.r.f.f.db.source));tx.setIsolationLevel(2);
  assertThrows(ApiException.class,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));
   t.v.invalidate(o,t.text("SELECT CAST(reservation_id AS CHAR) FROM pet_order"),"710302",Long.toString(IDS.incrementAndGet()),OffsetDateTime.now(ZoneOffset.UTC).withNano(0),ctx("710100"),t.r.f.f.db.source);}));
  assertEquals("VALID",t.check(o,old.code()).resultCode());assertEquals(0,t.count("SELECT COUNT(*) FROM verification_reschedule_fence"));
 }}
 @Test void refreshAndRescheduleRaceNeverLeavesLiveOldEpoch()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
  String o=t.ready();t.issue(o,"INITIAL");var issue=new Issue(ctx("710100"),o,t.read(o).credentialVersion(),"MANUAL");var change=t.r.command(o);var latch=new CountDownLatch(1);
  var a=pool.submit(()->{latch.await();try{return (Object)t.v.issue(issue);}catch(ApiException e){return e.code();}});var b=pool.submit(()->{latch.await();return t.r.service.reschedule(change);});latch.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
  assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential WHERE epoch=0 AND invalidated_at IS NULL"));t.confirm(o,1);assertEquals("INVALIDATED",t.read(o).status());assertEquals("VALID",t.check(o,t.issue(o,"INITIAL").code()).resultCode());
 }}
 @Test void serviceStartInPastDoesNotDisableCredentialAndMissingClaimsDo(){try(var t=new T()){
  String o=t.ready();
  t.sql("UPDATE pet_order SET appointment_start_at=DATE_SUB(appointment_start_at,INTERVAL 4 YEAR),appointment_end_at=DATE_SUB(appointment_end_at,INTERVAL 4 YEAR)");
  for(String table:List.of("schedule_reservation","schedule_reservation_claim","schedule_availability_window"))t.sql("UPDATE "+table+" SET start_at=DATE_SUB(start_at,INTERVAL 4 YEAR),end_at=DATE_SUB(end_at,INTERVAL 4 YEAR)");
  assertEquals("VALID",t.check(o,t.issue(o,"INITIAL").code()).resultCode());t.sql("DELETE FROM schedule_reservation_claim");code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.read(o));
 }}
 @Test void automaticConfirmationProofCanAuthorizeCredentials()throws Exception{try(var t=new T()){
  String o=t.r.f.paid(true);assertEquals(com.petplatform.order.api.command.OrderAutoConfirmApi.Result.CONFIRMED,t.r.f.auto().autoConfirm(t.r.f.autoCommand(o)));
  assertEquals("VALID",t.check(o,t.issue(o,"INITIAL").code()).resultCode());t.sql("DELETE FROM order_status_log WHERE event_type='ORDER_AUTO_CONFIRMED'");code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.read(o));
 }}
 @Test void concurrentThirdFailureProducesOnePersistentLock()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(3)){
  String o=t.ready();t.issue(o,"INITIAL");var start=new CountDownLatch(1);var tasks=new ArrayList<Future<CheckResult>>();
  for(int i=0;i<3;i++)tasks.add(pool.submit(()->{start.await();return t.check(o,"BAD");}));start.countDown();int locks=0;
  for(var task:tasks)if(task.get(15,TimeUnit.SECONDS).resultCode().equals("VERIFICATION_RISK_LOCKED"))locks++;
  assertEquals(1,locks);assertEquals(3,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt WHERE counts_failure=1"));assertEquals(1,t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='VerificationRiskLockedEvent.v1'"));
 }}
 @Test void missingStateCannotEraseRiskBeforeFirstIssuance(){try(var t=new T()){
  String o=t.ready();for(int i=0;i<3;i++)t.check(o,"BAD"+i);assertEquals("LOCKED",t.read(o).status());
  assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential"));t.sql("DELETE FROM verification_credential_state");
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.read(o));code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->t.v.issue(new Issue(ctx("710100"),o,"0","INITIAL")));
  assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_state"));
 }}
 @Test void concurrentLastManualQuotaCannotCommitSixthRefresh()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
  String o=t.ready();t.issue(o,"INITIAL");for(int i=0;i<4;i++)t.issue(o,"MANUAL");String version=t.read(o).credentialVersion();var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Object>>();
  for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{return t.v.issue(new Issue(ctx("710100"),o,version,"MANUAL"));}catch(ApiException e){return e.code();}}));start.countDown();int receipts=0;
  for(var task:tasks){Object result=task.get(15,TimeUnit.SECONDS);if(result instanceof Receipt)receipts++;else assertEquals(CommonApiCodes.CONFLICT,result);}
  assertEquals(1,receipts);code(CommonApiCodes.RATE_LIMITED,()->t.issue(o,"MANUAL"));assertEquals(5,t.count("SELECT COUNT(*) FROM verification_credential_refresh WHERE refresh_kind='MANUAL'"));
 }}
 @Test void pickupCredentialFollowsBothWholeWindowsAcrossReschedule()throws Exception{try(var t=new T()){
  String o=t.r.pickupPaid();t.r.assign(o);t.confirm(o,0);var old=t.issue(o,"INITIAL");assertEquals("VALID",t.check(o,old.code()).resultCode());
  t.r.window(710503,"PICKUP","2030-01-01 09:40:00","2030-01-01 10:15:00");t.r.window(710504,"RETURN","2030-01-01 12:30:00","2030-01-01 13:20:00");
  t.r.service.reschedule(new com.petplatform.order.api.command.OrderRescheduleApi.Command(ctx("710100"),o,t.text("SELECT CAST(version AS CHAR) FROM pet_order"),null,null,
   OffsetDateTime.parse("2030-01-01T09:40Z"),OffsetDateTime.parse("2030-01-01T12:30Z"),null,"710503","710504"));
  t.confirm(o,1);assertEquals("VERIFICATION_CODE_INVALID",t.check(o,old.code()).resultCode());assertEquals("VALID",t.check(o,t.issue(o,"INITIAL").code()).resultCode());
 }}
 static QueryContext q(){return new QueryContext("credential-qa",OperatorType.USER,"710100");}
 static CommandContext ctx(String user){return new CommandContext(UUID.randomUUID().toString(),"credential-qa",OperatorType.USER,user,"QA");}
 static void code(String expected,org.junit.jupiter.api.function.Executable work){assertEquals(expected,assertThrows(ApiException.class,work).code());}
 static class T implements AutoCloseable {
  final OrderRescheduleAcceptanceTest.T r;final VerificationCredentialService v;
  T(){try{r=new OrderRescheduleAcceptanceTest.T();v=build(r.f,r.f.f.db.source);}catch(Exception e){throw new IllegalStateException(e);}}
  String ready(){try{String o=r.f.paid();confirm(o,0);return o;}catch(Exception e){throw new IllegalStateException(e);}}
  void confirm(String o,int round){var c=r.f.command(o,"CONFIRM");r.f.service.decide(new com.petplatform.order.api.command.MerchantOrderCommandApi.Command(c.context(),o,round,"CONFIRM",null,null,null));}
  View read(String o){return v.read(o,q());}Receipt issue(String o,String kind){return v.issue(new Issue(ctx("710100"),o,read(o).credentialVersion(),kind));}
  CheckResult check(String o,String code){return v.check(new Check(ctx("710300"),o,"710302",code));}
  void expire(){sql("UPDATE verification_credential SET issued_at=UTC_TIMESTAMP(3)-INTERVAL 6 MINUTE,expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE");}
  void sql(String s){r.f.f.db.jdbc.execute(s);}long count(String s){return r.count(s);}String text(String s){return r.text(s);}public void close(){r.close();}
 }
}
