package com.petplatform.boot.booking;
import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.boot.booking.VerificationCredentialAcceptanceTest.*;
import com.petplatform.verification.api.command.VerificationCompletionApi.*;
import com.petplatform.verification.biz.application.VerificationCompletionService;
import org.junit.jupiter.api.Test;
import com.petplatform.common.*;
import java.util.*;
import java.util.concurrent.*;
class VerificationCompletionAcceptanceTest {
 @Test void ownerCompletesOnceWithTruthfulIdentityAndOneOrderEvent(){try(var t=new T()){
  String order=t.ready();var code=t.issue(order,"INITIAL");var command=new Command(ctx("710300"),order,"710302",code.code(),code.credentialVersion(),true);
  var service=build(t);var result=assertDoesNotThrow(()->service.verify(command));
  assertEquals("VERIFIED",result.resultCode());assertEquals(result,service.verify(command));
  assertEquals("COMPLETED",t.text("SELECT order_stage FROM pet_order"));
  assertEquals("VERIFIED",t.text("SELECT verification_status FROM pet_order"));
  assertEquals(1,t.count("SELECT COUNT(*) FROM verification_record WHERE operator_type='USER' AND operator_id=710300 AND membership_kind='OWNER' AND operator_staff_id IS NULL"));
  assertEquals(1,t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'"));
 }}
 @Test void currentAftersaleInvalidatesWithOrderWhileHistoryIsPreserved(){try(var t=new T()){
  String o=t.ready();seed(t,o,"PENDING",true);var c=t.issue(o,"INITIAL");var result=build(t).verify(command(o,c));
  assertEquals("INVALIDATED",t.text("SELECT status FROM aftersale_case WHERE id=8801"));assertEquals(0,t.count("SELECT active_flag FROM aftersale_case WHERE id=8801"));
  assertEquals(1,t.count("SELECT version FROM aftersale_case WHERE id=8801"));assertEquals("INVALIDATED",t.text("SELECT aftersale_status FROM pet_order"));
  assertEquals(8801,t.count("SELECT current_aftersale_id FROM pet_order"));assertEquals(1,t.count("SELECT COUNT(*) FROM aftersale_status_log WHERE event_type='ORDER_VERIFIED'"));
  assertEquals(1,t.count("SELECT COUNT(*) FROM aftersale_verification_proof WHERE verification_id="+result.verificationId()));
 }}
 @Test void missingMismatchedAndOrphanAftersalesFailClosed(){for(String mutation:List.of("missing","orphan","merchant","stage","unknown"))try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");seed(t,o,"PENDING",true);
  switch(mutation){case "missing"->t.sql("DELETE FROM aftersale_case");case "orphan"->t.sql("UPDATE pet_order SET current_aftersale_id=NULL");case "merchant"->t.sql("UPDATE aftersale_case SET merchant_id=999");case "stage"->t.sql("UPDATE aftersale_case SET source_stage='VERIFIED'");case "unknown"->t.sql("UPDATE aftersale_case SET status='UNKNOWN'");}
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->build(t).verify(command(o,c)));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_record"));assertEquals("PENDING_SERVICE",t.text("SELECT order_stage FROM pet_order"));
 }}
 @Test void endedAftersaleHistoryIsNotRewritten(){try(var t=new T()){
  String o=t.ready();seed(t,o,"WITHDRAWN",false);var c=t.issue(o,"INITIAL");build(t).verify(command(o,c));
  assertEquals("WITHDRAWN",t.text("SELECT status FROM aftersale_case"));assertEquals(0,t.count("SELECT version FROM aftersale_case"));assertEquals(0,t.count("SELECT COUNT(*) FROM aftersale_status_log"));
 }}
 @Test void negativeResultsCommitSharedRiskAndReplayNeverExtendsLock(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var v=build(t);
  assertEquals("VERIFICATION_CODE_INVALID",t.check(o,"BAD1").resultCode());
  var bad=new Command(ctx("710300"),o,"710302","BAD2",c.credentialVersion(),true);assertEquals("VERIFICATION_CODE_INVALID",v.verify(bad).resultCode());assertEquals(v.verify(bad),v.verify(bad));
  var last=new Command(ctx("710300"),o,"710302","BAD3",c.credentialVersion(),true);assertEquals("VERIFICATION_RISK_LOCKED",v.verify(last).resultCode());String until=t.text("SELECT CAST(locked_until AS CHAR) FROM verification_credential_state");
  assertEquals("VERIFICATION_RISK_LOCKED",v.verify(command(o,c)).resultCode());assertEquals(until,t.text("SELECT CAST(locked_until AS CHAR) FROM verification_credential_state"));assertEquals(3,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt WHERE counts_failure=1"));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_record"));
 }}
 @Test void confirmationVersionCrossStoreAndRevocationDoNotCountCodeFailures(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var v=build(t);
  code(CommonApiCodes.INVALID_ARGUMENT,()->v.verify(new Command(ctx("710300"),o,"710302",c.code(),c.credentialVersion(),false)));
  code(CommonApiCodes.CONFLICT,()->v.verify(new Command(ctx("710300"),o,"710302",c.code(),"0",true)));
  code("VERIFICATION_STORE_MISMATCH",()->v.verify(new Command(ctx("710300"),o,"710999",c.code(),c.credentialVersion(),true)));
  var command=command(o,c);v.verify(command);t.r.f.sessionActive.set(false);code(CommonApiCodes.UNAUTHORIZED,()->v.verify(command));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_risk_attempt WHERE counts_failure=1"));
 }}
 @Test void duplicateAndCompetingRequestsHaveExactlyOneSuccess()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
  String o=t.ready();var c=t.issue(o,"INITIAL");var command=command(o,c);var v=build(t);var start=new CountDownLatch(1);
  var a=pool.submit(()->{start.await();return v.verify(command);});var b=pool.submit(()->{start.await();return v.verify(command);});start.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
  code("VERIFICATION_ALREADY_DONE",()->v.verify(command(o,c)));assertEquals(1,t.count("SELECT COUNT(*) FROM verification_record"));assertEquals(1,t.count("SELECT COUNT(*) FROM verification_attempt"));
 }}
 @Test void everyCompletionWriteFailureRollsBackAndPreservesBinding(){try(var t=new T()){
  String o=t.ready();seed(t,o,"PROCESSING",true);var c=t.issue(o,"INITIAL");var v=build(t);var command=command(o,c);
  for(String target:List.of("verification_attempt:INSERT","verification_record:INSERT","verification_credential:UPDATE","verification_credential_state:UPDATE","aftersale_case:UPDATE","aftersale_status_log:INSERT","aftersale_verification_proof:INSERT","pet_order:UPDATE","order_status_log:INSERT","order_verification_commit:INSERT","integration_event_outbox:INSERT","verification_credential_command:UPDATE")){
   String[] parts=target.split(":");t.sql("CREATE TRIGGER qa_complete_fail BEFORE "+parts[1]+" ON "+parts[0]+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA completion fault'");
   code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->v.verify(command));t.sql("DROP TRIGGER qa_complete_fail");
   assertEquals("PENDING_SERVICE",t.text("SELECT order_stage FROM pet_order"));assertEquals("PROCESSING",t.text("SELECT status FROM aftersale_case"));assertEquals("ACTIVE",t.read(o).status());assertEquals(0,t.count("SELECT COUNT(*) FROM verification_record"));
  }
  code(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,()->v.verify(new Command(command.context(),o,"710302","CHANGED",c.credentialVersion(),true)));assertEquals("VERIFIED",v.verify(command).resultCode());
 }}

 @Test void verifyCapabilityCannotEscapeOrCommitAnIsolatedAftersale(){try(var t=new T()){
  String o=t.ready();seed(t,o,"WAITING_SUPPLEMENT",true);var c=t.issue(o,"INITIAL");var components=components(t);var source=t.r.f.f.db.source;
  var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));tx.setIsolationLevel(2);
  var token=new java.util.concurrent.atomic.AtomicReference<String>();
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));var p=components.orders().acquire(o,"710302",Long.toString(IDS.incrementAndGet()),ctx("710300"),source);token.set(p.token());
   components.aftersales().invalidateCurrent(p.token(),o,"710302",Long.toString(IDS.incrementAndGet()),java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0),source);
  }));
  assertEquals("WAITING_SUPPLEMENT",t.text("SELECT status FROM aftersale_case"));assertEquals(0,t.count("SELECT COUNT(*) FROM aftersale_verification_proof"));
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));components.orders().requirePending(token.get(),o,"710302",source);}));
  assertEquals("VERIFIED",components.service().verify(command(o,c)).resultCode());
 }}
 @Test void consumedCredentialAndOrderCannotCommitWithoutVerificationRecord(){try(var t=new T()){
  String o=t.ready();seed(t,o,"PENDING",true);var c=t.issue(o,"INITIAL");var components=components(t);var source=t.r.f.f.db.source;
  var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));tx.setIsolationLevel(2);
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));var p=components.orders().acquire(o,"710302",Long.toString(IDS.incrementAndGet()),ctx("710300"),source);String v=Long.toString(IDS.incrementAndGet());var at=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
   components.aftersales().invalidateCurrent(p.token(),o,"710302",v,at,source);components.orders().markVerified(p.token(),o,"710302",v,c.credentialId(),Long.toString(IDS.incrementAndGet()),at,source);
  }));
  assertEquals("PENDING_SERVICE",t.text("SELECT order_stage FROM pet_order"));assertEquals("PENDING",t.text("SELECT status FROM aftersale_case"));assertEquals(0,t.count("SELECT COUNT(*) FROM order_verification_commit"));
 }}
 @Test void completionCommitAckLossReturnsOriginalReceiptOnRestart(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var command=command(o,c);var commits=new java.util.concurrent.atomic.AtomicInteger();
  javax.sql.DataSource lost=new org.springframework.jdbc.datasource.DelegatingDataSource(t.r.f.f.db.source){
   @Override public java.sql.Connection getConnection()throws java.sql.SQLException{var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
    try{Object result=method.invoke(connection,args);if(method.getName().equals("commit")&&commits.incrementAndGet()==3)throw new java.sql.SQLException("QA committed ACK loss","08006");return result;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
  };
  var guard=new com.petplatform.schedule.biz.apiimpl.ScheduleCapacityGuardApiImpl(lost);var kernel=VerificationCredentialAcceptanceTest.build(t.r.f,lost,guard);var service=components(t,lost,guard,kernel).service();
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->service.verify(command));var replay=build(t).verify(command);assertEquals("VERIFIED",replay.resultCode());assertEquals(replay,build(t).verify(command));assertEquals(1,t.count("SELECT COUNT(*) FROM verification_record"));assertEquals(1,t.count("SELECT COUNT(*) FROM integration_event_outbox WHERE event_type='OrderVerifiedEvent.v2'"));
 }}
 @Test void requestUuidIsScopedByRealActorInsteadOfGlobalAttemptIndex(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var v=build(t);var first=new Command(ctx("710300"),o,"710302","BAD",c.credentialVersion(),true);assertEquals("VERIFICATION_CODE_INVALID",v.verify(first).resultCode());
  t.sql("UPDATE merchant SET owner_user_id=710101");var second=new Command(new CommandContext(first.context().requestId(),"qa",OperatorType.USER,"710101","MINIAPP"),o,"710302","BAD",c.credentialVersion(),true);
  assertEquals("VERIFICATION_CODE_INVALID",v.verify(second).resultCode());assertEquals(2,t.count("SELECT COUNT(*) FROM verification_attempt"));assertThrows(ApiException.class,()->v.verify(first));
 }}
 @Test void expiredCodesAndActualRefundRowsCannotComplete(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");t.expire();assertEquals("VERIFICATION_CODE_EXPIRED",build(t).verify(command(o,c)).resultCode());
  var fresh=t.issue(o,"AUTO");t.r.f.f.db.jdbc.update("INSERT INTO refund_order(id,refund_no,order_id,refund_type,source_type,refund_amount,refund_ratio,status,initiator_type,created_at,updated_at) VALUES(99,99,?,'FULL','PRESTART_AUTO',128,1,'FAILED','SYSTEM',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",Long.parseLong(o));
  code("VERIFICATION_BLOCKED_BY_REFUND",()->build(t).verify(command(o,fresh)));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_record"));
 }}
 @Test void autoConfirmationAndRescheduledPickupCanComplete()throws Exception{for(boolean pickup:List.of(false,true))try(var t=new T()){
  String o;if(pickup){o=t.r.pickupPaid();t.r.assign(o);t.confirm(o,0);t.issue(o,"INITIAL");
   t.r.window(710503,"PICKUP","2030-01-01 09:40:00","2030-01-01 10:15:00");t.r.window(710504,"RETURN","2030-01-01 12:30:00","2030-01-01 13:20:00");
   t.r.service.reschedule(new com.petplatform.order.api.command.OrderRescheduleApi.Command(ctx("710100"),o,t.text("SELECT CAST(version AS CHAR) FROM pet_order"),null,null,java.time.OffsetDateTime.parse("2030-01-01T09:40Z"),java.time.OffsetDateTime.parse("2030-01-01T12:30Z"),null,"710503","710504"));t.confirm(o,1);
  }else{o=t.r.f.paid(true);t.r.f.auto().autoConfirm(t.r.f.autoCommand(o));}
  assertEquals("VERIFIED",build(t).verify(command(o,t.issue(o,"INITIAL"))).resultCode());
 }}
 @Test void realCurrentSessionOwnerAdapterRejectsForgeryFrozenLogoutAndOwnerRevocation()throws Exception{try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var source=t.r.f.f.db.source;var guard=t.r.f.f.guard;
  // Real resolver + MySQL account/MER facts. Only the volatile cache is a local fixture;
  // the existing CAuthHttpTest separately exercises real Redis and login on CI.
  var cache=new SessionCache();var auth=new com.petplatform.user.biz.application.UserAuthService(source,IDS::incrementAndGet,java.time.Clock.systemUTC(),new com.petplatform.user.biz.application.WechatSessionProvider(){
   public WechatIdentity exchangeIdentity(String code){throw new ProofRejected();}public String exchangePhone(String code){throw new ProofRejected();}
  },cache,new com.petplatform.user.biz.application.UserAuthService.MiniAuthPolicy(300,3600,60,10,5));
  String token=UUID.randomUUID().toString();String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  cache.put("session:"+hash,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("userId",710300L,"sessionId",9001L,"expiresAtMs",System.currentTimeMillis()+60000)),java.time.Duration.ofMinutes(1));
  var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
  try{
   var owner=new com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl(source,guard);var authority=com.petplatform.boot.config.VerificationAuthorityTestSupport.authority(auth,owner);
   var facts=new com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl(source,guard);var confirmed=new com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,facts,new com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl(source,guard));
   var orderFacts=new com.petplatform.order.biz.apiimpl.OrderVerificationCredentialFactsApiImpl(source,guard,new com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl(source,guard),new com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl(source,guard),confirmed,facts,owner);
   var kernel=new com.petplatform.verification.biz.application.VerificationCredentialService(source,IDS::incrementAndGet,guard,orderFacts,new com.petplatform.verification.biz.application.CredentialProtection("qa-v1",Map.of("qa-v1",new byte[32]),Map.of("qa-v1",new byte[32])),u->{throw new AssertionError("Consumer issuer is not under test");},authority,new com.petplatform.event.core.TransactionalOutboxPublisher(source,IDS::incrementAndGet,new com.fasterxml.jackson.databind.ObjectMapper()));
   var service=components(t,source,guard,kernel).service();
   code(CommonApiCodes.FORBIDDEN,()->service.verify(new Command(ctx("710101"),o,"710302",c.code(),c.credentialVersion(),true)));
   t.sql("UPDATE user_account SET status='FROZEN' WHERE id=710300");code(CommonApiCodes.FORBIDDEN,()->service.verify(command(o,c)));t.sql("UPDATE user_account SET status='ACTIVE' WHERE id=710300");
   var command=command(o,c);assertEquals("VERIFIED",service.verify(command).resultCode());
   t.sql("UPDATE merchant SET owner_user_id=710101");assertThrows(ApiException.class,()->service.verify(command));t.sql("UPDATE merchant SET owner_user_id=710300");auth.logout(UUID.randomUUID().toString(),token);code(CommonApiCodes.UNAUTHORIZED,()->service.verify(command));
  }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 }}
 static class SessionCache implements com.petplatform.user.biz.infrastructure.provider.MiniAuthVolatileStore {
  private final Map<String,String> values=new HashMap<>();public boolean putIfAbsent(String k,String v,java.time.Duration ttl){return values.putIfAbsent(k,v)==null;}public void put(String k,String v,java.time.Duration ttl){values.put(k,v);}public Optional<String> get(String k){return Optional.ofNullable(values.get(k));}public void delete(String k){values.remove(k);}public long incrementWindow(String k,java.time.Duration ttl){throw new UnsupportedOperationException("No login in this session fixture");}public void verifyVolatileConfiguration(){}public void close(){values.clear();}
 }

 @Test void legacyIdentityMigrationRefusesUnmappedRowsBeforeAlteringSchema()throws Exception{try(var db=new BookingCreateAcceptanceTest.Database(false)){
  db.jdbc.update("INSERT INTO verification_record(id,verification_no,order_id,store_id,operator_staff_id,verify_method,request_id,verified_at,created_at) VALUES(1,1,2,3,4,'SCAN','legacy',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
  assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->db.script("48-Verification-Completion-Schema-v0.1.sql"));
  assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM verification_record WHERE operator_staff_id=4",Integer.class));
  assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='verification_record' AND column_name='operator_type'",Integer.class));
 }}
 @Test void differentKeysCompeteForOneFinalCompletion()throws Exception{try(var t=new T();var pool=Executors.newFixedThreadPool(2)){
  String o=t.ready();var c=t.issue(o,"INITIAL");var v=build(t);var start=new CountDownLatch(1);var results=new ArrayList<Future<Object>>();
  for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();try{return v.verify(command(o,c));}catch(ApiException e){return e.code();}}));start.countDown();int success=0,done=0;
  for(var r:results){Object value=r.get(20,TimeUnit.SECONDS);if(value instanceof Receipt)success++;else {assertEquals("VERIFICATION_ALREADY_DONE",value);done++;}}
  assertEquals(1,success);assertEquals(1,done);assertEquals(1,t.count("SELECT COUNT(*) FROM verification_record"));assertEquals(1,t.count("SELECT COUNT(*) FROM order_verification_commit"));
 }}
 @Test void readonlyWrongSourceAndForgedTokensCannotAuthorizeAnAftersale(){try(var t=new T()){
  String o=t.ready();var components=components(t);var source=t.r.f.f.db.source;var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));tx.setIsolationLevel(2);
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));components.orders().acquire(o,"710302","99001",ctx("710300"),new org.springframework.jdbc.datasource.DelegatingDataSource(source));}));
  code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->{t.r.f.f.guard.acquire(List.of("710302"),new QueryContext("qa",OperatorType.SYSTEM,null));components.aftersales().invalidateCurrent(UUID.randomUUID().toString(),o,"710302","99002",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0),source);}));
  tx.setReadOnly(true);code(CommonApiCodes.DEPENDENCY_UNAVAILABLE,()->tx.executeWithoutResult(s->components.orders().acquire(o,"710302","99003",ctx("710300"),source)));
  assertEquals(0,t.count("SELECT COUNT(*) FROM aftersale_verification_proof"));assertEquals(0,t.count("SELECT COUNT(*) FROM order_operation_guard"));
 }}
 @Test void malformedStoreIdsAreArgumentErrorsWithoutDurableAdmission(){try(var t=new T()){
  String o=t.ready();var c=t.issue(o,"INITIAL");var v=build(t);
  for(String store:Arrays.asList(null,"0","01","not-an-id"))code(CommonApiCodes.INVALID_ARGUMENT,()->v.verify(new Command(ctx("710300"),o,store,c.code(),c.credentialVersion(),true)));
  assertEquals(0,t.count("SELECT COUNT(*) FROM verification_credential_command WHERE command_namespace=CAST('verification.complete' AS BINARY)"));assertEquals(0,t.count("SELECT COUNT(*) FROM verification_attempt"));
 }}
 static VerificationCompletionService build(T t){return components(t).service();}
 record Components(VerificationCompletionService service,com.petplatform.order.api.command.OrderVerificationCommitApi orders,com.petplatform.aftersale.api.command.AfterSaleVerificationApi aftersales) {}
 static Components components(T t){return components(t,t.r.f.f.db.source,t.r.f.f.guard,t.v);}
 static Components components(T t,javax.sql.DataSource source,com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi guard,com.petplatform.verification.biz.application.VerificationCredentialService credentials){
  var facts=new com.petplatform.schedule.biz.apiimpl.ScheduleProtectionFactsApiImpl(source,guard);
  var confirmed=new com.petplatform.schedule.biz.apiimpl.ReservationConfirmApiImpl(source,IDS::incrementAndGet,guard,facts,new com.petplatform.order.biz.apiimpl.OrderPaymentFactsApiImpl(source,guard));
  var orderFacts=new com.petplatform.order.biz.apiimpl.OrderVerificationCredentialFactsApiImpl(source,guard,new com.petplatform.payment.biz.apiimpl.PaymentSuccessFactsApiImpl(source,guard),new com.petplatform.refund.biz.apiimpl.RefundOrderFactsApiImpl(source,guard),confirmed,facts,new com.petplatform.merchant.biz.apiimpl.MerchantOrderAuthorityApiImpl(source,guard));
  var outbox=new com.petplatform.event.core.TransactionalOutboxPublisher(source,IDS::incrementAndGet,new com.fasterxml.jackson.databind.ObjectMapper());
  var verification=new java.util.concurrent.atomic.AtomicReference<VerificationCompletionService>();var afs=new java.util.concurrent.atomic.AtomicReference<com.petplatform.aftersale.api.command.AfterSaleVerificationApi>();
  var orders=new com.petplatform.order.biz.apiimpl.OrderVerificationCommitApiImpl(source,guard,orderFacts,IDS::incrementAndGet,outbox,verification::get,afs::get);
  var aftersale=new com.petplatform.aftersale.biz.apiimpl.AfterSaleVerificationApiImpl(source,guard,orders,IDS::incrementAndGet);afs.set(aftersale);
  var service=new VerificationCompletionService(credentials,orders,aftersale);verification.set(service);return new Components(service,orders,aftersale);
 }
 static Command command(String o,com.petplatform.verification.api.command.VerificationCredentialApi.Receipt c){return new Command(ctx("710300"),o,"710302",c.code(),c.credentialVersion(),true);}
 static void seed(T t,String order,String status,boolean active){
  t.r.f.f.db.jdbc.update("INSERT INTO aftersale_case(id,aftersale_no,order_id,user_id,merchant_id,store_id,status,source_stage,description,active_flag,created_at) SELECT 8801,8801,id,user_id,merchant_id,store_id,?,'UNVERIFIED_POST_START','QA current case fixture',?,UTC_TIMESTAMP(3) FROM pet_order WHERE id=?",status,active?1:0,Long.parseLong(order));
  t.sql("UPDATE pet_order SET current_aftersale_id=8801");
 }
}
