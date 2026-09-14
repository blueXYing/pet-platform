package com.petplatform.admin.biz.auth;

import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.admin.biz.task.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.admin.biz.auth.AuthTestDatabase.*;

class AdminAuthFailureRecoveryTest {
    AuthTestDatabase db;AdminAuthService service;long owner;
    @TempDir Path directory;
    @BeforeEach void open()throws Exception{db=new AuthTestDatabase();service=db.service();owner=db.bootstrap(service);}
    @AfterEach void close(){if(db!=null)db.close();}
    @Test void lostLoginCommitAckResolvesSameDatabaseResultWithoutAnotherSession(){
        var a=db.attempt(service);var source=new FaultSource(db.source);source.loseCommitAfterSql="INSERT INTO admin_web_session";
        var faulted=db.service(source,db.codec,db.cache);String key=request();var first=db.login(faulted,a,key);
        assertTrue(source.ackLost.get());assertEquals(1,db.count("admin_web_session"));
        assertEquals(first.data(),db.login(service,a,key).data());
        assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_auth_command WHERE namespace='LOGIN' AND state='SUCCEEDED'",Long.class));
    }
    @Test void lostAttemptCreationAckCannotReplayAnonymousSecretsOrCreateAgain(){
        var source=new FaultSource(db.source);source.loseCommitAfterSql="INSERT INTO admin_attempt_creation";
        var faulted=db.service(source,db.codec,db.cache);String key=request();failure(409,()->faulted.createAttempt(key,"127.0.0.1"));
        assertTrue(source.ackLost.get());failure(409,()->service.createAttempt(key,"127.0.0.1"));assertEquals(1,db.count("admin_auth_attempt"));
    }
    @Test void unavailableDatabaseDoesNotReturnCachedGrant(){
        var a=db.attempt(service);String key=request();db.login(service,a,key);
        var source=new FaultSource(db.source);var faulted=db.service(source,db.codec,db.cache);source.offline=true;
        failure(503,()->faulted.attemptResult(a.id(),a.token(),a.cookie(),key));assertEquals(1,db.count("admin_web_session"));
    }
    @Test void successfulDatabaseLoginWithFailedCachePublicationNeverResignsOnRetry(){
        var cache=new FaultCache(db.cache);var faulted=db.service(db.source,db.codec,cache);var a=db.attempt(service);String key=request();cache.failPut=true;
        failure(503,()->db.login(faulted,a,key));assertEquals(1,db.count("admin_web_session"));
        cache.failPut=false;failure(503,()->db.login(faulted,a,key));assertEquals(1,db.count("admin_web_session"));
        assertEquals("SUCCEEDED",db.jdbc.queryForObject("SELECT state FROM admin_auth_command WHERE namespace='LOGIN'",String.class));
    }
    @Test void receiptCacheAndKeyLossFailClosedWhileExistingBearerStillWorks(){
        var a=db.attempt(service);String key=request();String token=db.login(service,a,key).data().get("accessToken").toString();
        var cache=new FaultCache(db.cache);var cacheFault=db.service(db.source,db.codec,cache);cache.failGet=true;
        failure(503,()->cacheFault.attemptResult(a.id(),a.token(),a.cookie(),key));assertNotNull(service.resolveSession(token));
        var absent=new AtomicBoolean();var material=new AdminSecretCodec.Keys(bytes(3),bytes(7));
        var codec=new AdminSecretCodec(new AdminSecretCodec.KeySource(){public String currentId(){return "qa-key";}public AdminSecretCodec.Keys get(String id){if(absent.get())throw AdminAuthFailure.unavailable();return material;}});
        var keyFault=db.service(db.source,codec,db.cache);absent.set(true);
        failure(503,()->keyFault.attemptResult(a.id(),a.token(),a.cookie(),key));assertEquals(1,db.count("admin_web_session"));
    }
    @Test void readResultRechecksAfterCacheReadWhenLogoutOrNewLoginCommits()throws Exception{
        for(boolean replaceByNewLogin:List.of(false,true)){
            var a=db.attempt(service);String loginKey=request();String old=db.login(service,a,loginKey).data().get("accessToken").toString();
            var cacheRead=new CountDownLatch(1);var revocationCommitted=new CountDownLatch(1);var paused=new AtomicBoolean();
            AdminGrantCache blocked=new AdminGrantCache(){
                public void verifyVolatileConfiguration(){db.cache.verifyVolatileConfiguration();}
                public void putIfAbsent(String ref,byte[] value,Duration ttl){db.cache.putIfAbsent(ref,value,ttl);}
                public Optional<byte[]> get(String ref){
                    var value=db.cache.get(ref);
                    if(paused.compareAndSet(false,true)){
                        assertTrue(value.isPresent(),"Read a real encrypted receipt before revocation");cacheRead.countDown();
                        try{if(!revocationCommitted.await(10,TimeUnit.SECONDS))throw new AssertionError("Revocation barrier was not released");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                    }
                    return value;
                }
            };
            var reader=db.service(db.source,db.codec,blocked);
            try(var pool=Executors.newSingleThreadExecutor()){
                try{
                    var pending=pool.submit(()->reader.attemptResult(a.id(),a.token(),a.cookie(),loginKey));
                    assertTrue(cacheRead.await(5,TimeUnit.SECONDS));
                    String replacement=null;
                    if(replaceByNewLogin)replacement=db.loginToken(service);else service.logout(request(),old);
                    failure(401,()->service.resolveSession(old));revocationCommitted.countDown();
                    var failed=assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));
                    assertEquals(401,assertInstanceOf(AdminAuthFailure.class,failed.getCause()).status(),"READ_RESULT must not use a pre-cache transaction snapshot");
                    if(replacement!=null)assertNotNull(service.resolveSession(replacement));
                }finally{revocationCommitted.countDown();}
            }
        }
    }
    @Test void b1UsesDatabasePrecommitAnchorAndCacheNeverContainsPlainTokenOrOverwrites(){
        var a=db.attempt(service);String key=request();var result=db.login(service,a,key);
        var row=db.jdbc.queryForMap("SELECT cache_ref,receipt_window_anchor_at,secret_expires_at,completed_at,result_expires_at FROM admin_auth_command WHERE namespace='LOGIN'");
        assertEquals(60L,db.jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,receipt_window_anchor_at,secret_expires_at) FROM admin_auth_command WHERE namespace='LOGIN'",Long.class));
        assertEquals(row.get("completed_at"),row.get("receipt_window_anchor_at"));
        assertTrue(db.jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,secret_expires_at,result_expires_at) FROM admin_auth_command WHERE namespace='LOGIN'",Long.class)>0);
        String ref=row.get("cache_ref").toString();String stored=db.redis.sync().get(db.prefix+ref);
        assertNotNull(stored);assertFalse(new String(Base64.getDecoder().decode(stored),java.nio.charset.StandardCharsets.ISO_8859_1).contains(result.data().get("accessToken").toString()));
        long ttl=db.redis.sync().pttl(db.prefix+ref);assertTrue(ttl>0&&ttl<=60000);
        byte[] first=db.cache.get(ref).orElseThrow();db.cache.putIfAbsent(ref,first,Duration.ofSeconds(60));
        failure(503,()->db.cache.putIfAbsent(ref,new byte[]{1,2,3},Duration.ofSeconds(60)));assertArrayEquals(first,db.cache.get(ref).orElseThrow());
        assertTrue(db.redis.sync().pttl(db.prefix+ref)<=ttl,"Same-reference replay must not reset TTL");
    }
    @Test void auditIntentFailureRollsBackLoginRatherThanCommittingUnauditedSession(){
        var a=db.attempt(service);var source=new FaultSource(db.source);source.failSql="INSERT INTO admin_audit_intent";
        var faulted=db.service(source,db.codec,db.cache);failure(503,()->db.login(faulted,a,request()));
        assertEquals(0,db.count("admin_web_session"));assertEquals(0L,db.jdbc.queryForObject("SELECT session_generation FROM admin_account WHERE id=?",Long.class,owner));
    }
    @Test void durableAuditSinkFailureKeepsIntentAndAckLossDoesNotDuplicateFrame()throws Exception{
        String token=db.loginToken(service);Path file=directory.resolve("audit.bin");var sink=new FileAdminAuditSink(file);var once=new AtomicBoolean();
        var delivery=new AdminAuditDelivery(db.source,e->{sink.append(e);if(once.compareAndSet(false,true))throw new IllegalStateException("QA sink ACK lost");});
        delivery.deliverPending(100);assertTrue(once.get());assertNotNull(service.resolveSession(token));
        assertTrue(db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_intent WHERE delivery_state='PENDING'",Long.class)>0);
        long firstSize=Files.size(file);db.jdbc.update("UPDATE admin_audit_intent SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE delivery_state='PENDING'");
        new AdminAuditDelivery(db.source,sink).deliverPending(100);
        assertEquals(firstSize,Files.size(file));assertEquals(0L,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_intent WHERE delivery_state='PENDING'",Long.class));
    }
    @Test void controlledRecoveryRequiresMaintenanceAndInvalidatesOldGeneration(){
        String old=db.loginToken(service);String prior=service.resolveSession(old).permissions().authzVersion();
        failure(409,()->service.recover(owner,"New_Example_93!".toCharArray(),"QA recovery"));
        service.beginMaintenance("QA recovery maintenance");failure(503,()->service.resolveSession(old));
        service.recover(owner,"New_Example_93!".toCharArray(),"QA controlled reset");failure(401,()->service.resolveSession(old));
        var a=db.attempt(service);failure(401,()->db.login(service,a,request()));
        var grant=service.login(request(),a.id(),a.token(),a.cookie(),"qa-owner","New_Example_93!".toCharArray(),null);
        assertNotEquals(prior,service.resolveSession(grant.data().get("accessToken").toString()).permissions().authzVersion());
        assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_intent WHERE action_code='AUTH_RECOVERY'",Long.class));
        var audit=db.jdbc.queryForMap("SELECT actor_id,actor_reference,resource_id FROM admin_audit_intent WHERE action_code='AUTH_RECOVERY'");
        assertNull(audit.get("actor_id"));assertEquals(owner,((Number)audit.get("resource_id")).longValue());
        assertEquals("HOST:"+ProcessHandle.current().info().user().orElseThrow()+":"+ProcessHandle.current().pid(),audit.get("actor_reference"));
    }
    @Test void bootstrapRaceIsSingleWinnerOnVirginDatabase()throws Exception{
        try(var virgin=new AuthTestDatabase();var pool=Executors.newFixedThreadPool(2)){
            var auth=virgin.service();var start=new CountDownLatch(1);
            Callable<Integer> call=()->{start.await();try{virgin.bootstrap(auth);return 201;}catch(AdminAuthFailure e){return e.status();}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();var statuses=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(201,409),statuses);assertEquals(1,virgin.count("admin_account"));assertEquals(6,virgin.count("admin_role"));
        }
    }
    static final class FaultCache implements AdminGrantCache {
        private final AdminGrantCache delegate;volatile boolean failGet,failPut;
        FaultCache(AdminGrantCache delegate){this.delegate=delegate;}
        public void verifyVolatileConfiguration(){delegate.verifyVolatileConfiguration();}
        public void putIfAbsent(String ref,byte[] value,Duration ttl){if(failPut)throw AdminAuthFailure.unavailable();delegate.putIfAbsent(ref,value,ttl);}
        public Optional<byte[]> get(String ref){if(failGet)throw AdminAuthFailure.unavailable();return delegate.get(ref);}
    }
}
