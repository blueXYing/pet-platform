package com.petplatform.admin.biz.auth;

import com.petplatform.admin.biz.application.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.admin.biz.auth.AuthTestDatabase.*;

class AdminAuthLifecycleTest {
    AuthTestDatabase db;AdminAuthService service;long owner;
    @BeforeEach void open()throws Exception{db=new AuthTestDatabase();service=db.service();owner=db.bootstrap(service);}
    @AfterEach void close(){if(db!=null)db.close();}
    @Test void bootstrapCreatesSixRolesAndSingleSuperadminWithNoImplicitDomainActions(){
        assertEquals(6,db.count("admin_role"));assertEquals(1,db.count("admin_account"));
        failure(409,()->db.bootstrap(service));
        String token=db.loginToken(service);var view=service.resolveSession(token);
        assertEquals("ADMIN_WEB",view.principal().audience());assertEquals("ALL",view.permissions().dataScope().mode());
        assertEquals(1,view.permissions().roles().size());assertEquals("PLATFORM_SUPER_ADMIN",view.permissions().roles().getFirst().roleCode());
        assertEquals(List.of(),view.permissions().actionCodes());
        assertTrue(db.jdbc.queryForObject("SELECT password_hash FROM admin_account WHERE id=?",String.class,owner).contains("m=65536,t=3,p=1"));
    }
    @Test void attemptRequiresBothSecretsAndDoesNotReplaySecretFromRequestIdAlone(){
        String key=request();var created=service.createAttempt(key,"127.0.0.2");long aid=Long.parseLong(created.data().get("attemptId").toString());String token=created.data().get("attemptToken").toString();
        failure(409,()->service.createAttempt(key,"127.0.0.2"));assertEquals(1,db.count("admin_auth_attempt"));
        failure(401,()->service.requirements(aid,token,"wrong"));failure(401,()->service.requirements(aid,"wrong",created.bindingCookie()));
        assertEquals("NONE",service.requirements(aid,token,created.bindingCookie()).data().get("requiredVerification"));
        failure(401,()->service.resolveSession(token));failure(401,()->service.resolveSession(created.bindingCookie()));
    }
    @Test void loginReplaysSameGrantButDifferentParametersConflictAndNewLoginKicksOld(){
        var a=db.attempt(service);String key=request();var first=db.login(service,a,key);String token=first.data().get("accessToken").toString();
        assertEquals(first.data(),db.login(service,a,key).data());assertEquals(1,db.count("admin_web_session"));
        failure(409,()->service.login(key,a.id(),a.token(),a.cookie(),"qa-owner","Different_92!".toCharArray(),null));
        String next=db.loginToken(service);assertNotEquals(token,next);failure(401,()->service.resolveSession(token));
        failure(401,()->service.attemptResult(a.id(),a.token(),a.cookie(),key));assertNotNull(service.resolveSession(next));
    }
    @Test void unknownWrongAndDisabledAccountsReturnSamePublicFailureAndAuditNoSecrets(){
        List<String> codes=new ArrayList<>();
        for(String name:List.of("qa-owner","absent-owner")) {var a=db.attempt(service,"127.0.0."+codes.size());codes.add(failure(401,()->service.login(request(),a.id(),a.token(),a.cookie(),name,"Wrong_ONLY_92!".toCharArray(),null)).code());}
        db.jdbc.update("UPDATE admin_account SET status='DISABLED' WHERE id=?",owner);var a=db.attempt(service,"127.0.0.9");
        codes.add(failure(401,()->db.login(service,a,request())).code());assertEquals(List.of("COMMON_UNAUTHORIZED","COMMON_UNAUTHORIZED","COMMON_UNAUTHORIZED"),codes);
        assertEquals(0,db.count("admin_web_session"));
        assertTrue(db.jdbc.queryForList("SELECT reason FROM admin_audit_intent",String.class).stream().noneMatch(s->s.contains("Wrong_ONLY")||s.contains(PASSWORD)));
    }
    @Test void activityDoesNotExtendOnReplayAndReceiptExpiryIsNotSessionExpiry(){
        var a=db.attempt(service);String loginKey=request();var grant=db.login(service,a,loginKey);String token=grant.data().get("accessToken").toString();
        db.jdbc.update("UPDATE admin_auth_command SET receipt_window_anchor_at=TIMESTAMPADD(SECOND,-61,UTC_TIMESTAMP(3)),secret_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE namespace='LOGIN'");
        failure(401,()->service.attemptResult(a.id(),a.token(),a.cookie(),loginKey));assertNotNull(service.resolveSession(token));
        String activityKey=request();var first=service.activity(activityKey,token);assertEquals(first.data(),service.activity(activityKey,token).data());
        assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM admin_auth_command WHERE namespace='ACTIVITY'",Long.class));
        db.jdbc.update("UPDATE admin_web_session SET idle_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3))");
        failure(401,()->service.activity(request(),token));failure(401,()->service.resolveSession(token));
    }
    @Test void logoutIsIdempotentAndRevokedSessionCannotReadOriginalGrant(){
        var a=db.attempt(service);String key=request();String token=db.login(service,a,key).data().get("accessToken").toString();String logoutKey=request();
        assertEquals(true,service.logout(logoutKey,token).data().get("loggedOut"));assertEquals(true,service.logout(logoutKey,token).data().get("loggedOut"));
        failure(401,()->service.resolveSession(token));failure(401,()->service.attemptResult(a.id(),a.token(),a.cookie(),key));
    }
    @Test void captchaHasIndependentProofLifetimeAndProofIsBoundToAttemptAndConsumed(){
        var a=db.attempt(service);String createKey=request();var challenge=service.createCaptcha(createKey,a.id(),a.token(),a.cookie());long cid=Long.parseLong(challenge.data().get("captchaId").toString());
        assertEquals(challenge.data(),service.createCaptcha(createKey,a.id(),a.token(),a.cookie()).data());
        assertTrue(challenge.data().get("imageDataUrl").toString().startsWith("data:image/png;base64,"));db.knownCaptcha(cid,"ABC234");
        db.jdbc.update("UPDATE admin_captcha SET challenge_created_at=TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(3)),challenge_expires_at=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(3)) WHERE id=?",cid);
        var other=db.attempt(service);failure(401,()->service.verifyCaptcha(request(),other.id(),other.token(),other.cookie(),cid,"ABC234".toCharArray()));
        String verifyKey=request();var proof=service.verifyCaptcha(verifyKey,a.id(),a.token(),a.cookie(),cid,"ABC234".toCharArray());
        assertEquals(proof.data(),service.verifyCaptcha(verifyKey,a.id(),a.token(),a.cookie(),cid,"ABC234".toCharArray()).data());
        assertEquals(120L,db.jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,proof_issued_at,proof_expires_at) FROM admin_captcha WHERE id=?",Long.class,cid));
        String p=proof.data().get("captchaProof").toString();assertNotNull(service.login(request(),a.id(),a.token(),a.cookie(),"qa-owner",PASSWORD.toCharArray(),p));
        failure(401,()->service.verifyCaptcha(verifyKey,a.id(),a.token(),a.cookie(),cid,"ABC234".toCharArray()));
    }
    @Test void repeatedFailedLoginKeyIsCountedOnceAndFiveDistinctFailuresRequireCaptcha(){
        var a=db.attempt(service);String key=request();
        for(int i=0;i<2;i++)failure(401,()->service.login(key,a.id(),a.token(),a.cookie(),"qa-owner","Wrong_ONLY_92!".toCharArray(),null));
        assertEquals(1L,db.jdbc.queryForObject("SELECT MAX(count) FROM admin_login_failure",Long.class));
        for(int i=0;i<4;i++)failure(401,()->service.login(request(),a.id(),a.token(),a.cookie(),"qa-owner","Wrong_ONLY_92!".toCharArray(),null));
        assertEquals("CAPTCHA",service.requirements(a.id(),a.token(),a.cookie()).data().get("requiredVerification"));
        failure(401,()->db.login(service,a,request()));
    }
    @Test void currentScopeAndRevisionChangeWithoutTrustingRoleDisplayName(){
        String token=db.loginToken(service);var first=service.resolveSession(token);
        db.jdbc.update("DELETE FROM admin_account_role WHERE account_id=?",owner);
        long finance=db.jdbc.queryForObject("SELECT id FROM admin_role WHERE role_code='FINANCE_READER'",Long.class);
        db.jdbc.update("INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",owner,finance,owner);
        db.jdbc.update("UPDATE admin_role SET display_name='Platform Superadmin' WHERE id=?",finance);
        db.jdbc.update("UPDATE admin_account_scope SET mode='MERCHANT' WHERE account_id=?",owner);
        db.jdbc.update("INSERT INTO admin_scope_merchant(account_id,merchant_id) VALUES(?,2001)",owner);
        db.jdbc.update("INSERT INTO admin_extra_grant(account_id,action_code,granted_by,granted_at) VALUES(?,'unapproved.action',?,UTC_TIMESTAMP(3))",owner,owner);
        db.jdbc.update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1");
        var next=service.resolveSession(token);assertNotEquals(first.permissions().authzVersion(),next.permissions().authzVersion());
        assertEquals("MERCHANT",next.permissions().dataScope().mode());assertEquals(List.of("2001"),next.permissions().dataScope().merchantIds());assertTrue(next.permissions().actionCodes().isEmpty());
    }
    @Test void concurrentSameAttemptLoginCreatesOneSessionAndOneGrant()throws Exception{
        var a=db.attempt(service);String key=request();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Map<String,Object>> call=()->{start.await();return db.login(service,a,key).data();};
            var one=pool.submit(call);var two=pool.submit(call);start.countDown();
            assertEquals(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));assertEquals(1,db.count("admin_web_session"));
        }
    }
}
